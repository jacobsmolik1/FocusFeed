/**
 * Regression checks for the WebView injection scripts.
 * Run: NODE_PATH=/tmp/fftest/node_modules node scripts/verify_injection_rules.mjs
 */
import fs from 'fs';
import path from 'path';
import { createRequire } from 'module';
import { fileURLToPath } from 'url';

function loadJsdom() {
  const candidates = [
    '/tmp/fftest/package.json',
    path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../package.json')
  ];
  for (const pkg of candidates) {
    if (fs.existsSync(pkg)) {
      try {
        return createRequire(pkg)('jsdom').JSDOM;
      } catch (e) { /* try the next install */ }
    }
  }
  throw new Error('jsdom is required to run this check (npm install jsdom)');
}

const JSDOM = loadJsdom();

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const igSrc = fs.readFileSync(path.join(root, 'app/src/main/assets/rules_instagram.js'), 'utf8');
const ytSrc = fs.readFileSync(path.join(root, 'app/src/main/assets/rules_youtube.js'), 'utf8');

let failed = 0;
function assert(cond, msg) {
  if (!cond) {
    failed++;
    console.error('FAIL:', msg);
  } else {
    console.log('ok:', msg);
  }
}

function executableSource(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:'"/])\/\/.*$/gm, '$1');
}

function sourceGuards(name, src) {
  const code = executableSource(src);
  assert(!/display\s*:\s*unset/.test(code), `${name} has no display:unset`);
  assert(!/data-bloks/.test(code), `${name} has no bloks substring selector`);
  assert(!/:has\(/.test(code), `${name} has no :has()`);
  assert(!/\[role=["']presentation["']\]/.test(code), `${name} does not query role=presentation`);
  assert(!/history\.pushState\s*=/.test(code), `${name} does not wrap history.pushState`);
  assert(!/history\.replaceState\s*=/.test(code), `${name} does not wrap history.replaceState`);
  assert(!/\(reel\|p\)/.test(code), `${name} does not treat /p/ as a reel`);
}

sourceGuards('instagram', igSrc);
sourceGuards('youtube', ytSrc);
assert(igSrc.includes('isAllowStories'), 'instagram reads the stories pref');
assert(igSrc.includes('isStrictDmOnly'), 'instagram reads strict DM pref');
assert(igSrc.includes('isHideExplore'), 'instagram reads explore pref');
assert(ytSrc.includes('overlay-style="SHORTS"'), 'youtube still keys off the Shorts badge');
assert(ytSrc.includes("replace('/watch?v='"), 'youtube still redirects /shorts/ to watch');

function boot(url, html, src, bridge, beforeEval) {
  const dom = new JSDOM('<!doctype html><html><head></head><body>' + html + '</body></html>', {
    url,
    pretendToBeVisual: true,
    runScripts: 'dangerously'
  });
  const w = dom.window;
  w.innerWidth = 400;
  w.innerHeight = 800;
  w.Element.prototype.getBoundingClientRect = function () {
    if (this.id === 'giant' || this.id === 'shell') {
      return { x: 0, y: 0, top: 0, left: 0, right: 1000, bottom: 2000, width: 1000, height: 2000, toJSON() { return {}; } };
    }
    return { x: 0, y: 0, top: 0, left: 0, right: 120, bottom: 80, width: 120, height: 80, toJSON() { return {}; } };
  };
  w.FocusFeedBridge = bridge;
  if (beforeEval) beforeEval(w);
  const nativePush = w.history.pushState;
  const nativeReplace = w.history.replaceState;
  // jsdom cannot navigate. Record the URL the script would pass to location.replace.
  const code = src
    .replace(
      'window.location.replace(url);',
      '(window.__ff_nav = window.__ff_nav || []).push(url);'
    )
    .replace(
      "window.location.replace('/watch?v=' + match[1]);",
      "(window.__ff_nav = window.__ff_nav || []).push('/watch?v=' + match[1]);"
    );
  w.eval(code);
  w.eval(code); // second injection must be idempotent
  return { dom, w, nativePush, nativeReplace };
}

const bridge = {
  isHideExplore: () => true,
  isStrictDmOnly: () => false,
  isAllowStories: () => true,
  recordBlock: () => {}
};

{
  const { w, nativePush, nativeReplace } = boot(
    'https://www.instagram.com/direct/inbox/',
    `
      <div id="inbox" aria-label="Messages" style="display:flex">
        <a id="thread" href="/direct/t/123/" style="display:flex">Alex</a>
        <a id="reelshare" href="/reel/AbC123/">shared reel</a>
      </div>
      <article id="post"><a href="/p/PHOTO1/">photo</a><span>morning</span></article>
      <article id="audio"><a href="/reels/audio/xyz/">audio</a><span>song</span></article>
      <article id="reelcard"><a href="/reel/AbC123/">reel</a></article>
      <article id="sugg"><span>Suggested for you</span><a href="/p/S1/">s</a></article>
    `,
    igSrc,
    bridge
  );
  assert(w.history.pushState === nativePush, 'DM page keeps native pushState');
  assert(w.history.replaceState === nativeReplace, 'DM page keeps native replaceState');
  assert(w.getComputedStyle(w.document.getElementById('inbox')).display === 'flex', 'Messages column stays flex');
  assert(w.getComputedStyle(w.document.getElementById('thread')).display === 'flex', 'DM thread row stays flex');
  assert(w.getComputedStyle(w.document.getElementById('post')).display !== 'none', 'photo post stays visible');
  assert(w.getComputedStyle(w.document.getElementById('audio')).display !== 'none', 'reel-audio post stays visible');
  assert(w.getComputedStyle(w.document.getElementById('reelcard')).display === 'none', 'reel permalink card is hidden');
  assert(w.getComputedStyle(w.document.getElementById('sugg')).display === 'none', 'suggested card is hidden');
  const css = w.document.getElementById('focusfeed-css').textContent;
  assert(!/unset/.test(css), 'injected CSS has no unset');
  assert(css.includes('/reel/'), 'injected CSS still hides reel permalinks');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/',
    `
      <div id="page" role="presentation">
        <nav>
          <a id="reels" href="/reels/">Reels</a>
          <a id="dms" href="/direct/inbox/">DMs</a>
          <a id="home" href="/">Home</a>
          <a id="explore" href="/explore/">Explore</a>
        </nav>
        <p id="feed">Your feed</p>
      </div>
      <article id="shell">
        <nav><a href="/reels/">Reels</a><a href="/direct/inbox/">DMs</a></nav>
        <p>wrapped feed</p>
      </article>
    `,
    igSrc,
    bridge
  );
  assert(w.getComputedStyle(w.document.getElementById('page')).display !== 'none', 'presentation shell stays visible');
  assert(w.getComputedStyle(w.document.getElementById('feed')).display !== 'none', 'feed copy stays visible');
  assert(w.getComputedStyle(w.document.getElementById('shell')).display !== 'none', 'article shell that contains nav stays visible');
  assert(w.getComputedStyle(w.document.getElementById('reels')).display === 'none', 'Reels tab link is hidden');
  assert(w.getComputedStyle(w.document.getElementById('dms')).display !== 'none', 'DM tab link stays visible');
  assert(w.getComputedStyle(w.document.getElementById('explore')).display === 'none', 'Explore tab is hidden when pref is on');
  assert(w.location.pathname === '/', 'home is not redirected');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/',
    `<nav><a id="explore" href="/explore/">Explore</a><a id="reels" href="/reels/">Reels</a></nav>`,
    igSrc,
    { ...bridge, isHideExplore: () => false }
  );
  assert(w.getComputedStyle(w.document.getElementById('explore')).display !== 'none', 'Explore stays when pref is off');
  assert(w.getComputedStyle(w.document.getElementById('reels')).display === 'none', 'Reels stay hidden when Explore is allowed');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/accounts/login/?next=/direct/inbox/',
    `
      <input name="username">
      <input name="password">
      <article id="promo"><a href="/reels/">Get the app</a><p id="formcopy">Log in</p></article>
      <div id="giant" aria-label="Stories tray">should not matter</div>
    `,
    igSrc,
    { ...bridge, isStrictDmOnly: () => true }
  );
  assert(w.location.href.includes('/accounts/login'), 'strict mode does not bounce the login page');
  assert(w.getComputedStyle(w.document.getElementById('promo')).display !== 'none', 'login promo article is not swept');
  assert(w.getComputedStyle(w.document.getElementById('formcopy')).display !== 'none', 'login copy stays visible');
  assert(w.getComputedStyle(w.document.querySelector('#promo a')).display !== 'none', 'login reels link is not hidden');
  assert(!w.document.getElementById('focusfeed-css'), 'login installs no stylesheet');
  assert(!w.__ff_ig_tick, 'login does not install the blocker');
  assert(!w.__ff_ig_obs, 'login does not install a mutation observer');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/challenge/',
    `<form id="checkpoint"><p>Confirm it's you</p></form>`,
    igSrc,
    { ...bridge, isStrictDmOnly: () => true }
  );
  assert(!w.__ff_ig_tick, 'checkpoint installs no blocker');
  assert(!w.__ff_nav, 'checkpoint is not redirected');
  assert(w.location.pathname === '/challenge/', 'checkpoint url stays');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/',
    `
      <input name="username">
      <input type="password" name="password">
      <article id="reelcard"><a href="/reel/AbC123/">reel</a></article>
    `,
    igSrc,
    bridge
  );
  assert(!w.__ff_ig_tick, 'visible login form on home installs no blocker');
  assert(w.getComputedStyle(w.document.getElementById('reelcard')).display !== 'none', 'visible login form does not hide the page');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/',
    `<article id="reelcard"><a href="/reel/AbC123/">reel</a></article>`,
    igSrc,
    { ...bridge, isInjectionDisabled: () => true }
  );
  assert(!w.__ff_ig_tick, 'kill switch installs no blocker');
  assert(w.getComputedStyle(w.document.getElementById('reelcard')).display !== 'none', 'kill switch does not hide reels via css');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/',
    `
      <div id="tray" aria-label="Stories tray"><span>ring</span></div>
      <div id="giant" aria-label="Stories tray"><p id="inside">page</p></div>
      <article id="post"><a href="/p/PHOTO1/">photo</a></article>
    `,
    igSrc,
    { ...bridge, isAllowStories: () => false }
  );
  assert(w.getComputedStyle(w.document.getElementById('tray')).display === 'none', 'stories tray hides when pref is off');
  assert(w.getComputedStyle(w.document.getElementById('giant')).display !== 'none', 'full-viewport stories label is not the page');
  assert(w.getComputedStyle(w.document.getElementById('inside')).display !== 'none', 'content inside a giant tray stays');
  assert(w.getComputedStyle(w.document.getElementById('post')).display !== 'none', 'feed stays when stories are off');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/',
    `<div id="tray" aria-label="Stories tray">rings</div>`,
    igSrc,
    bridge
  );
  assert(w.getComputedStyle(w.document.getElementById('tray')).display !== 'none', 'stories tray stays when pref is on');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/',
    `
      <article id="reelcard"><a href="/reel/AbC123/">reel</a></article>
      <div hidden><input name="username"><input name="password"></div>
    `,
    igSrc,
    bridge,
    (win) => {
      Object.defineProperty(win.document.documentElement, 'clientWidth', {
        configurable: true,
        get: () => 400
      });
    }
  );
  assert(w.getComputedStyle(w.document.getElementById('reelcard')).display === 'none', 'hidden login inputs do not disable the reel shield');
  const css = w.document.getElementById('focusfeed-css');
  assert(css && css.textContent.includes('/reel/'), 'feed CSS stays up when the only login form is hidden');
  w.close();
}

{
  const { w } = boot(
    'https://www.instagram.com/reels/',
    `<div id="grid">reels grid</div>`,
    igSrc,
    bridge
  );
  assert(
    (w.__ff_nav || []).indexOf('https://www.instagram.com/') !== -1,
    'reels grid redirects home'
  );
  w.close();
}

{
  const { w, nativePush } = boot(
    'https://www.youtube.com/feed/subscriptions',
    `
      <ytd-app id="app" aria-label="Shorts">
        <div id="content">
          <ytd-rich-item-renderer id="short-card">
            <a href="/shorts/abc"></a>
            <ytd-thumbnail-overlay-time-status-renderer overlay-style="SHORTS"></ytd-thumbnail-overlay-time-status-renderer>
          </ytd-rich-item-renderer>
          <ytd-rich-item-renderer id="normal">
            <a href="/watch?v=xyz"></a>
          </ytd-rich-item-renderer>
          <ytd-reel-shelf-renderer id="shelf"></ytd-reel-shelf-renderer>
          <ytd-rich-section-renderer id="mixed">
            <ytd-rich-shelf-renderer id="mixed-shelf" is-shorts></ytd-rich-shelf-renderer>
            <ytd-rich-item-renderer id="mixed-video"><a href="/watch?v=keep"></a></ytd-rich-item-renderer>
          </ytd-rich-section-renderer>
          <div id="giant" aria-label="Shorts">whole page</div>
        </div>
      </ytd-app>
    `,
    ytSrc,
    { recordBlock: () => {} }
  );
  assert(w.history.pushState === nativePush, 'youtube keeps native pushState');
  assert(w.getComputedStyle(w.document.getElementById('app')).display !== 'none', 'ytd-app stays visible');
  assert(w.getComputedStyle(w.document.getElementById('content')).display !== 'none', 'subscriptions content stays visible');
  assert(w.getComputedStyle(w.document.getElementById('short-card')).display === 'none', 'shorts card is hidden');
  assert(w.getComputedStyle(w.document.getElementById('normal')).display !== 'none', 'subscription video stays visible');
  assert(w.getComputedStyle(w.document.getElementById('shelf')).display === 'none', 'reel shelf is hidden');
  assert(w.getComputedStyle(w.document.getElementById('mixed')).display !== 'none', 'mixed section is not blanked');
  assert(w.getComputedStyle(w.document.getElementById('mixed-shelf')).display === 'none', 'shorts shelf inside a mixed section is hidden');
  assert(w.getComputedStyle(w.document.getElementById('mixed-video')).display !== 'none', 'watch video in a mixed section stays');
  assert(w.getComputedStyle(w.document.getElementById('giant')).display !== 'none', 'full-viewport Shorts label is not hidden');
  const css = w.document.getElementById('focusfeed-yt-css').textContent;
  assert(css.includes('overlay-style="SHORTS"'), 'youtube CSS still targets the badge');
  assert(!/:has\(/.test(css), 'youtube CSS still has no :has()');
  w.close();
}

{
  const { w } = boot(
    'https://www.youtube.com/shorts/abc123',
    `<ytd-shorts id="player"></ytd-shorts>`,
    ytSrc,
    { recordBlock: () => {} }
  );
  assert((w.__ff_nav || []).indexOf('/watch?v=abc123') !== -1, 'shorts URL redirects to watch');
  w.close();
}

if (failed) {
  console.error(`\n${failed} assertion(s) failed`);
  process.exit(1);
}
console.log('\nAll injection regression checks passed');

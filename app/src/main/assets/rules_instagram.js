(function () {
  'use strict';

  /*
   * Hard stop. Login, checkpoints, consent, and the debug kill-switch must
   * not install CSS, observers, history hooks, or click capture. Kotlin also
   * refuses to evaluate this file until a sessionid cookie exists.
   */
  function pathName() { return location.pathname || ''; }
  function hostName() { return (location.hostname || '').toLowerCase(); }

  function isAuthLocation() {
    var path = pathName();
    var host = hostName();
    if (host.indexOf('accounts.') === 0 || host.indexOf('consent.') === 0) return true;
    if (path.indexOf('/accounts/') === 0) return true;
    if (path.indexOf('/challenge') === 0) return true;
    if (path.indexOf('/checkpoint') === 0) return true;
    if (path.indexOf('/auth_platform') === 0) return true;
    if (path.indexOf('/consent') === 0) return true;
    if (path.indexOf('/privacy/consent') === 0) return true;
    if (path.indexOf('/login') === 0 || path.indexOf('/signin') === 0) return true;
    if (path.indexOf('/two_factor') !== -1 || path.indexOf('/oauth') !== -1) return true;
    return false;
  }

  function bridgeCall(name) {
    try {
      if (!window.FocusFeedBridge || !FocusFeedBridge[name]) return null;
      return FocusFeedBridge[name]();
    } catch (e) { return null; }
  }

  function injectionDisabled() {
    return bridgeCall('isInjectionDisabled') === true;
  }

  function layoutEngineActive() {
    try {
      return (document.documentElement && document.documentElement.clientWidth > 0) ||
        (document.body && document.body.offsetWidth > 0);
    } catch (e) { return false; }
  }

  function isShown(el) {
    if (!el || el.isConnected === false) return false;
    if (el.closest && el.closest('template, noscript, [hidden]')) return false;
    if (!layoutEngineActive()) return true;
    try {
      if (!el.getClientRects || el.getClientRects().length === 0) return false;
      var style = window.getComputedStyle(el);
      if (style && (style.display === 'none' || style.visibility === 'hidden')) return false;
    } catch (e) {}
    return true;
  }

  function visibleLogin() {
    var pass = document.querySelector('input[name="password"], input[autocomplete="current-password"], input[type="password"]');
    if (!isShown(pass)) return false;
    var user = document.querySelector(
      'input[name="username"], input[name="email"], input[autocomplete="username"], input[autocomplete="email"], input[type="email"]'
    );
    return isShown(user);
  }

  function disarm() {
    var style = document.getElementById('focusfeed-css');
    if (style && style.parentNode) style.parentNode.removeChild(style);
    var nodes = document.querySelectorAll('[data-ff-hidden]');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].style.display = '';
      nodes[i].removeAttribute('data-ff-hidden');
    }
    if (window.__ff_ig_obs) {
      try { window.__ff_ig_obs.disconnect(); } catch (e) {}
      window.__ff_ig_obs = null;
    }
  }

  if (isAuthLocation() || injectionDisabled() || visibleLogin()) {
    disarm();
    return;
  }

  if (window.__ff_ig_tick) {
    try { window.__ff_ig_tick(); } catch (e) {}
    return;
  }

  var DEBOUNCE_MS = 50;
  var _redirects = 0;
  var _navTimer = null;
  var _mutTimer = null;
  var _lastPolledPath = '';

  function prefHideExplore() {
    var v = bridgeCall('isHideExplore');
    return v === null ? true : !!v;
  }
  function prefStrictDmOnly() {
    return bridgeCall('isStrictDmOnly') === true;
  }
  function prefAllowStories() {
    var v = bridgeCall('isAllowStories');
    return v === null ? true : !!v;
  }
  function recordBlock() {
    try { if (window.FocusFeedBridge) FocusFeedBridge.recordBlock('instagram'); }
    catch (e) {}
  }

  /* Allowlist: tab and permalink anchors only. No display:unset, no bloks, no :has(). */
  function buildCSS() {
    var css = 'a[href="/reels"],a[href="/reels/"],a[href^="/reel/"]{display:none!important;}\n';
    if (prefHideExplore()) {
      css += 'a[href="/explore"],a[href="/explore/"]{display:none!important;}\n';
    }
    return css;
  }

  function injectCSS() {
    var css = buildCSS();
    var existing = document.getElementById('focusfeed-css');
    if (existing) { existing.textContent = css; return; }
    var el = document.createElement('style');
    el.id = 'focusfeed-css';
    el.textContent = css;
    (document.head || document.documentElement).appendChild(el);
  }

  function hrefPath(href) {
    if (!href) return '';
    var raw = href;
    if (raw.indexOf('http://') === 0 || raw.indexOf('https://') === 0) {
      try { raw = new URL(raw, location.origin).pathname || ''; }
      catch (e) { raw = href; }
    }
    var cut = raw.length;
    var q = raw.indexOf('?');
    var hash = raw.indexOf('#');
    if (q !== -1) cut = q;
    if (hash !== -1 && hash < cut) cut = hash;
    return raw.slice(0, cut);
  }

  function isReelPermalink(href) {
    return /^\/reel\/[A-Za-z0-9_-]+\/?$/.test(hrefPath(href));
  }
  function isReelsTab(href) {
    var p = hrefPath(href);
    return p === '/reels' || p === '/reels/';
  }
  function isExploreTab(href) {
    var p = hrefPath(href);
    return p === '/explore' || p === '/explore/';
  }

  function coversViewport(el) {
    try {
      var rect = el.getBoundingClientRect();
      if (rect.width < 8 || rect.height < 8) return false;
      var w = window.innerWidth || 0;
      var h = window.innerHeight || 0;
      if (w < 8 || h < 8) return false;
      return rect.width >= w * 0.9 && rect.height >= h * 0.85;
    } catch (e) { return false; }
  }

  function looksLikeChrome(el) {
    if (!el || !el.querySelector) return false;
    var nav = el.querySelector('nav, [role="navigation"], [role="tablist"]');
    var direct = el.querySelector('a[href^="/direct"]');
    var home = el.querySelector('a[href="/"], a[href="/reels"], a[href="/reels/"]');
    if (nav && (direct || home)) return true;
    if (direct && home) return true;
    return false;
  }

  function isShell(el) {
    if (!el || el === document.body || el === document.documentElement) return true;
    if (el.id === 'react-root' || el.id === 'mount_0_0') return true;
    if (looksLikeChrome(el) || coversViewport(el)) return true;
    return false;
  }

  function hide(el) {
    if (!el || isShell(el) || el.getAttribute('data-ff-hidden')) return false;
    el.style.display = 'none';
    el.setAttribute('data-ff-hidden', '1');
    return true;
  }

  function hasSuggestedHeader(el) {
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    var node;
    var seen = 0;
    while ((node = walker.nextNode())) {
      if (++seen > 30) return false;
      var t = node.nodeValue && node.nodeValue.trim().toLowerCase();
      if (t === 'suggested for you' || t === 'suggested posts') return true;
    }
    return false;
  }

  function sweep() {
    var blocked = false;
    var hidden = document.querySelectorAll('[data-ff-hidden]');
    for (var r = 0; r < hidden.length; r++) {
      if (isShell(hidden[r])) {
        hidden[r].style.display = '';
        hidden[r].removeAttribute('data-ff-hidden');
      }
    }

    var cards = document.querySelectorAll('article');
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      var links = card.querySelectorAll('a[href]');
      var reel = false;
      for (var a = 0; a < links.length; a++) {
        if (isReelPermalink(links[a].getAttribute('href'))) { reel = true; break; }
      }
      if (!reel && hasSuggestedHeader(card)) reel = true;
      if (reel && hide(card)) blocked = true;
    }

    var anchors = document.querySelectorAll('a[href]');
    for (var j = 0; j < anchors.length; j++) {
      var link = anchors[j];
      var href = link.getAttribute('href') || '';
      var block = isReelPermalink(href) || isReelsTab(href) || (prefHideExplore() && isExploreTab(href));
      if (!block) continue;
      if (hide(link)) blocked = true;
    }

    if (!prefAllowStories()) {
      var trays = document.querySelectorAll('[aria-label="Stories tray"], [aria-label="Stories"], [aria-label="Story tray"]');
      for (var t = 0; t < trays.length; t++) {
        if (trays[t].querySelector('textarea, [role="textbox"]')) continue;
        if (hide(trays[t])) blocked = true;
      }
    }

    if (blocked) recordBlock();
  }

  function go(url) {
    if (_redirects >= 4) return;
    _redirects++;
    try { window.location.replace(url); } catch (e) {}
  }

  function redirectDistractions() {
    var path = pathName();
    if (path.indexOf('/direct') === 0) {
      _redirects = 0;
      return;
    }
    var reelSurface = path === '/reels' || path === '/reels/' || path.indexOf('/reels/') === 0 ||
      /^\/reel\//.test(path);
    if (reelSurface || (prefStrictDmOnly() && path.indexOf('/direct') !== 0)) {
      go(prefStrictDmOnly()
        ? 'https://www.instagram.com/direct/inbox/'
        : 'https://www.instagram.com/');
    }
  }

  function tick() {
    if (isAuthLocation() || injectionDisabled() || visibleLogin()) {
      disarm();
      return;
    }
    injectCSS();
    sweep();
    redirectDistractions();
  }

  function startObserver() {
    var root = document.body || document.documentElement;
    if (!root) { setTimeout(startObserver, 200); return; }
    var obs = new MutationObserver(function () {
      clearTimeout(_mutTimer);
      _mutTimer = setTimeout(tick, DEBOUNCE_MS);
    });
    obs.observe(root, { childList: true, subtree: true });
    window.__ff_ig_obs = obs;
  }

  document.addEventListener('click', function (e) {
    if (isAuthLocation() || injectionDisabled() || visibleLogin()) return;
    var a = e.target && e.target.closest && e.target.closest('a[href]');
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (!(isReelPermalink(href) || isReelsTab(href) || (prefHideExplore() && isExploreTab(href)))) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    recordBlock();
  }, true);

  window.addEventListener('popstate', function () {
    clearTimeout(_navTimer);
    _navTimer = setTimeout(tick, DEBOUNCE_MS);
  });
  setInterval(function () {
    var p = pathName();
    if (p !== _lastPolledPath) {
      _lastPolledPath = p;
      tick();
    }
  }, 400);
  startObserver();

  window.__ff_ig_tick = tick;
  tick();
})();
//# sourceURL=rules_instagram.js

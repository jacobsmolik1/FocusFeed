(function () {
  'use strict';

  /*
   * Re-injection (onPageStarted + onPageFinished) must be idempotent.
   * A second eval only re-runs the sweep; it must not stack listeners.
   */
  if (window.__ff_ig_tick) {
    try { window.__ff_ig_tick(); } catch (e) { /* keep the page up */ }
    return;
  }

  var STYLE_ID = 'focusfeed-css';
  var DEBOUNCE_MS = 15;
  var _redirects = 0;
  var _swipeGuardActive = false;
  var _touchStartY = 0;
  var _navTimer = null;
  var _mutTimer = null;
  var _lastPolledPath = '';

  function prefHideExplore() {
    try { return !!(window.FocusFeedBridge && FocusFeedBridge.isHideExplore()); }
    catch (e) { return true; }
  }

  function prefStrictDmOnly() {
    try { return !!(window.FocusFeedBridge && FocusFeedBridge.isStrictDmOnly()); }
    catch (e) { return false; }
  }

  function prefAllowStories() {
    try {
      if (!window.FocusFeedBridge || !FocusFeedBridge.isAllowStories) return true;
      return !!FocusFeedBridge.isAllowStories();
    } catch (e) { return true; }
  }

  function recordBlock() {
    try { if (window.FocusFeedBridge) FocusFeedBridge.recordBlock('instagram'); }
    catch (e) { /* bridge not ready yet */ }
  }

  /*
   * CSS may only hide anchors by URL. Never use:
   *   - display:unset  (forces display:inline and collapses DM thread rows,
   *     whose hrefs are /direct/t/…, and any node labeled "Messages")
   *   - [data-bloks-name*="reel"]  (matches the stories tray / DM shell)
   *   - :has() or [role="presentation"]
   * Hiding a whole card is done in JS after a shell-size check.
   */
  function buildCSS() {
    /* Exact tab/permalink prefixes only. [href*="/reels/"] also matches
       /reels/audio/… on ordinary posts and must not be a blanket hide. */
    var css = ''
      + 'a[href="/reels"],a[href="/reels/"],a[href^="/reels?"],'
      + 'a[href^="/reel/"]{display:none!important;}\n';
    if (prefHideExplore()) {
      css += 'a[href="/explore"],a[href="/explore/"],a[href^="/explore?"]{display:none!important;}\n';
    }
    return css;
  }

  function injectCSS() {
    var css = buildCSS();
    var existing = document.getElementById(STYLE_ID);
    if (existing) { existing.textContent = css; return; }
    var el = document.createElement('style');
    el.id = STYLE_ID;
    el.textContent = css;
    (document.head || document.documentElement).appendChild(el);
  }

  function clearCSS() {
    var existing = document.getElementById(STYLE_ID);
    if (existing) existing.textContent = '';
  }

  function isAuthPath(path) {
    if (!path) return false;
    if (path.indexOf('/accounts/') === 0) return true;
    if (path.indexOf('/challenge') === 0) return true;
    if (path.indexOf('/auth_platform') === 0) return true;
    if (path.indexOf('/consent') === 0) return true;
    if (path.indexOf('/privacy/consent') === 0) return true;
    if (path.indexOf('/checkpoint') === 0) return true;
    if (path.indexOf('/two_factor') !== -1) return true;
    return false;
  }

  function layoutEngineActive() {
    try {
      return (document.documentElement && document.documentElement.clientWidth > 0) ||
        (document.body && document.body.offsetWidth > 0);
    } catch (e) {
      return false;
    }
  }

  /* Hidden "session expired" inputs ship inside the logged-in feed.
     Treating those as login would clear the shield and let Reels back in. */
  function isShown(el) {
    if (!el || (el.isConnected === false)) return false;
    if (el.closest && el.closest('template, noscript, [hidden]')) return false;
    if (!layoutEngineActive()) return true;
    try {
      if (!el.getClientRects || el.getClientRects().length === 0) return false;
      var style = window.getComputedStyle(el);
      if (style && (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0')) return false;
    } catch (e) { /* assume visible */ }
    return true;
  }

  function hasLoginForm() {
    var pass = document.querySelector('input[name="password"], input[autocomplete="current-password"]');
    if (!isShown(pass)) return false;
    var user = document.querySelector(
      'input[name="username"], input[name="email"], input[autocomplete="username"], input[autocomplete="email"]'
    );
    return isShown(user);
  }

  function isAuthSurface() {
    var host = (location.hostname || '').toLowerCase();
    if (host.indexOf('accounts.') === 0 || host.indexOf('consent.') === 0) return true;
    if (isAuthPath(location.pathname || '')) return true;
    if (hasLoginForm()) return true;
    return false;
  }

  function coversViewport(el) {
    try {
      var rect = el.getBoundingClientRect();
      if (rect.width < 8 || rect.height < 8) return false;
      var w = window.innerWidth || document.documentElement.clientWidth || 0;
      var h = window.innerHeight || document.documentElement.clientHeight || 0;
      if (w < 8 || h < 8) return false;
      return rect.width >= w * 0.9 && rect.height >= h * 0.85;
    } catch (e) {
      return false;
    }
  }

  /* A node that contains the app chrome is the page shell, not a reel card.
     Matching the shell is what blanked the feed when sweep used [role="presentation"]. */
  function looksLikePageChrome(el) {
    if (!el || !el.querySelector) return false;
    var hasNav = el.querySelector('nav, [role="navigation"], [role="tablist"]');
    var direct = el.querySelector('a[href^="/direct"], a[href*="/direct/inbox"], a[href*="/direct/t/"]');
    var home = el.querySelector('a[href="/"]');
    var reelTab = el.querySelector('a[href="/reels"], a[href="/reels/"], a[href^="/reels?"]');
    if (hasNav && (direct || home || reelTab)) return true;
    /* Some shells omit <nav> but still contain both the DM tab and Home/Reels. */
    if (direct && (home || reelTab)) return true;
    return false;
  }

  function isPageShell(el) {
    if (!el || el === document.body || el === document.documentElement) return true;
    var id = el.id || '';
    if (id === 'react-root' || id === 'mount_0_0') return true;
    if (el.querySelector && el.querySelector('input[name="password"], input[name="username"]')) return true;
    if (looksLikePageChrome(el)) return true;
    if (coversViewport(el)) return true;
    return false;
  }

  function isSafeToHide(el) {
    return !!el && !isPageShell(el);
  }

  function hideElement(el, reason) {
    if (!isSafeToHide(el)) return false;
    if (el.getAttribute('data-ff-hidden')) return false;
    el.style.display = 'none';
    el.setAttribute('data-ff-hidden', reason);
    return true;
  }

  function restoreHidden(reason) {
    var nodes = document.querySelectorAll('[data-ff-hidden="' + reason + '"]');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].style.display = '';
      nodes[i].removeAttribute('data-ff-hidden');
    }
  }

  function restoreAllHidden() {
    var nodes = document.querySelectorAll('[data-ff-hidden]');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].style.display = '';
      nodes[i].removeAttribute('data-ff-hidden');
    }
  }

  function hasSuggestedHeader(el) {
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    var node;
    var seen = 0;
    while ((node = walker.nextNode())) {
      if (++seen > 40) return false;
      var t = node.nodeValue && node.nodeValue.trim().toLowerCase();
      if (t === 'suggested for you' || t === 'suggested posts') return true;
    }
    return false;
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

  function isReelHref(href) {
    var path = hrefPath(href);
    return path.indexOf('/reel/') !== -1 || path === '/reels' || path.indexOf('/reels/') === 0;
  }

  function isExploreHref(href) {
    var path = hrefPath(href);
    return path === '/explore' || path.indexOf('/explore/') === 0;
  }

  function isReelPermalink(href) {
    return /^\/reel\/[A-Za-z0-9_-]+/.test(hrefPath(href));
  }

  function isReelArticle(card) {
    var links = card.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      if (isReelPermalink(links[i].getAttribute('href'))) return true;
    }
    /* Exact badge labels only — substring "reel" also matches story chrome. */
    if (card.querySelector('[aria-label="Reel"], [aria-label="Reels"], [aria-label="Clips"]')) return true;
    if (hasSuggestedHeader(card)) return true;
    return false;
  }

  function revalidateHidden() {
    var nodes = document.querySelectorAll('[data-ff-hidden]');
    for (var i = 0; i < nodes.length; i++) {
      if (!isSafeToHide(nodes[i])) {
        nodes[i].style.display = '';
        nodes[i].removeAttribute('data-ff-hidden');
      }
    }
  }

  function sweepFeed() {
    var blocked = false;
    revalidateHidden();
    var cards = document.querySelectorAll('article');
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      if (!isReelArticle(card)) continue;
      if (hideElement(card, 'reel')) blocked = true;
    }

    var navLinks = document.querySelectorAll('a[href]');
    for (var j = 0; j < navLinks.length; j++) {
      var link = navLinks[j];
      var href = link.getAttribute('href') || '';
      var blockedHref = isReelHref(href) || (prefHideExplore() && isExploreHref(href));
      if (!blockedHref) continue;
      if (hideElement(link, 'nav')) blocked = true;
      var parent = link.parentElement;
      if (parent && (parent.getAttribute('role') === 'button' || parent.getAttribute('role') === 'tab')) {
        if (hideElement(parent, 'nav')) blocked = true;
      }
    }

    var labelled = document.querySelectorAll('[role="button"][aria-label], [role="tab"][aria-label]');
    for (var k = 0; k < labelled.length; k++) {
      var el = labelled[k];
      var label = (el.getAttribute('aria-label') || '').trim().toLowerCase();
      if (!label || label.length > 48) continue;
      var reelLabel = label === 'reel' || label === 'reels' ||
        label.indexOf('reel ') === 0 || label.indexOf('reels') === 0 ||
        label === 'clips' || label.indexOf('clips ') === 0;
      var exploreLabel = prefHideExplore() && (label === 'explore' || label.indexOf('explore ') === 0);
      if ((reelLabel || exploreLabel) && hideElement(el, 'nav')) blocked = true;
    }

    if (!prefAllowStories()) {
      var trays = document.querySelectorAll(
        '[aria-label="Stories tray"], [aria-label="Stories"], [aria-label="Story tray"]'
      );
      for (var t = 0; t < trays.length; t++) {
        var tray = trays[t];
        if (tray.querySelector('textarea, [contenteditable="true"], [role="textbox"]')) continue;
        if (hideElement(tray, 'stories')) blocked = true;
      }
    } else {
      restoreHidden('stories');
    }

    if (blocked) recordBlock();
  }

  function enforceStrictMode() {
    if (!prefStrictDmOnly()) return;
    var path = location.pathname || '';
    if (path.indexOf('/direct') === 0) {
      _redirects = 0;
      return;
    }
    /* Never bounce login, 2FA, checkpoint, or consent — that loop blanks the DM tab. */
    if (isAuthSurface()) return;
    navigateTop('https://www.instagram.com/direct/inbox/');
  }

  function onTouchStart(e) {
    _touchStartY = (e.touches && e.touches[0] && e.touches[0].clientY) || 0;
  }
  function onTouchMove(e) {
    var y = (e.touches && e.touches[0] && e.touches[0].clientY) || _touchStartY;
    if (Math.abs(y - _touchStartY) > 20) e.stopImmediatePropagation();
  }
  function onWheel(e) {
    if (Math.abs(e.deltaY) > 20) e.stopImmediatePropagation();
  }

  function installSwipeGuard() {
    if (_swipeGuardActive) return;
    _swipeGuardActive = true;
    document.addEventListener('touchstart', onTouchStart, true);
    document.addEventListener('touchmove', onTouchMove, { capture: true, passive: false });
    document.addEventListener('wheel', onWheel, { capture: true, passive: false });
  }

  function removeSwipeGuard() {
    if (!_swipeGuardActive) return;
    _swipeGuardActive = false;
    document.removeEventListener('touchstart', onTouchStart, true);
    document.removeEventListener('touchmove', onTouchMove, true);
    document.removeEventListener('wheel', onWheel, true);
  }

  /* Only drop preloaded reel videos. Never remove a wrapper that also holds the
     visible video or the page shell — that previously ate the whole player. */
  function removeQueuedReelSiblings() {
    var videos = document.querySelectorAll('video');
    if (videos.length < 2) return;
    var primary = videos[0];
    for (var i = 1; i < videos.length; i++) {
      var container = videos[i].parentElement;
      if (!container || container === document.body) continue;
      if (container.contains(primary)) continue;
      if (!isSafeToHide(container)) continue;
      container.remove();
    }
  }

  function blockReelSurfaces() {
    var path = location.pathname || '';
    /* /p/ is a normal post. Only the reel viewer gets the swipe lock. */
    if (/^\/reel\/[A-Za-z0-9_-]+/.test(path)) {
      installSwipeGuard();
      removeQueuedReelSiblings();
      return;
    }
    removeSwipeGuard();
    if (path === '/reels' || path === '/reels/' || path.indexOf('/reels/') === 0) {
      var dest = prefStrictDmOnly()
        ? 'https://www.instagram.com/direct/inbox/'
        : 'https://www.instagram.com/';
      navigateTop(dest);
    }
  }

  function navigateTop(url) {
    if (_redirects >= 4) return;
    _redirects++;
    try { window.location.replace(url); } catch (e) { /* document is already going away */ }
  }

  function tick() {
    if (isAuthSurface()) {
      clearCSS();
      restoreAllHidden();
      removeSwipeGuard();
      return;
    }
    injectCSS();
    sweepFeed();
    enforceStrictMode();
    blockReelSurfaces();
  }

  function onNavigate() {
    clearTimeout(_navTimer);
    _navTimer = setTimeout(tick, DEBOUNCE_MS);
  }

  function startObserver() {
    var root = document.body || document.documentElement;
    if (!root) { setTimeout(startObserver, 100); return; }
    var obs = new MutationObserver(function () {
      clearTimeout(_mutTimer);
      _mutTimer = setTimeout(tick, DEBOUNCE_MS);
    });
    obs.observe(root, { childList: true, subtree: true });
  }

  function installClickGuard() {
    document.addEventListener('click', function (e) {
      if (isAuthSurface()) return;
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (!a) return;
      var href = a.getAttribute('href') || '';
      var blocked = isReelHref(href) || (prefHideExplore() && isExploreHref(href));
      if (!blocked) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      recordBlock();
    }, true);
  }

  function startPolling() {
    setInterval(function () {
      var p = location.pathname;
      if (p !== _lastPolledPath) {
        _lastPolledPath = p;
        onNavigate();
      }
    }, 400);
  }

  /*
   * Do not wrap history.pushState / replaceState. Instagram reads the native
   * functions during boot; wrapping them from onPageStarted has left the
   * client unmounted (blank document). Path changes are covered by polling,
   * popstate, and the mutation observer.
   */
  window.addEventListener('popstate', onNavigate);
  startObserver();
  installClickGuard();
  startPolling();

  window.__ff_ig_tick = tick;
  tick();
})();
//# sourceURL=rules_instagram.js

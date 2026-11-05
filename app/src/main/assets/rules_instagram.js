(function () {
  'use strict';

  /* ─── Guard: register observers/patches only once per page lifetime ─── */
  var _registered = !!window.__ff_ig_registered;
  window.__ff_ig_registered = true;

  /* ─── Constants ──────────────────────────────────────────────────────── */
  var STYLE_ID    = 'focusfeed-css';
  var REEL_PATH   = /^\/(reel|p)\/[A-Za-z0-9_-]+\/?/;
  var DEBOUNCE_MS = 15;

  /* ─── Preference helpers ─────────────────────────────────────────────── */
  function prefHideExplore() {
    try { return !!(window.FocusFeedBridge && FocusFeedBridge.isHideExplore()); }
    catch (e) { return true; }
  }

  function prefStrictDmOnly() {
    try { return !!(window.FocusFeedBridge && FocusFeedBridge.isStrictDmOnly()); }
    catch (e) { return false; }
  }

  /* ─── Bridge helper ──────────────────────────────────────────────────── */
  function recordBlock() {
    try { if (window.FocusFeedBridge) FocusFeedBridge.recordBlock('instagram'); }
    catch (e) { /* bridge not ready yet */ }
  }

  /* ─── CSS shield ─────────────────────────────────────────────────────── */
  function buildCSS() {
    return (
      'a[href^="/reels"]                             { display: none !important; }\n' +
      'a[href*="/reels/"]                            { display: none !important; }\n' +
      (prefHideExplore()
        ? 'a[href^="/explore"]                       { display: none !important; }\n'
        : '') +
      '[role="button"][aria-label*="Reel" i]         { display: none !important; }\n' +
      '[data-bloks-name*="reel"]                     { display: none !important; }\n' +
      '[data-bloks-name*="clips"]                    { display: none !important; }\n' +
      '[data-bloks-name*="suggested_header"]         { display: none !important; }\n' +
      'article:has([data-bloks-name*="suggested"])   { display: none !important; }\n' +
      /* Preserve DMs / Messages / Notifications / stories */
      'a[href^="/direct"]                            { display: unset !important; }\n' +
      '[aria-label="Messages" i]                     { display: unset !important; }\n' +
      '[aria-label="Notifications" i]                { display: unset !important; }\n' +
      '[aria-label="Stories tray"]                   { display: unset !important; }\n' +
      '[data-bloks-name*="stories_tray"]             { display: unset !important; }\n'
    );
  }

  function injectCSS() {
    var existing = document.getElementById(STYLE_ID);
    var css = buildCSS();
    if (existing) { existing.textContent = css; return; }
    var el = document.createElement('style');
    el.id = STYLE_ID;
    el.textContent = css;
    (document.head || document.documentElement).appendChild(el);
  }

  /* ─── Text-node walk for "Suggested" headers ─────────────────────────── */
  function hasSuggestedHeader(el) {
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null, false);
    var node;
    while ((node = walker.nextNode())) {
      var t = node.nodeValue && node.nodeValue.trim().toLowerCase();
      if (t === 'suggested for you' || t === 'suggested posts') return true;
    }
    return false;
  }

  /* ─── DOM sweep ──────────────────────────────────────────────────────── */
  function sweepFeed() {
    var blocked = false;

    /* Article / presentation cards */
    var cards = document.querySelectorAll('article, [role="presentation"]');
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      if (card.style.display === 'none') continue;
      if (
        card.querySelector('a[href*="/reels/"]') ||
        card.querySelector('[aria-label*="Reel" i]') ||
        card.querySelector('[aria-label*="Clips" i]') ||
        hasSuggestedHeader(card)
      ) {
        card.style.display = 'none';
        blocked = true;
      }
    }

    /* Nav / tablist links */
    var navLinks = document.querySelectorAll('nav a, [role="tablist"] a');
    for (var j = 0; j < navLinks.length; j++) {
      var link = navLinks[j];
      var href = link.getAttribute('href') || '';
      if (
        href.startsWith('/reels') ||
        href.indexOf('/reels/') !== -1 ||
        (prefHideExplore() && href.startsWith('/explore'))
      ) {
        link.style.display = 'none';
        blocked = true;
      }
    }

    if (blocked) recordBlock();
  }

  /* ─── Strict DM-only: redirect if not on /direct/ ───────────────────── */
  function enforceStrictMode() {
    if (!prefStrictDmOnly()) return;
    if (location.pathname.indexOf('/direct') !== 0) {
      window.location.replace('https://www.instagram.com/direct/inbox/');
    }
  }

  /* ─── Swipe guard (single-video DM links) ────────────────────────────── */
  var _swipeGuardActive = false;
  var _touchStartY = 0;

  function onTouchStart(e) {
    _touchStartY = (e.touches[0] && e.touches[0].clientY) || 0;
  }
  function onTouchMove(e) {
    var delta = Math.abs(((e.touches[0] && e.touches[0].clientY) || _touchStartY) - _touchStartY);
    if (delta > 20) e.stopImmediatePropagation();
  }
  function onWheel(e) {
    if (Math.abs(e.deltaY) > 20) e.stopImmediatePropagation();
  }

  function installSwipeGuard() {
    if (_swipeGuardActive) return;
    _swipeGuardActive = true;
    document.addEventListener('touchstart', onTouchStart, true);
    document.addEventListener('touchmove',  onTouchMove,  { capture: true, passive: false });
    document.addEventListener('wheel',      onWheel,      { capture: true, passive: false });
  }

  function removeSwipeGuard() {
    if (!_swipeGuardActive) return;
    _swipeGuardActive = false;
    document.removeEventListener('touchstart', onTouchStart, true);
    document.removeEventListener('touchmove',  onTouchMove,  true);
    document.removeEventListener('wheel',      onWheel,      true);
  }

  function removeQueuedReelSiblings() {
    var videos = document.querySelectorAll('video[playsinline]');
    for (var i = 1; i < videos.length; i++) {
      var container = videos[i].closest('div');
      if (container) container.remove();
    }
  }

  /* ─── SPA navigation ─────────────────────────────────────────────────── */
  var _navTimer = null;

  function onNavigate(url) {
    clearTimeout(_navTimer);
    _navTimer = setTimeout(function () {
      injectCSS();
      sweepFeed();
      enforceStrictMode();
      var path = typeof url === 'string' ? url.replace(/\?.*$/, '') : location.pathname;
      if (REEL_PATH.test(path)) {
        installSwipeGuard();
        removeQueuedReelSiblings();
      } else {
        removeSwipeGuard();
      }
    }, DEBOUNCE_MS);
  }

  /* ─── MutationObserver ───────────────────────────────────────────────── */
  var _mutTimer = null;

  function startObserver() {
    var root = document.getElementById('root') || document.body;
    if (!root) { setTimeout(startObserver, 100); return; }
    var obs = new MutationObserver(function () {
      clearTimeout(_mutTimer);
      _mutTimer = setTimeout(function () { injectCSS(); sweepFeed(); }, DEBOUNCE_MS);
    });
    obs.observe(root, { childList: true, subtree: true });
  }

  /* ─── History patches ────────────────────────────────────────────────── */
  function patchHistory() {
    var origPush    = history.pushState.bind(history);
    var origReplace = history.replaceState.bind(history);
    history.pushState = function (s, t, url) {
      origPush(s, t, url);
      onNavigate(typeof url === 'string' ? url : location.pathname);
    };
    history.replaceState = function (s, t, url) {
      origReplace(s, t, url);
      onNavigate(typeof url === 'string' ? url : location.pathname);
    };
    window.addEventListener('popstate', function () { onNavigate(location.pathname); });
  }

  /* ─── Click interception (belt-and-suspenders for CSS misses) ───────── */
  function installClickGuard() {
    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (!a) return;
      var href = a.getAttribute('href') || '';
      var blocked =
        href.startsWith('/reels') ||
        href.indexOf('/reels/') !== -1 ||
        (prefHideExplore() && href.startsWith('/explore'));
      if (blocked) {
        e.preventDefault();
        e.stopImmediatePropagation();
      }
    }, true);
  }

  /* ─── Polling fallback (catches navigations that bypass history patch) ─ */
  var _lastPolledPath = '';
  function startPolling() {
    setInterval(function () {
      var p = location.pathname;
      if (p !== _lastPolledPath) {
        _lastPolledPath = p;
        onNavigate(p);
      }
    }, 400);
  }

  /* ─── Boot ───────────────────────────────────────────────────────────── */
  injectCSS();
  sweepFeed();
  enforceStrictMode();

  if (!_registered) {
    patchHistory();
    startObserver();
    installClickGuard();
    startPolling();
  }

  onNavigate(location.pathname);

})();

(function () {
  'use strict';

  /* ─── Constants ─────────────────────────────────────────────────── */
  var STYLE_ID   = 'focusfeed-css';
  var REEL_PATH  = /^\/(reel|p)\/[A-Za-z0-9_-]+\/?/;
  var DEBOUNCE_MS = 15;

  /* ─── Preference helpers ─────────────────────────────────────────── */
  function hideExplore() {
    try { return window.FocusFeedBridge && FocusFeedBridge.getPref('hideExplore') === true; }
    catch (e) { return false; }
  }

  function isStrictDmOnly() {
    try { return window.FocusFeedBridge && FocusFeedBridge.isStrictDmOnly() === true; }
    catch (e) { return false; }
  }

  /* ─── Bridge helper ──────────────────────────────────────────────── */
  function recordBlock() {
    try {
      if (window.FocusFeedBridge) FocusFeedBridge.recordBlock('instagram');
    } catch (e) { /* ignore */ }
  }

  /* ─── CSS shield ─────────────────────────────────────────────────── */
  function buildCSS() {
    var exploreRule = hideExplore()
      ? 'a[href^="/explore"] { display: none !important; }\n'
      : '';

    var strictRule = isStrictDmOnly()
      ? 'body > * > *:not([href^="/direct"]):not([aria-label="Messages" i]):not([aria-label="Notifications" i]) { display: none !important; }\n'
      : '';

    return (
      /* ── Reels nav / tab entries ──────────────────────────────────── */
      'a[href^="/reels"]                             { display: none !important; }\n' +
      'a[href*="/reels/"]                            { display: none !important; }\n' +
      exploreRule +

      /* ── Reels button / bloks components ────────────────────────── */
      '[role="button"][aria-label*="Reel" i]         { display: none !important; }\n' +
      '[data-bloks-name*="reel"]                     { display: none !important; }\n' +
      '[data-bloks-name*="clips"]                    { display: none !important; }\n' +

      /* ── Suggested headers and their parent cards ────────────────── */
      '[data-bloks-name*="suggested_header"]         { display: none !important; }\n' +
      'article:has([data-bloks-name*="suggested"])   { display: none !important; }\n' +

      /* ── Preserve DMs / Messages / Notifications / stories ───────── */
      'a[href^="/direct"]                            { display: unset !important; }\n' +
      '[aria-label="Messages" i]                     { display: unset !important; }\n' +
      '[aria-label="Notifications" i]                { display: unset !important; }\n' +
      /* stories tray rows */
      '[aria-label="Stories tray"]                   { display: unset !important; }\n' +
      '[data-bloks-name*="stories_tray"]             { display: unset !important; }\n' +

      strictRule
    );
  }

  function injectCSS() {
    var existing = document.getElementById(STYLE_ID);
    var css = buildCSS();
    if (existing) {
      /* Refresh content in case prefs changed */
      existing.textContent = css;
      return;
    }
    var el = document.createElement('style');
    el.id = STYLE_ID;
    el.textContent = css;
    (document.head || document.documentElement).appendChild(el);
  }

  /* ─── Text-node walk for "Suggested" headers ─────────────────────── */
  function hasSuggestedHeader(el) {
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null, false);
    var node;
    while ((node = walker.nextNode())) {
      var t = node.nodeValue && node.nodeValue.trim().toLowerCase();
      if (t === 'suggested for you' || t === 'suggested posts') return true;
    }
    return false;
  }

  /* ─── DOM sweep ──────────────────────────────────────────────────── */
  var _blocked = false;

  function sweepFeed() {
    var blocked = false;

    /* Article / presentation cards */
    var cards = document.querySelectorAll('article, [role="presentation"]');
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      if (card.style.display === 'none') continue;
      var hide = false;

      if (card.querySelector('a[href*="/reels/"]'))            hide = true;
      if (!hide && card.querySelector('[aria-label*="Reel" i]')) hide = true;
      if (!hide && card.querySelector('[aria-label*="Clips" i]')) hide = true;
      if (!hide && hasSuggestedHeader(card))                    hide = true;

      if (hide) {
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
        (hideExplore() && href.startsWith('/explore'))
      ) {
        link.style.display = 'none';
        blocked = true;
      }
    }

    /* Strict DM-only mode: hide everything except DM links */
    if (isStrictDmOnly()) {
      var allLinks = document.querySelectorAll('a');
      for (var k = 0; k < allLinks.length; k++) {
        var aEl = allLinks[k];
        var aHref = aEl.getAttribute('href') || '';
        if (!aHref.startsWith('/direct')) {
          aEl.style.display = 'none';
          blocked = true;
        }
      }
    }

    if (blocked && !_blocked) {
      _blocked = true;
      recordBlock();
    }
  }

  /* ─── Swipe guard ────────────────────────────────────────────────── */
  var _swipeGuardActive = false;
  var _touchStartY = 0;

  function onTouchStart(e) {
    _touchStartY = e.touches[0].clientY;
  }

  function onTouchMove(e) {
    var delta = Math.abs(e.touches[0].clientY - _touchStartY);
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

  /* ─── SPA navigation ─────────────────────────────────────────────── */
  var _navTimer = null;

  function onNavigate(url) {
    clearTimeout(_navTimer);
    _navTimer = setTimeout(function () {
      injectCSS();
      sweepFeed();

      var path = (typeof url === 'string') ? url.replace(/\?.*$/, '') : location.pathname;
      if (REEL_PATH.test(path)) {
        installSwipeGuard();
        removeQueuedReelSiblings();
      } else {
        removeSwipeGuard();
      }
    }, DEBOUNCE_MS);
  }

  function patchHistory() {
    var origPush    = history.pushState.bind(history);
    var origReplace = history.replaceState.bind(history);

    history.pushState = function (state, title, url) {
      origPush(state, title, url);
      onNavigate(typeof url === 'string' ? url : location.pathname);
    };

    history.replaceState = function (state, title, url) {
      origReplace(state, title, url);
      onNavigate(typeof url === 'string' ? url : location.pathname);
    };

    window.addEventListener('popstate', function () {
      onNavigate(location.pathname);
    });
  }

  /* ─── MutationObserver ───────────────────────────────────────────── */
  var _mutTimer = null;

  function startObserver() {
    var root = document.getElementById('root') || document.body;
    if (!root) return;

    var obs = new MutationObserver(function () {
      clearTimeout(_mutTimer);
      _mutTimer = setTimeout(function () {
        injectCSS();
        sweepFeed();
      }, DEBOUNCE_MS);
    });

    obs.observe(root, { childList: true, subtree: true });
  }

  /* ─── Boot ───────────────────────────────────────────────────────── */
  injectCSS();
  sweepFeed();
  patchHistory();
  startObserver();
  onNavigate(location.pathname);

})();

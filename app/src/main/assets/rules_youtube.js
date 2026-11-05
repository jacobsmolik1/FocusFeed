(function () {
  'use strict';

  /* ─── Guard: register observers/patches only once per page lifetime ─── */
  var _registered = !!window.__ff_yt_registered;
  window.__ff_yt_registered = true;

  /* ─── Constants ──────────────────────────────────────────────────────── */
  var STYLE_ID    = 'focusfeed-yt-css';
  var SHORTS_PATH = /^\/shorts\/([A-Za-z0-9_-]+)/;
  var DEBOUNCE_MS = 15;

  /* ─── Bridge helper ──────────────────────────────────────────────────── */
  function recordBlock() {
    try { if (window.FocusFeedBridge) FocusFeedBridge.recordBlock('youtube'); }
    catch (e) { /* bridge not ready yet */ }
  }

  /* ─── Shorts URL redirect ────────────────────────────────────────────── */
  function redirectIfShorts() {
    var match = location.pathname.match(SHORTS_PATH);
    if (match) window.location.replace('/watch?v=' + match[1]);
  }

  /* ─── CSS shield (no :has() — not safe to assume WebView version) ────── */
  var CSS = (
    /* Explicit Shorts containers */
    '#shorts-container                                       { display: none !important; }\n' +
    'ytd-shorts                                              { display: none !important; }\n' +
    'ytd-reel-shelf-renderer                                 { display: none !important; }\n' +
    'ytd-rich-shelf-renderer[is-shorts]                      { display: none !important; }\n' +
    'ytm-reel-shelf-renderer                                 { display: none !important; }\n' +
    /* Mobile pivot bar Shorts entry */
    'ytm-pivot-bar-item-renderer[tab-identifier="FEshorts"] { display: none !important; }\n' +
    /* Guide sidebar — matched by href, no :has() needed */
    'ytd-guide-entry-renderer a[href="/shorts"]              { display: none !important; }\n' +
    'ytd-mini-guide-entry-renderer a[href="/shorts"]         { display: none !important; }\n' +
    /* Shorts badge overlay — hiding the badge makes the item look normal;
       the JS sweep below hides the whole card */
    'ytd-thumbnail-overlay-time-status-renderer[overlay-style="SHORTS"] { display: none !important; }\n'
  );

  function injectCSS() {
    if (document.getElementById(STYLE_ID)) return;
    var el = document.createElement('style');
    el.id = STYLE_ID;
    el.textContent = CSS;
    (document.head || document.documentElement).appendChild(el);
  }

  /* ─── DOM sweep ──────────────────────────────────────────────────────── */
  function sweepShorts() {
    var blocked = false;

    /* 1. Hide entire cards that contain a Shorts overlay badge.
          This works without :has() by walking up from the badge itself. */
    document.querySelectorAll(
      'ytd-thumbnail-overlay-time-status-renderer[overlay-style="SHORTS"]'
    ).forEach(function (badge) {
      var card = badge.closest(
        'ytd-rich-item-renderer, ytd-grid-video-renderer, ' +
        'ytd-compact-video-renderer, ytd-video-renderer'
      );
      if (card && card.style.display !== 'none') {
        card.style.display = 'none';
        blocked = true;
      }
    });

    /* 2. Hide Shorts shelf sections — reel-shelf-renderer has no ambiguity */
    document.querySelectorAll(
      'ytd-reel-shelf-renderer, ytd-rich-shelf-renderer[is-shorts]'
    ).forEach(function (shelf) {
      var section = shelf.parentElement || shelf;
      if (section.tagName && section.tagName.toLowerCase() === 'ytd-rich-section-renderer') {
        if (section.style.display !== 'none') { section.style.display = 'none'; blocked = true; }
      } else {
        if (shelf.style.display !== 'none') { shelf.style.display = 'none'; blocked = true; }
      }
    });

    /* 3. Hide Shorts cards identified by /shorts/ in their href */
    document.querySelectorAll('a[href*="/shorts/"]').forEach(function (a) {
      var card = a.closest(
        'ytd-rich-item-renderer, ytd-grid-video-renderer, ' +
        'ytd-compact-video-renderer, ytd-video-renderer'
      );
      if (card && card.style.display !== 'none') {
        card.style.display = 'none';
        blocked = true;
      }
    });

    /* 4. aria-label sweep for nav containers (localization-resilient) */
    document.querySelectorAll('[aria-label]').forEach(function (el) {
      var label = (el.getAttribute('aria-label') || '').trim().toLowerCase();
      if (label === 'shorts' || label.indexOf('shorts ') === 0) {
        var nav = el.closest(
          'ytm-pivot-bar-item-renderer, ytd-guide-entry-renderer, ' +
          'ytd-mini-guide-entry-renderer, [role="tab"]'
        );
        var target = nav || el;
        if (target.style.display !== 'none') { target.style.display = 'none'; blocked = true; }
      }
    });

    /* 5. endpoint / page-type attribute sweep */
    document.querySelectorAll('[endpoint*="shorts"], [page-type="SHORTS"]').forEach(function (el) {
      var card = el.closest(
        'ytd-rich-item-renderer, ytm-pivot-bar-item-renderer, ytd-compact-video-renderer'
      );
      var target = card || el;
      if (target.style.display !== 'none') { target.style.display = 'none'; blocked = true; }
    });

    if (blocked) recordBlock();
  }

  /* ─── Click interception ─────────────────────────────────────────────── */
  function installClickGuard() {
    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (!a) return;
      var href = a.getAttribute('href') || '';
      if (href.indexOf('/shorts/') !== -1 || href === '/shorts') {
        e.preventDefault();
        e.stopImmediatePropagation();
      }
    }, true);
  }

  /* ─── Polling fallback ───────────────────────────────────────────────── */
  var _lastPolledPath = '';
  function startPolling() {
    setInterval(function () {
      var p = location.pathname;
      if (p !== _lastPolledPath) {
        _lastPolledPath = p;
        redirectIfShorts();
        injectCSS();
        sweepShorts();
      }
    }, 400);
  }

  /* ─── SPA navigation ─────────────────────────────────────────────────── */
  var _navTimer = null;
  function onNavigate() {
    clearTimeout(_navTimer);
    _navTimer = setTimeout(function () {
      redirectIfShorts();
      injectCSS();
      sweepShorts();
    }, DEBOUNCE_MS);
  }

  function patchHistory() {
    var origPush    = history.pushState.bind(history);
    var origReplace = history.replaceState.bind(history);
    history.pushState    = function (s, t, u) { origPush(s, t, u);    onNavigate(); };
    history.replaceState = function (s, t, u) { origReplace(s, t, u); onNavigate(); };
    window.addEventListener('popstate', onNavigate);
  }

  /* ─── MutationObserver ───────────────────────────────────────────────── */
  var _mutTimer = null;
  function startObserver() {
    var root = document.querySelector('ytd-app, ytm-app, body');
    if (!root) { setTimeout(startObserver, 100); return; }
    var obs = new MutationObserver(function () {
      clearTimeout(_mutTimer);
      _mutTimer = setTimeout(function () { injectCSS(); sweepShorts(); }, DEBOUNCE_MS);
    });
    obs.observe(root, { childList: true, subtree: true });
  }

  /* ─── Boot ───────────────────────────────────────────────────────────── */
  redirectIfShorts();
  injectCSS();
  sweepShorts();

  if (!_registered) {
    patchHistory();
    startObserver();
    installClickGuard();
    startPolling();
  }

})();

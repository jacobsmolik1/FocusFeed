(function () {
  'use strict';

  /* ─── Constants ─────────────────────────────────────────────────── */
  var STYLE_ID    = 'focusfeed-yt-css';
  var SHORTS_PATH = /^\/shorts\/([A-Za-z0-9_-]+)/;
  var DEBOUNCE_MS = 15;

  /* ─── Bridge helper ──────────────────────────────────────────────── */
  function recordBlock() {
    try {
      if (window.FocusFeedBridge) FocusFeedBridge.recordBlock('youtube');
    } catch (e) { /* ignore */ }
  }

  /* ─── Shorts URL redirect ────────────────────────────────────────── */
  function redirectIfShorts() {
    var match = location.pathname.match(SHORTS_PATH);
    if (match) {
      window.location.replace('/watch?v=' + match[1]);
    }
  }

  /* ─── CSS shield ─────────────────────────────────────────────────── */
  var CSS = (
    /* ── Full Shorts containers ──────────────────────────────────── */
    '#shorts-container                                                       { display: none !important; }\n' +
    'ytd-shorts                                                              { display: none !important; }\n' +
    'ytd-reel-shelf-renderer                                                 { display: none !important; }\n' +
    'ytd-rich-shelf-renderer[is-shorts]                                      { display: none !important; }\n' +

    /* ── Section renderers that contain Shorts shelves ───────────── */
    'ytd-rich-section-renderer:has(ytd-reel-shelf-renderer)                 { display: none !important; }\n' +
    'ytd-rich-section-renderer:has(ytd-reel-video-renderer)                 { display: none !important; }\n' +

    /* ── Mobile Shorts shelf ─────────────────────────────────────── */
    'ytm-reel-shelf-renderer                                                 { display: none !important; }\n' +

    /* ── Mobile pivot bar (bottom nav) Shorts entry ──────────────── */
    'ytm-pivot-bar-item-renderer:has(a[href*="shorts"])                     { display: none !important; }\n' +
    'ytm-pivot-bar-item-renderer > [aria-label*="Short" i]                  { display: none !important; }\n' +

    /* ── Shorts filter chip in search/explore ────────────────────── */
    'yt-chip-cloud-chip-renderer:has([aria-label*="Short" i])               { display: none !important; }\n' +

    /* ── Sidebar / mini-guide Shorts entries ─────────────────────── */
    'ytd-guide-entry-renderer:has(a[href="/shorts"])                        { display: none !important; }\n' +
    'ytd-mini-guide-entry-renderer:has(a[href="/shorts"])                   { display: none !important; }\n' +

    /* ── Individual Shorts video cards in all renderer types ─────── */
    'ytd-video-renderer:has(a[href*="/shorts/"])                            { display: none !important; }\n' +
    'ytd-compact-video-renderer:has(a[href*="/shorts/"])                    { display: none !important; }\n' +
    'ytd-grid-video-renderer:has(a[href*="/shorts/"])                       { display: none !important; }\n' +
    'ytd-rich-item-renderer:has(a[href*="/shorts/"])                        { display: none !important; }\n' +

    /* ── Shorts-labelled shelf layout ────────────────────────────── */
    'ytd-shelf-renderer[layout-type="shorts-shelf"]                         { display: none !important; }\n'
  );

  function injectCSS() {
    var existing = document.getElementById(STYLE_ID);
    if (existing) return; /* content is static; no need to refresh */
    var el = document.createElement('style');
    el.id = STYLE_ID;
    el.textContent = CSS;
    (document.head || document.documentElement).appendChild(el);
  }

  /* ─── DOM sweep ──────────────────────────────────────────────────── */
  function sweepShorts() {
    var blocked = false;

    /* 1. aria-label sweep: label === "shorts" or starts with "shorts " */
    var labeled = document.querySelectorAll('[aria-label]');
    for (var i = 0; i < labeled.length; i++) {
      var el = labeled[i];
      var label = (el.getAttribute('aria-label') || '').trim().toLowerCase();
      if (label === 'shorts' || label.indexOf('shorts ') === 0) {
        /* Walk up to the nearest nav container and hide it */
        var navContainer = el.closest(
          'ytm-pivot-bar-item-renderer, ytd-guide-entry-renderer, ' +
          'ytd-mini-guide-entry-renderer, nav, [role="navigation"]'
        );
        var target = navContainer || el;
        if (target.style.display !== 'none') {
          target.style.display = 'none';
          blocked = true;
        }
      }
    }

    /* 2. endpoint / page-type attribute sweep */
    var epEls = document.querySelectorAll('[endpoint*="shorts"], [page-type="SHORTS"]');
    for (var j = 0; j < epEls.length; j++) {
      var epEl = epEls[j];
      var card = epEl.closest(
        'ytd-rich-item-renderer, ytm-pivot-bar-item-renderer, ytd-compact-video-renderer'
      );
      var epTarget = card || epEl;
      if (epTarget.style.display !== 'none') {
        epTarget.style.display = 'none';
        blocked = true;
      }
    }

    if (blocked) recordBlock();
  }

  /* ─── SPA navigation ─────────────────────────────────────────────── */
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

    history.pushState = function (state, title, url) {
      origPush(state, title, url);
      onNavigate();
    };

    history.replaceState = function (state, title, url) {
      origReplace(state, title, url);
      onNavigate();
    };

    window.addEventListener('popstate', onNavigate);
  }

  /* ─── MutationObserver ───────────────────────────────────────────── */
  var _mutTimer = null;

  function startObserver() {
    var root = document.querySelector('ytd-app, ytm-app, body');
    if (!root) return;

    var obs = new MutationObserver(function () {
      clearTimeout(_mutTimer);
      _mutTimer = setTimeout(function () {
        injectCSS();
        sweepShorts();
      }, DEBOUNCE_MS);
    });

    obs.observe(root, { childList: true, subtree: true });
  }

  /* ─── Boot ───────────────────────────────────────────────────────── */
  redirectIfShorts();
  injectCSS();
  sweepShorts();
  patchHistory();
  startObserver();

})();

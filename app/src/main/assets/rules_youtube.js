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

  /* ─── CSS shield ─────────────────────────────────────────────────────── */
  var CSS = (
    '#shorts-container                                                       { display: none !important; }\n' +
    'ytd-shorts                                                              { display: none !important; }\n' +
    'ytd-reel-shelf-renderer                                                 { display: none !important; }\n' +
    'ytd-rich-shelf-renderer[is-shorts]                                      { display: none !important; }\n' +
    'ytd-rich-section-renderer:has(ytd-reel-shelf-renderer)                 { display: none !important; }\n' +
    'ytd-rich-section-renderer:has(ytd-reel-video-renderer)                 { display: none !important; }\n' +
    'ytm-reel-shelf-renderer                                                 { display: none !important; }\n' +
    'ytm-pivot-bar-item-renderer:has(a[href*="shorts"])                     { display: none !important; }\n' +
    'ytm-pivot-bar-item-renderer > [aria-label*="Short" i]                  { display: none !important; }\n' +
    'yt-chip-cloud-chip-renderer:has([aria-label*="Short" i])               { display: none !important; }\n' +
    'ytd-guide-entry-renderer:has(a[href="/shorts"])                        { display: none !important; }\n' +
    'ytd-mini-guide-entry-renderer:has(a[href="/shorts"])                   { display: none !important; }\n' +
    'ytd-video-renderer:has(a[href*="/shorts/"])                            { display: none !important; }\n' +
    'ytd-compact-video-renderer:has(a[href*="/shorts/"])                    { display: none !important; }\n' +
    'ytd-grid-video-renderer:has(a[href*="/shorts/"])                       { display: none !important; }\n' +
    'ytd-rich-item-renderer:has(a[href*="/shorts/"])                        { display: none !important; }\n' +
    'ytd-shelf-renderer[layout-type="shorts-shelf"]                         { display: none !important; }\n'
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

    var labeled = document.querySelectorAll('[aria-label]');
    for (var i = 0; i < labeled.length; i++) {
      var el = labeled[i];
      var label = (el.getAttribute('aria-label') || '').trim().toLowerCase();
      if (label === 'shorts' || label.indexOf('shorts ') === 0) {
        var navContainer = el.closest(
          'ytm-pivot-bar-item-renderer, ytd-guide-entry-renderer, ' +
          'ytd-mini-guide-entry-renderer, nav, [role="navigation"]'
        );
        var target = navContainer || el;
        if (target.style.display !== 'none') { target.style.display = 'none'; blocked = true; }
      }
    }

    var epEls = document.querySelectorAll('[endpoint*="shorts"], [page-type="SHORTS"]');
    for (var j = 0; j < epEls.length; j++) {
      var epEl = epEls[j];
      var card = epEl.closest(
        'ytd-rich-item-renderer, ytm-pivot-bar-item-renderer, ytd-compact-video-renderer'
      );
      var epTarget = card || epEl;
      if (epTarget.style.display !== 'none') { epTarget.style.display = 'none'; blocked = true; }
    }

    if (blocked) recordBlock();
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

  /* ─── History patches ────────────────────────────────────────────────── */
  function patchHistory() {
    var origPush    = history.pushState.bind(history);
    var origReplace = history.replaceState.bind(history);
    history.pushState = function (s, t, url) { origPush(s, t, url); onNavigate(); };
    history.replaceState = function (s, t, url) { origReplace(s, t, url); onNavigate(); };
    window.addEventListener('popstate', onNavigate);
  }

  /* ─── Click interception (block Shorts links before navigation fires) ── */
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

(function () {
  'use strict';

  /* Sign-in and the debug kill-switch get no observers and no CSS. */
  var __host = (location.hostname || '').toLowerCase();
  var __path = location.pathname || '';
  var __auth = __host.indexOf('accounts.') === 0 || __host.indexOf('consent.') === 0 ||
    __path.indexOf('/signin') === 0 || __path.indexOf('/account') === 0;
  var __off = false;
  try {
    __off = !!(window.FocusFeedBridge && FocusFeedBridge.isInjectionDisabled && FocusFeedBridge.isInjectionDisabled());
  } catch (e) { __off = false; }
  if (__auth || __off) {
    var __old = document.getElementById('focusfeed-yt-css');
    if (__old && __old.parentNode) __old.parentNode.removeChild(__old);
    return;
  }

  if (window.__ff_yt_tick) {
    try { window.__ff_yt_tick(); } catch (e) { /* keep the page up */ }
    return;
  }

  var STYLE_ID = 'focusfeed-yt-css';
  var SHORTS_PATH = /^\/shorts\/([A-Za-z0-9_-]+)/;
  var DEBOUNCE_MS = 15;
  var _navTimer = null;
  var _mutTimer = null;
  var _lastPolledPath = '';
  var _redirects = 0;

  function recordBlock() {
    try { if (window.FocusFeedBridge) FocusFeedBridge.recordBlock('youtube'); }
    catch (e) { /* bridge not ready yet */ }
  }

  function isShown(el) {
    if (!el || el.isConnected === false) return false;
    if (el.closest && el.closest('template, noscript, [hidden]')) return false;
    var laidOut = false;
    try {
      laidOut = (document.documentElement && document.documentElement.clientWidth > 0) ||
        (document.body && document.body.offsetWidth > 0);
    } catch (e) {}
    if (!laidOut) return true;
    try {
      if (!el.getClientRects || el.getClientRects().length === 0) return false;
      var style = window.getComputedStyle(el);
      if (style && (style.display === 'none' || style.visibility === 'hidden')) return false;
    } catch (e2) {}
    return true;
  }

  function isAuthSurface() {
    var host = (location.hostname || '').toLowerCase();
    var path = location.pathname || '';
    if (host.indexOf('accounts.') === 0 || host.indexOf('consent.') === 0) return true;
    if (path.indexOf('/signin') === 0) return true;
    var pass = document.querySelector('input[type="password"], input[name="Passwd"], input[name="password"]');
    if (!isShown(pass)) return false;
    var user = document.querySelector('input[type="email"], input[name="identifier"], input[autocomplete="username"]');
    return isShown(user);
  }

  function redirectIfShorts() {
    if (isAuthSurface()) return;
    var match = (location.pathname || '').match(SHORTS_PATH);
    if (!match) return;
    if (_redirects >= 4) return;
    _redirects++;
    try { window.location.replace('/watch?v=' + match[1]); } catch (e) { /* ignore */ }
  }

  /* No :has(). Explicit elements only — card removal is the JS sweep. */
  var CSS = (
    '#shorts-container                                       { display: none !important; }\n' +
    'ytd-shorts                                              { display: none !important; }\n' +
    'ytd-reel-shelf-renderer                                 { display: none !important; }\n' +
    'ytd-rich-shelf-renderer[is-shorts]                      { display: none !important; }\n' +
    'ytm-reel-shelf-renderer                                 { display: none !important; }\n' +
    'ytm-pivot-bar-item-renderer[tab-identifier="FEshorts"] { display: none !important; }\n' +
    'ytd-guide-entry-renderer a[href="/shorts"]              { display: none !important; }\n' +
    'ytd-mini-guide-entry-renderer a[href="/shorts"]         { display: none !important; }\n' +
    'ytd-thumbnail-overlay-time-status-renderer[overlay-style="SHORTS"] { display: none !important; }\n'
  );

  function injectCSS() {
    var existing = document.getElementById(STYLE_ID);
    if (existing) { existing.textContent = CSS; return; }
    var el = document.createElement('style');
    el.id = STYLE_ID;
    el.textContent = CSS;
    (document.head || document.documentElement).appendChild(el);
  }

  function clearCSS() {
    var existing = document.getElementById(STYLE_ID);
    if (existing) existing.textContent = '';
  }

  function coversViewport(el) {
    try {
      var rect = el.getBoundingClientRect();
      if (rect.width < 8 || rect.height < 8) return false;
      var w = window.innerWidth || document.documentElement.clientWidth || 0;
      var h = window.innerHeight || document.documentElement.clientHeight || 0;
      if (w < 8 || h < 8) return false;
      return rect.width >= w * 0.92 && rect.height >= h * 0.85;
    } catch (e) {
      return false;
    }
  }

  function isPageShell(el) {
    if (!el || el === document.body || el === document.documentElement) return true;
    var tag = (el.tagName || '').toUpperCase();
    if (tag === 'YTD-APP' || tag === 'YTM-APP' || tag === 'YTD-PAGE-MANAGER' || tag === 'YTD-BROWSE') return true;
    var id = el.id || '';
    if (id === 'content' || id === 'page-manager' || id === 'masthead-container') return true;
    if (coversViewport(el)) return true;
    return false;
  }

  function isKnownShortsTarget(el) {
    var tag = (el.tagName || '').toLowerCase();
    return tag === 'ytd-rich-item-renderer' ||
      tag === 'ytd-grid-video-renderer' ||
      tag === 'ytd-compact-video-renderer' ||
      tag === 'ytd-video-renderer' ||
      tag === 'ytd-reel-shelf-renderer' ||
      tag === 'ytd-rich-shelf-renderer' ||
      tag === 'ytd-rich-section-renderer' ||
      tag === 'ytm-reel-shelf-renderer' ||
      tag === 'ytm-pivot-bar-item-renderer' ||
      tag === 'ytm-video-with-context-renderer' ||
      tag === 'ytd-guide-entry-renderer' ||
      tag === 'ytd-mini-guide-entry-renderer' ||
      tag === 'a' ||
      tag === 'button';
  }

  function isSafeToHide(el) {
    if (!el || isPageShell(el)) return false;
    /* display:none on an unlaid-out unknown node can stick and blank the page.
       Known cards are safe to hide before the first layout. */
    if (!isKnownShortsTarget(el)) {
      try {
        var rect = el.getBoundingClientRect();
        if (rect.width < 8 || rect.height < 8) return false;
      } catch (e) {
        return false;
      }
    }
    return true;
  }

  function hideElement(el) {
    if (!isSafeToHide(el)) return false;
    if (el.getAttribute('data-ff-hidden')) return false;
    el.style.display = 'none';
    el.setAttribute('data-ff-hidden', 'shorts');
    return true;
  }

  function sweepShorts() {
    var blocked = false;

    var badges = document.querySelectorAll(
      'ytd-thumbnail-overlay-time-status-renderer[overlay-style="SHORTS"]'
    );
    for (var i = 0; i < badges.length; i++) {
      var card = badges[i].closest(
        'ytd-rich-item-renderer, ytd-grid-video-renderer, ytd-compact-video-renderer, ytd-video-renderer'
      );
      if (card && hideElement(card)) blocked = true;
    }

    var shelves = document.querySelectorAll(
      'ytd-reel-shelf-renderer, ytd-rich-shelf-renderer[is-shorts], ytm-reel-shelf-renderer'
    );
    for (var s = 0; s < shelves.length; s++) {
      var shelf = shelves[s];
      var parent = shelf.parentElement;
      var section = (parent && parent.tagName && parent.tagName.toLowerCase() === 'ytd-rich-section-renderer')
        ? parent
        : shelf;
      /* A mixed section also holds ordinary subscription videos — hide the shelf only. */
      if (section !== shelf && section.querySelector('ytd-rich-item-renderer a[href*="/watch"], ytd-grid-video-renderer a[href*="/watch"]')) {
        section = shelf;
      }
      if (hideElement(section)) blocked = true;
    }

    var links = document.querySelectorAll('a[href*="/shorts/"]');
    for (var a = 0; a < links.length; a++) {
      var linkCard = links[a].closest(
        'ytd-rich-item-renderer, ytd-grid-video-renderer, ytd-compact-video-renderer, ytd-video-renderer, ytm-video-with-context-renderer'
      );
      if (linkCard && hideElement(linkCard)) blocked = true;
    }

    var labelled = document.querySelectorAll('[aria-label]');
    for (var n = 0; n < labelled.length; n++) {
      var el = labelled[n];
      var label = (el.getAttribute('aria-label') || '').trim().toLowerCase();
      if (!(label === 'shorts' || label.indexOf('shorts ') === 0)) continue;
      var nav = el.closest(
        'ytm-pivot-bar-item-renderer, ytd-guide-entry-renderer, ytd-mini-guide-entry-renderer, [role="tab"]'
      );
      var target = nav || el;
      if (hideElement(target)) blocked = true;
    }

    var endpoints = document.querySelectorAll('[endpoint*="shorts"], [page-type="SHORTS"]');
    for (var e = 0; e < endpoints.length; e++) {
      var endpointCard = endpoints[e].closest(
        'ytd-rich-item-renderer, ytm-pivot-bar-item-renderer, ytd-compact-video-renderer, ytd-reel-shelf-renderer'
      );
      var endpointTarget = endpointCard || endpoints[e];
      if (hideElement(endpointTarget)) blocked = true;
    }

    if (blocked) recordBlock();
  }

  function installClickGuard() {
    document.addEventListener('click', function (e) {
      if (isAuthSurface()) return;
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (!a) return;
      var href = a.getAttribute('href') || '';
      if (href.indexOf('/shorts/') === -1 && href !== '/shorts' && href.indexOf('/shorts?') !== 0) return;
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
        tick();
      }
    }, 400);
  }

  function tick() {
    if (isAuthSurface()) {
      clearCSS();
      var hidden = document.querySelectorAll('[data-ff-hidden]');
      for (var i = 0; i < hidden.length; i++) {
        hidden[i].style.display = '';
        hidden[i].removeAttribute('data-ff-hidden');
      }
      return;
    }
    if ((location.pathname || '') === '/watch') _redirects = 0;
    redirectIfShorts();
    injectCSS();
    sweepShorts();
  }

  function onNavigate() {
    clearTimeout(_navTimer);
    _navTimer = setTimeout(tick, DEBOUNCE_MS);
  }

  function startObserver() {
    var root = document.querySelector('ytd-app, ytm-app') || document.body;
    if (!root) { setTimeout(startObserver, 100); return; }
    var obs = new MutationObserver(function () {
      clearTimeout(_mutTimer);
      _mutTimer = setTimeout(tick, DEBOUNCE_MS);
    });
    obs.observe(root, { childList: true, subtree: true });
  }

  /*
   * Do not wrap history.pushState. YouTube's SPA replaces it during boot;
   * yt-navigate-finish plus the mutation observer cover subscriptions updates.
   */
  window.addEventListener('yt-navigate-finish', onNavigate);
  window.addEventListener('yt-page-data-updated', onNavigate);
  window.addEventListener('popstate', onNavigate);
  startObserver();
  installClickGuard();
  startPolling();

  window.__ff_yt_tick = tick;
  tick();
})();
//# sourceURL=rules_youtube.js

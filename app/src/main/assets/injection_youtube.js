(function() {
    'use strict';

    const STYLE_ID = 'focusfeed-yt-style';

    const CSS = `
        /* Hide Shorts shelf on home page */
        ytd-rich-shelf-renderer[is-shorts],
        ytd-reel-shelf-renderer,
        ytd-rich-section-renderer:has(ytd-reel-shelf-renderer) {
            display: none !important;
        }

        /* Hide Shorts tab in nav */
        ytd-guide-entry-renderer a[href="/shorts"],
        ytd-mini-guide-entry-renderer a[href="/shorts"],
        tp-yt-paper-item[role="option"]:has(a[href="/shorts"]) {
            display: none !important;
        }

        /* Hide Shorts chips in search */
        yt-chip-cloud-chip-renderer:has([aria-label*="Short"]),
        yt-chip-cloud-chip-renderer:has([aria-label*="short"]) {
            display: none !important;
        }

        /* Hide individual Shorts in search results and recommendations */
        ytd-video-renderer:has(a[href*="/shorts/"]),
        ytd-compact-video-renderer:has(a[href*="/shorts/"]),
        ytd-grid-video-renderer:has(a[href*="/shorts/"]),
        ytd-rich-item-renderer:has(a[href*="/shorts/"]) {
            display: none !important;
        }

        /* Block /shorts/ page content */
        ytd-shorts,
        #shorts-container {
            display: none !important;
        }
    `;

    function injectStyle() {
        if (document.getElementById(STYLE_ID)) return;
        const s = document.createElement('style');
        s.id = STYLE_ID;
        s.textContent = CSS;
        (document.head || document.documentElement).appendChild(s);
    }

    function redirectIfShorts() {
        if (window.location.pathname.startsWith('/shorts')) {
            window.location.replace('/');
        }
    }

    injectStyle();
    redirectIfShorts();

    const observer = new MutationObserver(() => {
        injectStyle();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });

    const origPushState = history.pushState.bind(history);
    history.pushState = function(...args) {
        origPushState(...args);
        redirectIfShorts();
        setTimeout(injectStyle, 300);
    };
    window.addEventListener('popstate', () => {
        redirectIfShorts();
        setTimeout(injectStyle, 300);
    });
})();

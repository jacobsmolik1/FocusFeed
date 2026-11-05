(function() {
    'use strict';

    const STYLE_ID = 'focusfeed-ig-style';

    const CSS = `
        /* Hide Reels tab in bottom nav */
        [href="/reels/"],
        a[href*="/reels"],
        div[role="tablist"] a:has(svg[aria-label*="Reel"]),
        div[role="tablist"] a:has(svg[aria-label*="reel"]) {
            display: none !important;
        }

        /* Hide Reels row in Explore grid */
        [data-bloks-name*="reel"],
        [data-testid*="reel"] {
            display: none !important;
        }

        /* Hide Suggested Reels in home feed */
        article:has(video[playsinline]) {
            display: none !important;
        }

        /* Hide stories bar only if it's autoplay video stories */
        div[role="menu"] video {
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

    function hideReelsInFeed() {
        document.querySelectorAll('article').forEach(article => {
            if (article.querySelector('video[playsinline]') &&
                !article.querySelector('img[alt]')) {
                article.style.display = 'none';
            }
        });
    }

    injectStyle();
    hideReelsInFeed();

    const observer = new MutationObserver(() => {
        injectStyle();
        hideReelsInFeed();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });

    // Re-run on SPA navigation
    const origPushState = history.pushState.bind(history);
    history.pushState = function(...args) {
        origPushState(...args);
        setTimeout(() => { injectStyle(); hideReelsInFeed(); }, 300);
    };
    window.addEventListener('popstate', () => {
        setTimeout(() => { injectStyle(); hideReelsInFeed(); }, 300);
    });
})();

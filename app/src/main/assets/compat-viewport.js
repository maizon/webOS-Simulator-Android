/**
 * LG webOS TV layout compatibility. The WebView is letterboxed by the Android
 * host to 16:9; Chromium's own overview zoom fits a fixed CSS viewport width.
 * Do NOT add min-width/min-height to <html>: they cause horizontal scrolling.
 */
(function () {
  'use strict';

  var script = document.currentScript;
  var logicalWidth = script && script.getAttribute('data-tv-width') === '1280' ? 1280 : 1920;
  var logicalHeight = logicalWidth === 1280 ? 720 : 1080;

  function apply() {
    try {
      var metas = document.querySelectorAll('meta[name="viewport" i]');
      var meta = metas.length ? metas[0] : document.createElement('meta');
      var content = 'width=' + logicalWidth + ', minimum-scale=0.1, maximum-scale=5.0';
      // Avoid forced initial-scale=1, which often crops the 1920-wide page on phones.
      if (meta.getAttribute('content') !== content) meta.setAttribute('content', content);
      if (!metas.length) (document.head || document.documentElement).appendChild(meta);
      for (var i = 1; i < metas.length; i++) metas[i].parentNode.removeChild(metas[i]);
      if (document.documentElement.getAttribute('data-webos-simulator-viewport') !==
          logicalWidth + 'x' + logicalHeight) {
        document.documentElement.setAttribute('data-webos-simulator-viewport',
          logicalWidth + 'x' + logicalHeight);
      }
    } catch (err) {
      try { AndroidLuna.log('VIEWPORT', String(err)); } catch (ignored) {}
    }
  }

  apply();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', apply, {once: true});
  }
  // Apps occasionally replace <meta name=viewport> during boot. Restore the
  // logical TV width without repeatedly mutating unchanged DOM attributes.
  if (typeof MutationObserver !== 'undefined') {
    var observer = new MutationObserver(function () { apply(); });
    function observe() {
      var head = document.head;
      if (head) observer.observe(head, {
        childList: true, subtree: true, attributes: true,
        attributeFilter: ['name', 'content']
      });
    }
    if (document.head) observe();
    else document.addEventListener('DOMContentLoaded', observe, {once: true});
  }
})();

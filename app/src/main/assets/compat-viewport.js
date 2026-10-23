/* Optional logical TV viewport. Does not impersonate TV hardware or GPU. */
(function () {
  'use strict';
  function apply() {
    try {
      var metas = document.querySelectorAll('meta[name="viewport" i]');
      var meta = metas.length ? metas[0] : document.createElement('meta');
      meta.setAttribute('name', 'viewport');
      meta.setAttribute('content', 'width=1920, initial-scale=1.0, minimum-scale=0.2, maximum-scale=5.0');
      if (!metas.length) (document.head || document.documentElement).appendChild(meta);
      for (var i = 1; i < metas.length; i++) metas[i].parentNode.removeChild(metas[i]);
      document.documentElement.style.minWidth = '1920px';
      document.documentElement.style.minHeight = '1080px';
      document.documentElement.setAttribute('data-webos-simulator-viewport', '1920x1080');
    } catch (err) {
      try { AndroidLuna.log('VIEWPORT', String(err)); } catch (ignored) {}
    }
  }
  apply();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', apply, {once: true});
})();

/* webOS TV web-app compatibility shim.
 * Only conservative Web APIs / Luna mock responses: no DRM/native/firmware emulation.
 * Never fake WebGL, SharedArrayBuffer or WebAssembly capabilities.
 */
(function () {
  'use strict';
  if (window.__webOsAndroidShim) return;
  window.__webOsAndroidShim = true;
  var device = {};
  try { if (window.AndroidLuna) device = JSON.parse(AndroidLuna.getProfile()); } catch (e) {}
  var tv = device.screen || { width: 1920, height: 1080 };
  var info = {};
  try { if (window.AndroidLuna) info = JSON.parse(AndroidLuna.getAppInfo()); } catch (e) {}

  function log(level, message) {
    try { if (window.AndroidLuna) AndroidLuna.log(String(level), String(message)); } catch (e) {}
  }
  function copy(obj) {
    try { return JSON.parse(JSON.stringify(obj || {})); } catch (e) { return {}; }
  }
  function callback(fn, data) {
    if (typeof fn !== 'function') return;
    setTimeout(function () {
      try { fn(data); } catch (e) { log('JS_CALLBACK', String(e)); }
    }, 0);
  }
  function perform(uri, params, ok, fail) {
    var response;
    try {
      if (!window.AndroidLuna) throw Error('Android bridge unavailable');
      response = JSON.parse(AndroidLuna.request(String(uri), JSON.stringify(params || {})));
    } catch (e) {
      response = {returnValue: false, errorCode: -1,
        errorText: String(e), __simulator: 'ERROR'};
    }
    callback(response.returnValue === false ? fail : ok, response);
    return response;
  }
  function joinUri(service, method) {
    var base = String(service || '').replace(/\/+$/, '');
    return method ? base + '/' + String(method).replace(/^\/+/, '') : base;
  }

  var webOS = window.webOS || {};
  if (!webOS.platform) webOS.platform = {
    tv: true,
    version: device.webOSVersion || '7.0',
    modelName: device.modelName || 'UQ8050PSB'
  };
  webOS.deviceInfo = webOS.deviceInfo || copy(device);
  webOS.service = webOS.service || {};
  if (typeof webOS.service.request !== 'function') {
    webOS.service.request = function (service, options) {
      var opts = options || {}, cancelled = false;
      var uri = joinUri(service, opts.method);
      perform(uri, opts.parameters || {}, function (value) {
        if (!cancelled) callback(opts.onSuccess, value);
      }, function (value) {
        if (!cancelled) callback(opts.onFailure, value);
      });
      if (opts.subscribe) log('LUNA_MOCK', 'Live subscription unsupported: ' + uri);
      return {cancel: function () { cancelled = true; }};
    };
  }
  if (typeof webOS.service.Request !== 'function') {
    webOS.service.Request = function (uri, opts) {
      return webOS.service.request(uri, opts);
    };
  }
  if (typeof webOS.fetchAppInfo !== 'function') {
    webOS.fetchAppInfo = function (cb) {
      callback(cb, copy(info));
      return copy(info);
    };
  }
  if (typeof webOS.getAppInfo !== 'function') {
    webOS.getAppInfo = function () { return copy(info); };
  }
  if (typeof webOS.platformBack !== 'function') {
    webOS.platformBack = function () {
      window.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Escape', code: 'Escape', bubbles: true, cancelable: true
      }));
    };
  }
  if (!webOS.keyboard) webOS.keyboard = {
    isShowing: function () { return false; },
    show: function () { log('UNSUPPORTED', 'webOS keyboard.show'); },
    hide: function () { log('UNSUPPORTED', 'webOS keyboard.hide'); }
  };
  window.webOS = webOS;

  var system = window.webOSSystem || {};
  if (!system.deviceInfo) system.deviceInfo = JSON.stringify({
    modelName: device.modelName || 'UQ8050PSB',
    platformVersion: device.platformVersion || 'webOS TV 22',
    version: device.webOSVersion || '7.0',
    screenWidth: tv.width || 1920,
    screenHeight: tv.height || 1080,
    __simulator: 'MOCK'
  });
  if (typeof system.getDeviceInfo !== 'function') {
    system.getDeviceInfo = function () {
      try { return JSON.parse(system.deviceInfo); } catch (e) { return {}; }
    };
  }
  if (typeof system.platformBack !== 'function') system.platformBack = webOS.platformBack;
  if (typeof system.close !== 'function') system.close = function () {
    log('UNSUPPORTED', 'webOSSystem.close does not terminate Android host');
  };
  if (typeof system.launchParams !== 'string') system.launchParams = '{}';
  if (!system.identifier) system.identifier = device.profileId || 'lg-uq8050psb-webos22';
  window.webOSSystem = system;

  if (typeof window.PalmServiceBridge !== 'function') {
    function PalmServiceBridge() {
      this.onservicecallback = null;
      this._cancelled = false;
    }
    PalmServiceBridge.prototype.call = function (uri, paramText) {
      var self = this, params = {};
      try { params = JSON.parse(paramText || '{}'); }
      catch (e) { log('WARN', 'Invalid Luna request JSON'); }
      var result;
      try { result = JSON.parse(AndroidLuna.request(String(uri), JSON.stringify(params))); }
      catch (e) { result = {returnValue:false, errorText:String(e), __simulator:'ERROR'}; }
      callback(function () {
        if (!self._cancelled && typeof self.onservicecallback === 'function') {
          self.onservicecallback(JSON.stringify(result));
        }
      });
    };
    PalmServiceBridge.prototype.cancel = function () { this._cancelled = true; };
    window.PalmServiceBridge = PalmServiceBridge;
  }

  // Reflect the actual browser, not a pretend firmware implementation.
  window.addEventListener('error', function (e) {
    log('JS_ERROR', (e.message || 'Error') + ' ' + (e.filename || '') + ':' + (e.lineno || 0));
  });
  window.addEventListener('unhandledrejection', function (e) {
    log('JS_REJECTION', String(e.reason));
  });
  window.addEventListener('focus', function () { log('APP', 'window focus'); });
  window.addEventListener('blur', function () { log('APP', 'window blur'); });
  log('INFO', 'webOS API shim ready; model=' + (device.modelName || 'unknown') +
      '; WASM=' + (typeof WebAssembly) + '; isolated=' + String(window.crossOriginIsolated));
})();

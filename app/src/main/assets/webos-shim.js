/* LG webOS TV compatibility shim: Android-backed where possible; mocks are explicitly labeled. */
(function () {
  'use strict';
  if (window.__webOsAndroidShim) return;
  window.__webOsAndroidShim = true;

  function profile() {
    try { return JSON.parse(AndroidLuna.getProfile()); }
    catch (e) { return { modelName: 'UQ8050PSB', platformVersion: 'webOS TV 22', screen: {width: 1920, height: 1080} }; }
  }
  var device = profile();
  var report = function (level, text) {
    try { AndroidLuna.log(String(level), String(text)); } catch (e) { /* testing outside emulator */ }
  };
  function callNative(uri, params, onSuccess, onFailure) {
    var result;
    try { result = JSON.parse(AndroidLuna.request(uri, JSON.stringify(params || {}))); }
    catch (e) { result = {returnValue: false, errorText: String(e), errorCode: -1, __simulator: 'ERROR'}; }
    setTimeout(function () {
      try {
        if (result.returnValue === false) {
          if (typeof onFailure === 'function') onFailure(result);
        } else if (typeof onSuccess === 'function') {
          onSuccess(result);
        }
      } catch (err) { report('ERROR', 'Luna callback: ' + err); }
    }, 0);
    return result;
  }
  function fullUri(service, method) {
    return String(service || '').replace(/\/$/, '') + (method ? '/' + String(method).replace(/^\//, '') : '');
  }

  var webOS = window.webOS || {};
  webOS.platform = webOS.platform || {
    tv: true,
    version: device.webOSVersion || '7.0',
    modelName: device.modelName || 'UQ8050PSB'
  };
  webOS.deviceInfo = device;
  webOS.service = webOS.service || {};
  webOS.service.request = function (uri, opts) {
    opts = opts || {};
    var cancelled = false;
    var joined = fullUri(uri, opts.method);
    callNative(joined, opts.parameters || {}, function (value) {
      if (!cancelled && typeof opts.onSuccess === 'function') opts.onSuccess(value);
    }, function (value) {
      if (!cancelled && typeof opts.onFailure === 'function') opts.onFailure(value);
    });
    if (opts.subscribe) report('MOCK', 'Subscription not live: ' + joined);
    return {cancel: function () { cancelled = true; }};
  };
  webOS.service.Request = function (uri, opts) { return webOS.service.request(uri, opts); };
  webOS.fetchAppInfo = function (callback) {
    var info = {};
    try { info = JSON.parse(AndroidLuna.getAppInfo()); } catch (e) {}
    if (typeof callback === 'function') setTimeout(function () { callback(info); }, 0);
    return info;
  };
  webOS.keyboard = webOS.keyboard || { isShowing: function () { return false; } };
  window.webOS = webOS;

  var system = window.webOSSystem || {};
  system.deviceInfo = JSON.stringify({
    modelName: device.modelName || 'UQ8050PSB',
    platformVersion: device.platformVersion || 'webOS TV 22',
    version: device.webOSVersion || '7.0',
    screenWidth: device.screen && device.screen.width || 1920,
    screenHeight: device.screen && device.screen.height || 1080,
    __simulator: 'MOCK'
  });
  system.getDeviceInfo = function () { return JSON.parse(system.deviceInfo); };
  system.platformBack = function () {
    window.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', code: 'Escape', bubbles: true}));
  };
  system.close = function () { report('MOCK', 'webOSSystem.close requested'); };
  system.launchParams = '{}';
  system.identifier = device.profileId || 'lg-uq8050psb-webos22';
  window.webOSSystem = system;

  function PalmServiceBridge() { this.onservicecallback = null; this.__closed = false; }
  PalmServiceBridge.prototype.call = function (uri, paramText) {
    var self = this, params = {};
    try { params = JSON.parse(paramText || '{}'); } catch (e) { report('WARN', 'Invalid Luna JSON'); }
    callNative(uri, params, function (result) {
      if (!self.__closed && typeof self.onservicecallback === 'function')
        self.onservicecallback(JSON.stringify(result));
    }, function (result) {
      if (!self.__closed && typeof self.onservicecallback === 'function')
        self.onservicecallback(JSON.stringify(result));
    });
  };
  PalmServiceBridge.prototype.cancel = function () { this.__closed = true; };
  window.PalmServiceBridge = window.PalmServiceBridge || PalmServiceBridge;

  window.addEventListener('error', function (event) {
    report('JS_ERROR', event.message + ' at ' + (event.filename || '') + ':' + event.lineno);
  });
  window.addEventListener('unhandledrejection', function (event) {
    report('JS_REJECTION', String(event.reason));
  });
  report('INFO', 'webOS shim loaded; profile=' + device.profileId +
    '; isolated=' + String(window.crossOriginIsolated) + '; wasm=' + (typeof WebAssembly) +
    '; SAB=' + (typeof SharedArrayBuffer));
})();

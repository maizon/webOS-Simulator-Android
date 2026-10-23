/* webOS TV 22 public app-level API compatibility, experimental.
 * Implementations are Android-backed or transparent simulations, NEVER LG firmware.
 * Real TV DRM, hardware keys, accounts, services and sensors are unavailable.
 */
(function () {
  'use strict';
  if (window.__webOsAndroidShim) return;
  window.__webOsAndroidShim = true;

  function decode(text, fallback) {
    try { return JSON.parse(text); } catch (e) { return fallback; }
  }
  var device = decode(window.AndroidLuna && AndroidLuna.getProfile(), {});
  var appInfo = decode(window.AndroidLuna && AndroidLuna.getAppInfo(), {});
  var appId = appInfo.id || '';
  var screenInfo = device.screen || {width: 1920, height: 1080};
  var REQUEST_LIMIT = 48, liveCount = 0, active = [];
  var launches = {};
  function log(level, message) {
    try { if (window.AndroidLuna) AndroidLuna.log(String(level), String(message).slice(0, 400)); } catch (ignored) {}
  }
  function copy(value) {
    return decode(JSON.stringify(value || {}), {});
  }
  function later(fn, arg) {
    if (typeof fn !== 'function') return;
    setTimeout(function () {
      try { fn(arg); } catch (error) { log('LUNA_CALLBACK', String(error)); }
    }, 0);
  }
  function uriOf(service, method) {
    var base = String(service || '').replace(/\/+$/, '');
    if (base.slice(0, 7) !== 'luna://') base = 'luna://' + base.replace(/^\/+/, '');
    if (method) base += '/' + String(method).replace(/^\/+/, '');
    return base;
  }
  function bridgeRequest(uri, parameters) {
    try {
      if (!window.AndroidLuna) throw Error('Android bridge unavailable');
      var result = decode(AndroidLuna.request(String(uri), JSON.stringify(parameters || {})), null);
      if (!result || typeof result.returnValue !== 'boolean') throw Error('Malformed Luna response');
      if (result.returnValue === false) log('UNSUPPORTED_LUNA', uri + ': ' + (result.errorText || 'failure'));
      return result;
    } catch (error) {
      return {returnValue: false, errorCode: -1, errorText: String(error), __simulator: 'ERROR'};
    }
  }

  // Both webOSTV.js and older webOS.js accept service/method/parameters.
  function serviceRequest(service, options) {
    var opts = options || {};
    var parameters = copy(opts.parameters || opts.params || {});
    var wantsSubscription = Boolean(opts.subscribe || parameters.subscribe);
    if (wantsSubscription) parameters.subscribe = true;
    var uri = uriOf(service, opts.method);
    var cancelled = false, timer = null, previous = null, slotAllocated = false;
    function emit(response) {
      if (cancelled) return;
      later(function (value) {
        if (cancelled) return;
        if (value.returnValue === false) {
          if (typeof opts.onFailure === 'function') opts.onFailure(value);
        } else if (typeof opts.onSuccess === 'function') opts.onSuccess(value);
        if (typeof opts.onComplete === 'function') opts.onComplete(value);
      }, response);
    }
    function poll(initial) {
      if (cancelled) return;
      var response = bridgeRequest(uri, parameters);
      if (initial || JSON.stringify(response) !== previous) {
        previous = JSON.stringify(response);
        emit(response);
      }
      if (response.returnValue === false || response.subscribed !== true) {
        clearInterval(timer); timer = null;
        if (slotAllocated && liveCount > 0) { liveCount--; slotAllocated = false; }
        return;
      }
    }
    function cancel() {
      if (cancelled) return;
      cancelled = true;
      handle.__cancelled = true;
      if (timer) clearInterval(timer);
      if (slotAllocated && liveCount > 0) { liveCount--; slotAllocated = false; }
    }
    var handle = {send: function () {
      if (!cancelled) poll(true);
      return handle;
    }, cancel: cancel};
    active.push(handle);
    // Remove canceled handles periodically: bound retained objects for long-running apps.
    if (active.length > 64) active = active.filter(function (h) { return !h.__cancelled; }).slice(-64);
    poll(true);
    if (wantsSubscription && previous) {
      var first = decode(previous, {});
      if (first.returnValue === true && first.subscribed === true) {
        if (liveCount < REQUEST_LIMIT) {
          liveCount++; slotAllocated = true;
          // Poll only supported subscriptions. Updates are sent only on changes.
          timer = setInterval(function () { poll(false); }, 30000);
        } else {
          log('LUNA_LIMIT', 'Subscription limit reached; initial snapshot only: ' + uri);
        }
      }
    }
    return handle;
  }

  var webOS = window.webOS || {};
  if (!webOS.platform) webOS.platform = {tv: true};
  if (!webOS.libVersion) webOS.libVersion = '1.2.4-simulated';
  if (!webOS.service) webOS.service = {};
  if (typeof webOS.service.request !== 'function') webOS.service.request = serviceRequest;
  if (typeof webOS.service.Request !== 'function') {
    webOS.service.Request = function (uri, options) {
      return webOS.service.request(uri, options);
    };
  }
  if (typeof webOS.fetchAppId !== 'function') webOS.fetchAppId = function () { return appId; };
  if (typeof webOS.fetchAppRootPath !== 'function') webOS.fetchAppRootPath = function () {
    return location.origin + '/apps/' + encodeURIComponent(appId) + '/';
  };
  if (typeof webOS.fetchAppInfo !== 'function') webOS.fetchAppInfo = function (fn) {
    webOS.appInfo = copy(appInfo);
    later(fn, copy(appInfo));
  };
  if (typeof webOS.getAppInfo !== 'function') webOS.getAppInfo = function () { return copy(appInfo); };
  if (!webOS.appInfo) webOS.appInfo = copy(appInfo);

  // Official webOSTV.js deviceInfo is a FUNCTION with a callback, not a plain object.
  var simulatedDevice = {
    modelName: device.modelName || 'UQ8050PSB',
    version: device.webOSVersion || '7.0.0',
    versionMajor: 7, versionMinor: 0, versionDot: 0,
    sdkVersion: device.webOSVersion || '7.0',
    manufacturer: 'LG Electronics',
    screenWidth: screenInfo.width || 1920,
    screenHeight: screenInfo.height || 1080,
    resolution: (screenInfo.width || 1920) + 'x' + (screenInfo.height || 1080),
    platformVersion: device.platformVersion || 'webOS TV 22',
    __simulator: 'TV_PROFILE'
  };
  if (typeof webOS.deviceInfo !== 'function') {
    webOS.deviceInfo = function (fn) { later(fn, copy(simulatedDevice)); };
  }
  // webOS.systemInfo is a property, not a service request function.
  if (!webOS.systemInfo) {
    webOS.systemInfo = {
      country: 'BRA', smartServiceCountry: 'BRA',
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
    };
  }
  if (typeof webOS.platformBack !== 'function') {
    webOS.platformBack = function () {
      document.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true, cancelable: true
      }));
    };
  }
  if (!webOS.keyboard) webOS.keyboard = {};
  if (typeof webOS.keyboard.isShowing !== 'function') {
    webOS.keyboard.isShowing = function () {
      var el = document.activeElement;
      return !!el && (/^(INPUT|TEXTAREA)$/.test(el.tagName) || el.isContentEditable);
    };
  }
  if (typeof webOS.keyboard.show !== 'function') webOS.keyboard.show = function () {
    log('UNSUPPORTED', 'webOS.keyboard.show requires LG virtual keyboard');
  };
  if (typeof webOS.keyboard.hide !== 'function') webOS.keyboard.hide = function () {
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
  };
  window.webOS = webOS;

  var system = window.webOSSystem || {};
  if (!system.deviceInfo) system.deviceInfo = JSON.stringify(simulatedDevice);
  if (typeof system.getDeviceInfo !== 'function') {
    system.getDeviceInfo = function () { return copy(simulatedDevice); };
  }
  if (typeof system.platformBack !== 'function') system.platformBack = webOS.platformBack;
  if (typeof system.close !== 'function') system.close = function () {
    log('UNSUPPORTED', 'webOSSystem.close does not terminate Android host');
  };
  if (typeof system.launchParams !== 'string') system.launchParams = '{}';
  if (!system.identifier) system.identifier = appId || device.profileId || 'webos-ipk';
  if (!system.locale) system.locale = navigator.language || 'pt-BR';
  window.webOSSystem = system;
  if (!window.PalmSystem) window.PalmSystem = system;

  // The modern webOSDev facade exposes only nonprivileged functions.
  var dev = window.webOSDev || {};
  if (!dev.APP) dev.APP = {BROWSER: 'com.webos.app.browser'};
  if (typeof dev.launchParams !== 'function') {
    dev.launchParams = function () { return decode(system.launchParams, {}); };
  }
  if (typeof dev.launch !== 'function') {
    dev.launch = function (options) {
      var opts = options || {};
      return webOS.service.request('luna://com.webos.applicationManager', {
        method: 'launch', parameters: {id: opts.id, params: opts.params || {}},
        onSuccess: opts.onSuccess, onFailure: opts.onFailure
      });
    };
  }
  if (!dev.connection) dev.connection = {};
  if (typeof dev.connection.getStatus !== 'function') {
    dev.connection.getStatus = function (options) {
      var opts = options || {};
      return webOS.service.request('luna://com.palm.connectionmanager', {
        method: 'getStatus',
        parameters: {subscribe: !!opts.subscribe},
        subscribe: !!opts.subscribe,
        onSuccess: opts.onSuccess, onFailure: opts.onFailure
      });
    };
  }
  if (typeof dev.LGUDID !== 'function') {
    dev.LGUDID = function (options) {
      later(options && options.onFailure,
        {returnValue: false, errorCode: -1,
          errorText: 'LG unique device ID unavailable in Android simulator',
          __simulator: 'UNSUPPORTED'});
    };
  }
  if (typeof dev.drmAgent !== 'function') {
    dev.drmAgent = function () {
      log('UNSUPPORTED', 'DRM agent requires proprietary LG hardware and rights');
      return null;
    };
  }
  window.webOSDev = dev;

  if (typeof window.PalmServiceBridge !== 'function') {
    function PalmServiceBridge() {
      this.onservicecallback = null;
      this._request = null;
    }
    PalmServiceBridge.prototype.call = function (uri, text) {
      if (this._request) this._request.cancel();
      var self = this, params = decode(text || '{}', {});
      this._request = serviceRequest(uri, {
        parameters: params,
        subscribe: params.subscribe === true,
        onComplete: function (response) {
          if (typeof self.onservicecallback === 'function') {
            try { self.onservicecallback(JSON.stringify(response)); }
            catch (error) { log('BRIDGE_CALLBACK', String(error)); }
          }
        }
      });
    };
    PalmServiceBridge.prototype.cancel = function () {
      if (this._request) this._request.cancel();
      this._request = null;
    };
    window.PalmServiceBridge = PalmServiceBridge;
  }
  window.__webOSSimulatorRelaunch = function (params) {
    // Called only from the current app's Android bridge after self-launch.
    launches = copy(params);
    system.launchParams = JSON.stringify(launches);
    document.dispatchEvent(new CustomEvent('webOSRelaunch', {detail: copy(launches)}));
  };
  window.addEventListener('pagehide', function () {
    active.forEach(function (request) { request.cancel(); });
    active = [];
  });
  window.addEventListener('error', function (event) {
    if (event.target !== window) return;
    log('JS_ERROR', (event.message || 'Error') + ' ' + (event.filename || '') + ':' + (event.lineno || 0));
  });
  window.addEventListener('unhandledrejection', function (event) {
    log('JS_REJECTION', String(event.reason));
  });
  log('INFO', 'webOS 22 Luna facade ready: ' + (device.modelName || 'unknown') +
    '; WASM=' + typeof WebAssembly + '; isolated=' + String(window.crossOriginIsolated));
})();

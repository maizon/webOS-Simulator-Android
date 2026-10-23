/* Passive diagnostics. Does not override canvas, WebGL, WASM, rAF or fetch implementations. */
(function () {
  'use strict';
  if (window.WebOSGraphicsDoctor) return;
  var errors = [], limit = 100, losses = 0, lastProbes = null;
  var frames = 0, lastFrameTime = 0, lastFPS = 0, rafStarted = false;
  var started = Date.now();
  var pendingResources = 0;
  function log(kind, message) {
    errors.push({ms: Date.now() - started, kind: kind, message: String(message).slice(0, 300)});
    if (errors.length > limit) errors.shift();
    try { AndroidLuna.log('GRAPHICS', kind + ' ' + String(message).slice(0, 260)); } catch (e) {}
  }
  function frame(now) {
    frames++;
    if (!lastFrameTime) lastFrameTime = now;
    var elapsed = now - lastFrameTime;
    if (elapsed >= 1500) {
      lastFPS = Math.round(frames * 10000 / elapsed) / 10;
      frames = 0; lastFrameTime = 0; rafStarted = false;
      return;
    }
    requestAnimationFrame(frame);
  }
  function begin() {
    if (!rafStarted && typeof requestAnimationFrame === 'function') {
      frames = 0; lastFrameTime = 0; rafStarted = true; requestAnimationFrame(frame);
    }
  }
  function probe(kind) {
    var c = document.createElement('canvas');
    c.width = 4; c.height = 4;
    try {
      var ctx = c.getContext(kind, {preserveDrawingBuffer: true});
      if (!ctx) return {available: false};
      if (kind === '2d') {
        ctx.fillStyle = '#f00'; ctx.fillRect(0, 0, 4, 4);
        var pix = ctx.getImageData(1, 1, 1, 1).data;
        return {available: true, redPixel: pix[0] === 255 && pix[1] === 0};
      }
      ctx.clearColor(0, 1, 0, 1);
      ctx.clear(ctx.COLOR_BUFFER_BIT);
      var px = new Uint8Array(4);
      ctx.readPixels(0, 0, 1, 1, ctx.RGBA, ctx.UNSIGNED_BYTE, px);
      var result = {
        available: true, version: ctx.getParameter(ctx.VERSION),
        vendor: ctx.getParameter(ctx.VENDOR),
        renderer: ctx.getParameter(ctx.RENDERER),
        maxTextureSize: ctx.getParameter(ctx.MAX_TEXTURE_SIZE),
        clearPixel: Array.from(px), glError: ctx.getError()
      };
      var lose = ctx.getExtension('WEBGL_lose_context');
      if (lose) lose.loseContext();
      return result;
    } catch (e) { return {available: false, error: String(e)}; }
  }
  function visibleCanvases() {
    return Array.prototype.slice.call(document.querySelectorAll('canvas')).map(function (c, index) {
      var rect = c.getBoundingClientRect(), cs = getComputedStyle(c);
      var visible = c.width > 0 && c.height > 0 && rect.width > 0 && rect.height > 0 &&
        cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0 &&
        rect.bottom > 0 && rect.top < innerHeight && rect.right > 0 && rect.left < innerWidth;
      return {index: index, width: c.width, height: c.height,
        screen: [Math.round(rect.left), Math.round(rect.top), Math.round(rect.width), Math.round(rect.height)],
        display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
        visible: visible};
    });
  }
  function resources() {
    if (!performance || typeof performance.getEntriesByType !== 'function') return {};
    var entries = performance.getEntriesByType('resource') || [];
    var data = {count: entries.length, slow: [], wasm: [], failedOrZero: []};
    entries.slice(-1000).forEach(function (r) {
      var item = {url: String(r.name).slice(0, 150), ms: Math.round(r.duration),
        bytes: r.transferSize || 0};
      if (/\.wasm(?:\?|$)/i.test(r.name)) data.wasm.push(item);
      if (r.duration > 2000 && data.slow.length < 10) data.slow.push(item);
      if (r.duration === 0 && r.transferSize === 0 && data.failedOrZero.length < 10)
        data.failedOrZero.push(item);
    });
    return data;
  }
  function report() {
    begin(); // Diagnostic sampling is on-demand, never a permanent game-time loop.
    var canvases = visibleCanvases();
    if (!lastProbes) lastProbes = {canvas2d: probe('2d'), webgl1: probe('webgl'), webgl2: probe('webgl2')};
    var wasm = {available: typeof WebAssembly !== 'undefined', threads: typeof SharedArrayBuffer !== 'undefined'};
    if (wasm.available) {
      try { wasm.valid = WebAssembly.validate(new Uint8Array([0,97,115,109,1,0,0,0])); } catch (e) { wasm.valid = false; }
    }
    var warnings = [];
    if (document.readyState !== 'complete') warnings.push('Document not completely loaded');
    if (!canvases.length) warnings.push('No canvas found: verify script startup and asset requests');
    if (canvases.length && !canvases.some(function (c) { return c.visible; }))
      warnings.push('All canvases are invisible, outside viewport or have zero size');
    if (losses) warnings.push('WebGL context loss recorded');
    if (!lastProbes.webgl1.available) warnings.push('WebGL 1 probe unavailable');
    if (!lastProbes.webgl2.available) warnings.push('WebGL 2 probe unavailable');
    if (!wasm.threads) warnings.push('SharedArrayBuffer unavailable: multithreaded WASM may fail');
    if (errors.some(function (e) { return e.kind === 'JS_ERROR' || e.kind === 'RESOURCE'; }))
      warnings.push('JavaScript/resource errors captured');
    return {
      app: location.href, timestamp: new Date().toISOString(), readyState: document.readyState,
      viewport: {width: innerWidth, height: innerHeight, screenWidth: screen.width,
        screenHeight: screen.height, dpr: devicePixelRatio,
        logicalTV: document.documentElement.getAttribute('data-webos-simulator-viewport') || 'off'},
      webviewUserAgent: navigator.userAgent, crossOriginIsolated: window.crossOriginIsolated === true,
      wasm: wasm, workers: typeof Worker !== 'undefined',
      offscreenCanvas: typeof OffscreenCanvas !== 'undefined',
      media: {audioContext: !!(window.AudioContext || window.webkitAudioContext),
        mediaSource: !!window.MediaSource,
        webRTC: !!(navigator.mediaDevices || window.RTCPeerConnection)},
      frameSchedulerFPS: lastFPS,
      frameSchedulerNote: 'rAF callback throughput, NOT measured game/render FPS',
      canvasCount: canvases.length, canvases: canvases.slice(0, 30),
      lostContexts: losses, errors: errors.slice(-30),
      resources: resources(), probes: lastProbes, warnings: warnings
    };
  }
  document.addEventListener('webglcontextlost', function (e) {
    losses++; log('WEBGL_LOST', (e.target && e.target.tagName) || 'unknown');
  }, true);
  document.addEventListener('webglcontextrestored', function () { log('WEBGL_RESTORED', 'restored'); }, true);
  window.addEventListener('error', function (e) {
    if (e.target && e.target !== window && e.target.src) {
      pendingResources++;
      log('RESOURCE', e.target.tagName + ': ' + String(e.target.src).slice(0, 200));
    } else log('JS_ERROR', e.message || 'Unknown JS error');
  }, true);
  window.addEventListener('unhandledrejection', function (e) { log('PROMISE', String(e.reason)); });
  document.addEventListener('DOMContentLoaded', function () { log('LIFECYCLE', 'DOMContentLoaded'); });
  window.addEventListener('load', function () { log('LIFECYCLE', 'load'); });
  window.WebOSGraphicsDoctor = {
    scan: report,
    resetProbes: function () { lastProbes = null; },
    log: log,
    version: '1.0'
  };
})();

/* Passive graphics diagnostics: never alters WebGL/Canvas methods or frame scheduling. */
(function () {
  'use strict';
  if (window.WebOSGraphicsDoctor) return;
  var errors = [];
  var contextLoss = 0;
  var lastCanvasSnapshot = [];
  var maxErrors = 50;
  function report(kind, message) {
    errors.push({time: new Date().toISOString(), kind: kind, message: String(message).slice(0, 300)});
    if (errors.length > maxErrors) errors.shift();
    try { AndroidLuna.log('GRAPHICS', kind + ': ' + message); } catch (e) {}
  }
  document.addEventListener('webglcontextlost', function (e) {
    contextLoss++; report('CONTEXT_LOST', e.target && e.target.tagName);
  }, true);
  document.addEventListener('webglcontextrestored', function (e) {
    report('CONTEXT_RESTORED', e.target && e.target.tagName);
  }, true);
  function probe(kind) {
    var canvas = document.createElement('canvas');
    canvas.width = 4; canvas.height = 4;
    try {
      var gl = canvas.getContext(kind, {preserveDrawingBuffer: true});
      if (!gl) return {available: false};
      if (kind === '2d') {
        gl.fillStyle = 'rgb(255,0,0)';
        gl.fillRect(0, 0, 4, 4);
        var p = gl.getImageData(0, 0, 1, 1).data;
        return {available: true, redPixel: p[0] === 255 && p[1] === 0};
      }
      gl.clearColor(1, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
      var pixel = new Uint8Array(4);
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
      var result = {available: true, version: gl.getParameter(gl.VERSION),
        vendor: gl.getParameter(gl.VENDOR), maxTextureSize: gl.getParameter(gl.MAX_TEXTURE_SIZE),
        clearPixel: Array.from(pixel), error: gl.getError()};
      var lose = gl.getExtension('WEBGL_lose_context');
      if (lose) lose.loseContext();
      return result;
    } catch (e) { return {available: false, error: String(e)}; }
  }
  function canvases() {
    return Array.prototype.slice.call(document.querySelectorAll('canvas')).map(function (c, i) {
      var rect = c.getBoundingClientRect(), style = getComputedStyle(c);
      return {index: i, width: c.width, height: c.height, cssWidth: Math.round(rect.width),
        cssHeight: Math.round(rect.height), display: style.display, visibility: style.visibility,
        opacity: style.opacity, inViewport: rect.bottom > 0 && rect.top < innerHeight &&
          rect.right > 0 && rect.left < innerWidth};
    });
  }
  var lastProbe = null;
  window.WebOSGraphicsDoctor = {
    scan: function () {
      var list = canvases();
      var reportObj = {
        app: location.pathname, timestamp: new Date().toISOString(),
        userAgent: navigator.userAgent, viewport: {width: innerWidth, height: innerHeight,
          dpr: devicePixelRatio, screenWidth: screen.width, screenHeight: screen.height},
        wasm: typeof WebAssembly !== 'undefined',
        sharedArrayBuffer: typeof SharedArrayBuffer !== 'undefined',
        crossOriginIsolated: window.crossOriginIsolated === true,
        offscreenCanvas: typeof OffscreenCanvas !== 'undefined',
        requestAnimationFrame: typeof requestAnimationFrame === 'function',
        workers: typeof Worker !== 'undefined',
        canvasCount: list.length, canvases: list,
        contextLoss: contextLoss, recentErrors: errors.slice(-12),
        warning: list.length && list.every(function (c) {
          return !c.width || !c.height || !c.cssWidth || !c.cssHeight || c.display === 'none';
        }) ? 'All canvases have zero size or are hidden' : ''
      };
      if (!lastProbe) {
        lastProbe = {canvas2d: probe('2d'), webgl1: probe('webgl'), webgl2: probe('webgl2')};
      }
      reportObj.capabilityTests = lastProbe;
      return reportObj;
    },
    resetProbes: function () { lastProbe = null; },
    error: report
  };
  window.addEventListener('error', function (e) { report('JS_ERROR', e.message || 'unknown'); });
  window.addEventListener('unhandledrejection', function (e) { report('PROMISE', String(e.reason)); });
})();

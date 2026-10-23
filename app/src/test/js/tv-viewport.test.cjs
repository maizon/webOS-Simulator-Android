'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/compat-viewport.js', 'utf8');
function evaluate(width) {
  const metas = [];
  const head = {appendChild(node) { metas.push(node); node.parentNode = head; },
    removeChild(node) { const i = metas.indexOf(node); if (i >= 0) metas.splice(i, 1); }};
  const root = {attributes: new Map(), style: {},
    getAttribute(key) { return this.attributes.get(key) || null; },
    setAttribute(key, value) { this.attributes.set(key, value); }};
  const document = {
    currentScript: {getAttribute() { return width; }},
    head, documentElement: root, readyState: 'loading', handlers: [],
    createElement(type) {
      assert.equal(type, 'meta');
      return {attributes: new Map(), parentNode: head,
        setAttribute(key, value) { this.attributes.set(key, value); },
        getAttribute(key) { return this.attributes.get(key); }};
    },
    querySelectorAll(sel) {
      assert.match(sel, /viewport/);
      return metas.slice();
    },
    addEventListener(name, callback) { this.handlers.push([name, callback]); }
  };
  vm.runInNewContext(source, {document, console, AndroidLuna: {log() {}}, MutationObserver: undefined});
  return {document, metas, root};
}
test('1080p viewport preserves 1920 logical width and does not force min-width on the page', () => {
  const {document, metas, root} = evaluate('1920');
  assert.equal(metas.length, 1);
  assert.match(metas[0].getAttribute('content'), /width=1920/);
  assert.doesNotMatch(metas[0].getAttribute('content'), /initial-scale=1/);
  assert.equal(root.getAttribute('data-webos-simulator-viewport'), '1920x1080');
  assert.equal(root.style.minWidth, undefined);
  document.handlers.find(([name]) => name === 'DOMContentLoaded')[1]();
  assert.equal(metas.length, 1);
});
test('720p viewport takes resolution declared by appinfo, not device-width', () => {
  const {metas, root} = evaluate('1280');
  assert.match(metas[0].getAttribute('content'), /width=1280/);
  assert.equal(root.getAttribute('data-webos-simulator-viewport'), '1280x720');
});

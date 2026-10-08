import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../src/main/resources/static/js/file-tools.js', import.meta.url), 'utf8');

function setup({ open = true, fail = false } = {}) {
  const listeners = new Set(), timers = new Map(), editors = [], calls = [];
  const disclosure = { open, addEventListener: (_type, listener) => listeners.add(listener),
    removeEventListener: (_type, listener) => listeners.delete(listener) };
  const textarea = { value: 'first file' };
  const classes = new Set();
  const preview = { dataset: { textExtension: 'json', textName: 'first.json' },
    classList: { add: name => classes.add(name), remove: name => classes.delete(name) },
    closest: () => disclosure, matches: selector => selector === '[data-shared-text-preview]',
    querySelector: () => textarea,
    querySelectorAll: () => assert.fail('readonly initializer must not scan admin forms') };
  let nextTimer = 0;
  const window = { localStorage: { getItem: () => null },
    setTimeout(callback) { timers.set(++nextTimer, callback); return nextTimer; },
    clearTimeout: id => timers.delete(id),
    CodeMirror: { fromTextArea(node, options) {
      if (fail) throw new Error('unavailable');
      const editor = { node, options, wrapper: { style: {} },
        getWrapperElement() { return this.wrapper; },
        getInputField: () => ({ blur: () => calls.push('blur') }),
        refresh: () => calls.push('refresh'), toTextArea: () => calls.push('restore') };
      editors.push(editor);
      return editor;
    } } };
  vm.runInNewContext(source, { window, document: { addEventListener() {} },
    console: { warn: (...args) => calls.push(['warning', ...args]) } });
  return { preview, disclosure, listeners, timers, editors, classes, calls, api: window.EnderVaultFileTools,
    toggle() { for (const listener of [...listeners]) listener(); },
    tick() { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(callback => callback()); } };
}

test('public text initializes only a readonly CodeMirror and releases its instance and refresh timer', () => {
  const s = setup();
  s.api.initReadOnlyTextPreview(s.preview);
  s.api.initReadOnlyTextPreview(s.preview);
  assert.equal(s.editors.length, 1);
  assert.equal(s.editors[0].options.readOnly, true);
  assert.equal(s.editors[0].options.mode, 'application/json');
  assert.equal(s.preview.dataset.sharedTextPreviewBound, 'true');
  assert.equal(s.timers.size, 1);
  const pending = [...s.timers.values()][0];
  const before = s.calls.filter(call => call === 'refresh').length;
  s.api.destroyReadOnlyTextPreview(s.preview);
  assert.deepEqual(s.calls.slice(-2), ['blur', 'restore']);
  assert.equal(s.timers.size, 0);
  assert.equal(s.preview.dataset.sharedTextPreviewBound, undefined);
  assert.equal(s.classes.size, 0);
  pending();
  assert.equal(s.calls.filter(call => call === 'refresh').length, before);
  s.api.destroyReadOnlyTextPreview(s.preview);
  assert.equal(s.calls.filter(call => call === 'restore').length, 1);
});

test('departure while collapsed removes the lazy listener and prevents late initialization', () => {
  const s = setup({ open: false });
  s.api.initReadOnlyTextPreview(s.preview);
  s.api.initReadOnlyTextPreview(s.preview);
  assert.equal(s.listeners.size, 1);
  const oldToggle = [...s.listeners][0];
  s.api.destroyReadOnlyTextPreview(s.preview);
  assert.equal(s.listeners.size, 0);
  assert.equal(s.preview.dataset.sharedTextPreviewPending, undefined);
  s.disclosure.open = true;
  oldToggle();
  assert.equal(s.editors.length, 0);
});

test('lazy opening mounts once and the same preview can be initialized for the next file after cleanup', () => {
  const s = setup({ open: false });
  s.api.initReadOnlyTextPreview(s.preview);
  s.toggle();
  assert.equal(s.editors.length, 0);
  s.disclosure.open = true;
  s.toggle();
  assert.equal(s.listeners.size, 0);
  s.api.initReadOnlyTextPreview(s.preview);
  assert.equal(s.editors.length, 1);
  s.api.destroyReadOnlyTextPreview(s.preview);
  s.preview.dataset.textExtension = 'md';
  s.preview.dataset.textName = 'next.md';
  s.api.initReadOnlyTextPreview(s.preview);
  assert.equal(s.editors.length, 2);
  assert.equal(s.editors[1].options.mode, 'markdown');
  assert.equal(s.editors[1].options.readOnly, true);
});

test('generic file-tools disposal includes public previews and constructor failure leaves the fallback available', () => {
  const s = setup();
  s.api.initReadOnlyTextPreview(s.preview);
  s.api.destroy({ matches: () => false,
    querySelectorAll: selector => selector === '[data-shared-text-preview]' ? [s.preview] : [] });
  assert.equal(s.calls.filter(call => call === 'restore').length, 1);
  const failed = setup({ fail: true });
  failed.api.initReadOnlyTextPreview(failed.preview);
  assert.equal(failed.preview.dataset.sharedTextPreviewBound, undefined);
  assert.equal(failed.preview._endervaultSharedTextPreviewCleanup, undefined);
  assert.equal(failed.classes.size, 0);
  assert.equal(failed.calls.length, 1);
});

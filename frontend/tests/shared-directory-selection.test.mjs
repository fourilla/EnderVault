import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const source = readFileSync(new URL('../../src/main/resources/static/js/file-selection.js', import.meta.url), 'utf8');

function setup() {
  const listeners = new Map();
  const classes = new Set();
  const selectedClasses = new Set();
  const attributes = new Map();
  const classList = (values) => ({
    add: (name) => values.add(name),
    toggle: (name, active) => active ? values.add(name) : values.delete(name),
  });
  const item = { classList: classList(selectedClasses), setAttribute: (name, value) => attributes.set(name, value) };
  const checkbox = { checked: true, closest: () => item };
  const selectAll = { closest: () => null };
  const download = { title: 'Download selected file', dataset: {} };
  const document = {
    body: { classList: classList(classes) },
    addEventListener(name, listener) {
      const callbacks = listeners.get(name) || [];
      callbacks.push(listener);
      listeners.set(name, callbacks);
    },
    getElementById: (id) => id === 'downloadSelectedButton' ? download : null,
    querySelectorAll: (selector) => selector.includes('[form="bulkActionForm"]') ? [checkbox]
      : selector === '[data-select-all]' ? [selectAll] : [],
  };
  vm.runInNewContext(source, { document, window: {} });
  listeners.get('DOMContentLoaded')[0]();
  const click = (ancestors) => {
    const event = {
      target: { closest: (selector) => selector.split(',').some((part) => ancestors.includes(part.trim())) ? {} : null },
      preventDefault() { this.prevented = true; },
      stopPropagation() { this.stopped = true; },
    };
    listeners.get('click').forEach((listener) => listener(event));
    return event;
  };
  return { click, checkbox, selectAll, download, classes, selectedClasses, attributes };
}

for (const area of ['.toolbar', '.toolbar-cluster', '.toolbar-actions', '.workspace']) {
  test(`shared directory: empty ${area} clears selection and updates download/select-all state`, () => {
    const s = setup();
    assert.equal(s.download.disabled, false);
    assert.equal(s.selectAll.checked, true);
    const event = s.click(area === '.workspace' ? [area] : ['.toolbar', area]);
    assert.equal(s.checkbox.checked, false);
    assert.equal(s.selectAll.checked, false);
    assert.equal(s.selectAll.indeterminate, false);
    assert.equal(s.download.disabled, true);
    assert.equal(s.classes.has('selection-mode-active'), false);
    assert.equal(s.selectedClasses.has('is-selected'), false);
    assert.equal(s.attributes.get('aria-selected'), 'false');
    assert.equal(event.prevented, undefined);
  });
}

test('shared directory: download button, icon and text preserve selection and native submission', () => {
  for (const child of ['button', 'i', 'span']) {
    const s = setup();
    const event = s.click(['.toolbar', 'button', child]);
    assert.equal(s.checkbox.checked, true);
    assert.equal(s.download.disabled, false);
    assert.equal(s.classes.has('selection-mode-active'), true);
    assert.equal(event.prevented, undefined);
    assert.equal(event.stopped, undefined);
  }
});

test('shared directory: toolbar inputs, labels and links remain controls rather than background', () => {
  for (const control of ['input', 'label', 'select', 'textarea', 'summary', 'a[href]']) {
    const s = setup();
    s.click(['.toolbar', control, 'span']);
    assert.equal(s.checkbox.checked, true);
  }
});

test('shared directory: toast and select-all interactions preserve selection', () => {
  for (const control of ['.toast-region', '[data-select-all]', '.select-all-label']) {
    const s = setup();
    s.click([control]);
    assert.equal(s.checkbox.checked, true);
  }
});

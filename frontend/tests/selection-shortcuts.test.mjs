import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  return (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
}
const code = await compile('shared/browser/useSelectionShortcuts.ts');
const navigationCode = await compile('files/useBrowserNavigation.ts');

function setup() {
  const listeners = new Set(), removals = [], effects = [], calls = [];
  let ref, overlay = '', inside = true;
  const body = node('body'), html = node('html'), row = node('div', { row: true });
  const scope = { contains: (element) => element.inside !== false };
  const document = { body, documentElement: html, activeElement: body, visibilityState: 'visible', fullscreenElement: null,
    querySelector: (selector) => {
      assert.match(selector, /dialog\[open\]/);
      return overlay && selector.includes(overlay) ? {} : null;
    },
    addEventListener: (name, fn, capture) => { assert.equal(name, 'keydown'); assert.equal(capture, true); listeners.add(fn); },
    removeEventListener: (name, fn, capture) => {
      assert.equal(name, 'keydown'); assert.equal(capture, true); removals.push(fn); listeners.delete(fn);
    } };
  const react = { useRef: (value) => ref ??= { current: value }, useEffect: (fn) => { if (!effects.length) effects.push(fn()); } };
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, document, require: (id) => {
    if (id === 'react') return react;
    assert.equal(id, '../api/form-api');
    return { toastError: (reason) => calls.push(['error', reason.message]) };
  } });
  const options = { enabled: true, contextKey: 'files:alpha', selectedCount: 2, selectAll: () => calls.push('all'),
    clearSelection: () => calls.push('clear'), scope: () => inside ? scope : null };
  const render = () => module.exports.useSelectionShortcuts(options);
  const press = (key, extras = {}) => {
    const event = { key, target: row, defaultPrevented: false, ctrlKey: false, metaKey: false,
      altKey: false, shiftKey: false, isComposing: false, repeat: false,
      preventDefault() { this.defaultPrevented = true; }, ...extras };
    for (const listener of listeners) listener(event);
    return event;
  };
  render();
  return { options, render, press, calls, document, listeners, removals, row,
    overlay: (value) => { overlay = value; }, detach: () => { inside = false; },
    cleanup: () => effects.forEach((cleanup) => cleanup()) };
}

function node(tag, { row = false, inside = true, checkbox = '', zone = '' } = {}) {
  const parts = (selector) => selector.split(',').map((part) => part.trim());
  const element = { inside,
    matches: (selector) => checkbox && parts(selector).includes(`input[type="checkbox"].${checkbox}`),
    closest: (selector) => {
      const selectors = parts(selector);
      if (selectors.includes(tag) || (tag === 'a' && selectors.includes('a[href]'))
        || (row && selectors.includes('[data-context-item="true"]'))
        || (zone && selectors.includes(zone))) return element;
      return null;
    } };
  return element;
}

test('Files/Search opt-in uses Ctrl/Command+A and Escape without enabling other commands', () => {
  const s = setup();
  try {
    assert.equal(s.press('a', { ctrlKey: true }).defaultPrevented, true);
    assert.equal(s.press('A', { metaKey: true }).defaultPrevented, true);
    assert.equal(s.press('Escape').defaultPrevented, true);
    assert.deepEqual(s.calls, ['all', 'all', 'clear']);
    for (const [key, extras] of [['Delete', {}], ['a', {}], ['a', { ctrlKey: true, shiftKey: true }],
      ['a', { ctrlKey: true, altKey: true }], ['Escape', { ctrlKey: true }], ['c', { ctrlKey: true }]]) {
      assert.equal(s.press(key, extras).defaultPrevented, false);
    }
    assert.equal(s.calls.length, 3);
  } finally { s.cleanup(); }
});

test('unavailable, detached, composing and already-handled events preserve native behavior', () => {
  const s = setup();
  try {
    s.options.enabled = false; s.render();
    assert.equal(s.press('a', { ctrlKey: true }).defaultPrevented, false);
    s.options.enabled = true; s.options.selectedCount = 0; s.render();
    assert.equal(s.press('Escape').defaultPrevented, false);
    assert.equal(s.press('a', { ctrlKey: true, isComposing: true }).defaultPrevented, false);
    s.press('a', { ctrlKey: true, defaultPrevented: true });
    s.document.visibilityState = 'hidden';
    assert.equal(s.press('a', { ctrlKey: true }).defaultPrevented, false);
    s.document.visibilityState = 'visible'; s.detach();
    assert.equal(s.press('a', { ctrlKey: true }).defaultPrevented, false);
    assert.deepEqual(s.calls, ['clear']);
  } finally { s.cleanup(); }
});

test('Escape resets a remaining Shift anchor even with zero selected items, without consuming the native key', () => {
  const s = setup();
  try {
    s.options.selectedCount = 0; s.render();
    assert.equal(s.press('Escape').defaultPrevented, false);
    assert.deepEqual(s.calls, ['clear']);
    assert.equal(s.press('Delete').defaultPrevented, false);
    assert.deepEqual(s.calls, ['clear']);
  } finally { s.cleanup(); }
});

for (const area of ['input', 'textarea', 'select', '.sticky-note-layer', '.CodeMirror', '.cm-editor',
  '[contenteditable]:not([contenteditable="false"])', '[role="textbox"]', '[data-native-context-menu]',
  '.table-actions', '.action-icon']) {
  test(`${area}: target and active focus each protect native editing and control behavior`, () => {
    const s = setup(), target = node(['input', 'textarea', 'select'].includes(area) ? area : 'span', { row: true, zone: area });
    try {
      s.options.deleteSelection = async () => { s.calls.push('delete'); }; s.render();
      assert.equal(s.press('a', { ctrlKey: true, target }).defaultPrevented, false);
      assert.equal(s.press('Escape', { target }).defaultPrevented, false);
      assert.equal(s.press('Delete', { target }).defaultPrevented, false);
      s.document.activeElement = target;
      assert.equal(s.press('a', { ctrlKey: true }).defaultPrevented, false);
      assert.equal(s.press('Delete').defaultPrevented, false);
      assert.deepEqual(s.calls, []);
    } finally { s.cleanup(); }
  });
}

test('selection checkboxes and item links are list controls; Shell and other controls are not', () => {
  const s = setup();
  try {
    for (const target of [node('input', { checkbox: 'row-select-checkbox', row: true }),
      node('input', { checkbox: 'card-check', row: true }),
      node('input', { checkbox: 'select-all-checkbox' }), node('a', { row: true }), s.document.body, s.document.documentElement]) {
      s.document.activeElement = target;
      assert.equal(s.press('a', { ctrlKey: true, target }).defaultPrevented, true);
    }
    s.document.activeElement = s.document.body;
    for (const target of [node('button'), node('summary'), node('a'), node('div', { inside: false })]) {
      assert.equal(s.press('a', { ctrlKey: true, target }).defaultPrevented, false);
      s.document.activeElement = target;
      assert.equal(s.press('Escape').defaultPrevented, false);
      s.document.activeElement = s.document.body;
    }
    assert.equal(s.calls.length, 6);
  } finally { s.cleanup(); }
});

for (const overlay of ['dialog[open]', '[aria-modal="true"]', '.context-menu', '.admin-shell-popover.is-open',
  '.floating-actions-panel', '.is-editor-fullscreen', '.is-comic-fullscreen', '.is-image-fullscreen']) {
  test(`${overlay}: closing an overlay with Escape cannot also clear the underlying selection`, () => {
    const s = setup();
    try {
      s.overlay(overlay);
      s.options.deleteSelection = async () => { s.calls.push('delete'); }; s.render();
      assert.equal(s.press('Escape').defaultPrevented, false);
      assert.equal(s.press('a', { ctrlKey: true }).defaultPrevented, false);
      assert.equal(s.press('Delete').defaultPrevented, false);
      assert.deepEqual(s.calls, []);
      s.overlay('');
      assert.equal(s.press('Escape').defaultPrevented, true);
      assert.deepEqual(s.calls, ['clear']);
    } finally { s.cleanup(); }
  });
}

test('native fullscreen is protected and callbacks update without duplicate listeners', () => {
  const s = setup();
  s.document.fullscreenElement = {};
  assert.equal(s.press('a', { ctrlKey: true }).defaultPrevented, false);
  s.document.fullscreenElement = null;
  const original = [...s.listeners][0];
  s.options.selectAll = () => s.calls.push('new callback'); s.render(); s.render();
  assert.equal(s.listeners.size, 1);
  assert.equal([...s.listeners][0], original);
  s.press('a', { ctrlKey: true });
  assert.deepEqual(s.calls, ['new callback']);
  s.cleanup();
  assert.deepEqual(s.removals, [original]);
  assert.equal(s.listeners.size, 0);
  assert.equal(s.press('a', { ctrlKey: true }).defaultPrevented, false);
});

const settle = () => new Promise((resolve) => setImmediate(resolve));
function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

test('Delete is opt-in, requires a selection, and excludes modifier combinations and key repeat', async () => {
  const s = setup();
  try {
    assert.equal(s.press('Delete').defaultPrevented, false);
    s.options.deleteSelection = async () => { s.calls.push('delete'); }; s.render();
    s.options.selectedCount = 0; s.render();
    assert.equal(s.press('Delete').defaultPrevented, false);
    s.options.selectedCount = 2; s.render();
    for (const extras of [{ ctrlKey: true }, { metaKey: true }, { altKey: true }, { shiftKey: true },
      { repeat: true }, { isComposing: true }, { defaultPrevented: true }]) {
      s.press('Delete', extras);
    }
    assert.deepEqual(s.calls, []);
    s.options.enabled = false; s.render();
    assert.equal(s.press('Delete').defaultPrevented, false);
    s.options.enabled = true; s.render();
    assert.equal(s.press('Delete').defaultPrevented, true);
    await settle();
    assert.deepEqual(s.calls, ['delete']);
  } finally { s.cleanup(); }
});

test('pending confirmation/request blocks repeated Delete and releases the lock after cancellation or success', async () => {
  const s = setup(), request = deferred();
  try {
    s.options.deleteSelection = async () => { s.calls.push('first'); await request.promise; }; s.render();
    assert.equal(s.press('Delete').defaultPrevented, true);
    assert.equal(s.press('Delete').defaultPrevented, false);
    s.options.deleteSelection = async () => { s.calls.push('next'); }; s.render();
    assert.equal(s.press('Delete').defaultPrevented, false);
    assert.deepEqual(s.calls, ['first']);
    request.resolve(); await settle();
    assert.equal(s.press('Delete').defaultPrevented, true);
    await settle();
    assert.deepEqual(s.calls, ['first', 'next']);
  } finally { request.resolve(); s.cleanup(); }
});

test('late Delete confirmation cannot survive a condition change, even when returning to the same condition', async () => {
  const s = setup(), confirm = deferred();
  let stillCurrent;
  try {
    s.options.deleteSelection = async (isCurrent) => {
      stillCurrent = isCurrent;
      await confirm.promise;
      if (isCurrent()) s.calls.push('delete');
    }; s.render();
    s.press('Delete');
    assert.equal(stillCurrent(), true);
    s.options.contextKey = 'files:beta'; s.render();
    assert.equal(stillCurrent(), false);
    s.options.contextKey = 'files:alpha'; s.render();
    assert.equal(stillCurrent(), false);
    confirm.resolve(); await settle();
    assert.deepEqual(s.calls, []);
  } finally { confirm.resolve(); s.cleanup(); }
});

test('selection callback updates do not retarget an already open Delete confirmation', async () => {
  const s = setup(), confirm = deferred();
  try {
    s.options.deleteSelection = async (isCurrent) => {
      await confirm.promise;
      if (isCurrent()) s.calls.push('original selection');
    }; s.render();
    s.press('Delete');
    s.options.deleteSelection = async () => { s.calls.push('new selection'); }; s.render();
    confirm.resolve(); await settle();
    assert.deepEqual(s.calls, ['original selection']);
  } finally { confirm.resolve(); s.cleanup(); }
});

for (const end of ['disabled', 'unmounted']) {
  test(`${end}: late Delete confirmation cannot submit a mutation`, async () => {
    const s = setup(), confirm = deferred();
    try {
      s.options.deleteSelection = async (isCurrent) => {
        await confirm.promise;
        if (isCurrent()) s.calls.push('delete');
      }; s.render();
      s.press('Delete');
      if (end === 'disabled') { s.options.enabled = false; s.render(); }
      else s.cleanup();
      confirm.resolve(); await settle();
      assert.deepEqual(s.calls, []);
    } finally { confirm.resolve(); if (end === 'disabled') s.cleanup(); }
  });
}

test('a failed Delete callback reports through the common error policy and permits another attempt', async () => {
  const s = setup();
  try {
    s.options.deleteSelection = async () => { throw new Error('Unavailable'); }; s.render();
    s.press('Delete'); await settle();
    assert.deepEqual(s.calls, [['error', 'Unavailable']]);
    s.options.deleteSelection = async () => { s.calls.push('retry'); }; s.render();
    s.press('Delete'); await settle();
    assert.deepEqual(s.calls, [['error', 'Unavailable'], 'retry']);
  } finally { s.cleanup(); }
});

test('selection identity follows all request conditions but ignores view and scroll', () => {
  const module = { exports: {} };
  vm.runInNewContext(navigationCode, { module, exports: module.exports, require: () => ({}) });
  const key = module.exports.listingRequestKeyFor;
  const initial = { mode: 'browse', path: 'alpha', query: '', page: 1, sort: 'name', direction: 'asc',
    hidden: 'hide', pageSize: 40, view: 'table', scrollTop: 0 };
  for (const [field, value] of [['mode', 'search'], ['path', 'beta'], ['query', 'needle'], ['page', 2],
    ['sort', 'modified'], ['direction', 'desc'], ['hidden', 'show'], ['pageSize', 80]]) {
    assert.notEqual(key(initial), key({ ...initial, [field]: value }), field);
  }
  assert.equal(key(initial), key({ ...initial, view: 'grid', scrollTop: 600 }));
});

test('only Files/Search registers shortcuts in this stage and empty/loading/failed lists disable them', () => {
  const app = readFileSync(new URL('../src/files/BrowserApp.tsx', import.meta.url), 'utf8');
  assert.match(app, /listingRequestKeyFor\(currentState\)/);
  assert.match(app, /useSelectionShortcuts\(\{[\s\S]*payload && !loading && !error/);
  assert.match(app, /payload\.directories\.length \+ payload\.entries\.length > 0/);
  assert.match(app, /selectAll: selection\.selectAll/);
  assert.match(app, /clearSelection: selection\.clearSelection/);
  assert.match(app, /contextKey: listingRequestKeyFor\(currentState\)/);
  assert.match(app, /deleteSelection: \(isCurrent\) => actions\.moveEntriesToTrash\(selection\.selectedEntries, isCurrent\)/);
  for (const file of ['bookmarks/BookmarksApp.tsx', 'recent/RecentApp.tsx', 'shares/SharedLinksApp.tsx',
    'file-requests/FileRequestsApp.tsx', 'shared-file/SharedComicViewer.tsx']) {
    assert.doesNotMatch(readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8'), /useSelectionShortcuts/);
  }
});

import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createHookHarness } from './helpers/react-hooks.mjs';
import { sharedDirectoryMenuActions, sharedZipUrl } from '../src/public-share/directory-actions.ts';
import { nativeLinkClick, nativeTextSelection } from '../src/public-share/directory-interactions.ts';
import { browserMenuContext } from '../src/shared/browser/browser-menu-context.ts';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const sources = {};
for (const file of ['public-share/SharedDirectoryPage.tsx', 'shared/browser/useItemSelection.ts',
  'shared/browser/useSelectionShortcuts.ts', 'shared/browser/useBrowserContextMenu.ts']) sources[file] = await compile(file);

function entry(name, directory = false, preview = true) {
  return { name, path: `books/${name}`, directory, hidden: false, sizeLabel: '4 B', modifiedLabel: '2026-10-08',
    mediaType: directory ? 'directory' : 'text/plain', typeLabel: directory ? 'Directory' : 'Text',
    openUrl: directory ? `/s/token?path=${encodeURIComponent(`books/${name}`)}` : `/s/token/file?item=${encodeURIComponent(name)}`,
    detailUrl: directory ? null : `/s/token/file?item=${encodeURIComponent(name)}`,
    downloadUrl: directory ? null : `/s/token/download?path=books&item=${encodeURIComponent(name)}`,
    previewLandingUrl: !directory && preview ? `/s/token/file?item=${encodeURIComponent(name)}` : null };
}
function listing(entries = [entry('folder', true), entry('a.txt'), entry('b.txt', false, false)]) {
  return { targetType: 'DIRECTORY', view: 'listing', path: 'books', parentPath: '', rootUrl: '/s/token', upUrl: '/s/token',
    breadcrumbs: [{ label: 'Root', path: '', url: '/s/token' }, { label: 'books', path: 'books', url: '/s/token?path=books' }],
    entries, downloadZipUrl: '/s/token/download.zip?path=books' };
}
const visible = (actions, context) => actions.filter(action => !action.visible || action.visible(context));
const context = (mode, items = []) => ({ mode, item: items[0] || null, items });

test('ZIP uses exact repeated child names and preserves the server supplied relative path', () => {
  const names = ['folder, one', 'note + & 50% 한글.txt'];
  const url = new URL(sharedZipUrl('/s/token/download.zip?path=chapter%20%2B%20one%2Fpart%202&items=stale', names), 'https://example.test');
  assert.equal(url.pathname, '/s/token/download.zip');
  assert.equal(url.searchParams.get('path'), 'chapter + one/part 2');
  assert.deepEqual(url.searchParams.getAll('items'), names);
  assert.equal(url.searchParams.has('paths'), false);
});

test('public row, selection and background menus expose only their readonly public actions', () => {
  const calls = [], payload = listing();
  const actions = sharedDirectoryMenuActions({ listing: payload, selectedCount: 2,
    navigate: url => calls.push(['open', url]), download: url => calls.push(['download', url]),
    selectAll: () => calls.push('all'), clearSelection: () => calls.push('clear') });
  const file = context('single', [payload.entries[1]]), directory = context('single', [payload.entries[0]]);
  const selected = context('selection', payload.entries.slice(0, 2)), background = context('background');
  assert.deepEqual(visible(actions, file).map(action => action.id), ['open', 'download']);
  assert.deepEqual(visible(actions, directory).map(action => action.id), ['open', 'download-directory']);
  assert.deepEqual(visible(actions, selected).map(action => action.id), ['download-selected', 'clear-selection']);
  assert.deepEqual(visible(actions, background).map(action => action.id), ['select-all', 'clear-selection']);
  visible(actions, file)[0].run(file);
  visible(actions, directory)[1].run(directory);
  visible(actions, selected)[0].run(selected);
  visible(actions, background)[0].run(background);
  visible(actions, selected)[1].run(selected);
  assert.equal(calls[0][1], payload.entries[1].openUrl);
  assert.deepEqual(new URL(calls[1][1], 'https://example.test').searchParams.getAll('items'), ['folder']);
  assert.deepEqual(new URL(calls[2][1], 'https://example.test').searchParams.getAll('items'), ['folder', 'a.txt']);
  assert.deepEqual(calls.slice(3), ['all', 'clear']);
  const empty = sharedDirectoryMenuActions({ listing: listing([]), selectedCount: 0,
    navigate() {}, download() {}, selectAll() {}, clearSelection() {} });
  assert.deepEqual(visible(empty, background), []);
});

function load(file, modules, globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(sources[file], { module, exports: module.exports, URLSearchParams, ...globals,
    require(id) { assert.ok(id in modules, `unexpected import ${id}`); return modules[id]; } });
  return module.exports;
}
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.props?.children)];
}
const rows = tree => nodes(tree).filter(node => node.type === 'tr' && node.props['data-context-item'] === 'true');
function dom({ anchor = false, row = false, inside = true, checkbox = false, classes = [] } = {}) {
  const node = { inside,
    closest(selector) {
      const selectors = selector.split(',').map(part => part.trim());
      return anchor && selectors.includes('a[href]') || row && selectors.includes('[data-context-item="true"]')
        || checkbox && selectors.includes('input') || classes.some(name => selectors.includes('.' + name)) ? node : null;
    },
    matches(selector) { return checkbox && selector.includes('.row-select-checkbox'); },
  };
  return node;
}
function setup(initialSelectedNames = [], payload = listing()) {
  const h = createHookHarness(), changes = [], navigations = [], downloads = [], created = [], listeners = new Map(), timers = new Map();
  const react = { ...h.react, useLayoutEffect: h.react.useEffect };
  const body = dom(), html = dom();
  const scope = { contains: target => target?.inside !== false, querySelectorAll: () => payload.entries.map(item => ({ getAttribute: () => item.name })) };
  let timerId = 0, textSelection = null, closes = 0, disposals = 0;
  const document = { body, documentElement: html, activeElement: body, visibilityState: 'visible', fullscreenElement: null,
    querySelector: () => null,
    addEventListener(name, listener) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(listener); },
    removeEventListener(name, listener) { listeners.get(name)?.delete(listener); },
  };
  const window = { location: { assign: url => downloads.push(url) }, getSelection: () => textSelection,
    setTimeout(fn) { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id),
    EnderVaultContextMenus: { createActionMenu(options) { created.push(options); return { close() { closes++; },
      dispose() { disposals++; }, activeContext: () => null }; } } };
  const globals = { window, document };
  const menuHook = load('shared/browser/useBrowserContextMenu.ts', { react: h.react, './browser-menu-context': { browserMenuContext } }, globals);
  const selectionHook = load('shared/browser/useItemSelection.ts', { react: h.react }, globals);
  const shortcuts = load('shared/browser/useSelectionShortcuts.ts', { react: h.react, '../api/form-api': { toastError() { assert.fail('public keys must not submit mutations'); } } }, globals);
  function SelectionHeader() {}
  function StableTable() {}
  function OverflowMarquee() {}
  function Link() {}
  const page = load('public-share/SharedDirectoryPage.tsx', { react, 'react/jsx-runtime': jsx,
    'react-router-dom': { Link, useNavigate: () => url => navigations.push(url) },
    '../shared/browser/useItemSelection': selectionHook, '../shared/browser/useSelectionShortcuts': shortcuts,
    '../shared/browser/useBrowserContextMenu': menuHook, '../shared/browser/SelectionHeader': { SelectionHeader },
    '../shared/browser/StableTable': { StableTable }, '../shared/layout/OverflowMarquee': { OverflowMarquee },
    './directory-actions': { sharedDirectoryMenuActions, sharedZipUrl }, './directory-interactions': { nativeLinkClick, nativeTextSelection },
  }, globals);
  const props = { listing: payload, initialSelectedNames, onSelectionChange: names => changes.push(Array.from(names)) };
  const render = () => h.render(() => page.SharedDirectoryPage(props), tree => { tree.props.ref.current = scope; });
  const key = (name, extras = {}) => {
    const event = { key: name, target: dom({ row: true }), defaultPrevented: false, ctrlKey: false, metaKey: false,
      shiftKey: false, altKey: false, isComposing: false, repeat: false, preventDefault() { this.defaultPrevented = true; }, ...extras };
    listeners.get('keydown')?.forEach(listener => listener(event)); return event;
  };
  const click = (row, extras = {}, target = dom({ row: true })) => {
    const event = { button: 0, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, target,
      currentTarget: { querySelector: () => ({ focus() {} }) }, prevented: false, stopped: false,
      preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; }, ...extras };
    row.props.onMouseDownCapture(event); row.props.onClickCapture(event); return event;
  };
  const documentClick = target => listeners.get('click')?.forEach(listener => listener({ target,
    preventDefault() { assert.fail('background selection must not prevent native clicks'); } }));
  return { render, changes, navigations, downloads, created, scope, listeners, timers, document, key, click, documentClick, props,
    selectText(value) { textSelection = value; }, closes: () => closes, disposals: () => disposals, cleanup: () => h.dispose() };
}

test('history selection restores visible names without reporting a transient empty selection', () => {
  const s = setup(['a.txt', 'removed.txt']);
  try {
    const tree = s.render();
    assert.deepEqual(s.changes, [['a.txt']]);
    assert.equal(rows(tree)[1].props['aria-selected'], true);
    const header = nodes(tree).find(node => node.type?.name === 'SelectionHeader');
    assert.equal(header.props.total, 3); assert.equal(header.props.selected, 1);
    const table = nodes(tree).find(node => node.type?.name === 'StableTable');
    assert.deepEqual(Array.from(table.props.columns), ['select', 'text', 'type', 'size', 'date', 'actions']);
    assert.equal(table.props.actionCount, 2);
    const typeCell = rows(tree)[1].props.children[2];
    assert.equal(typeCell.props.children.type.name, 'OverflowMarquee');
    assert.equal(typeCell.props.children.props.text, 'text/plain');
    assert.equal(nodes(rows(tree)[0]).filter(node => node.type === 'i' && node.props.className === 'fas fa-folder item-icon').length, 1);
    assert.equal(nodes(rows(tree)[1]).filter(node => node.type === 'i' && node.props.className?.includes('item-icon')).length, 0);
    const toolbar = nodes(tree).find(node => node.props?.className === 'toolbar public-share-toolbar');
    assert.equal(toolbar.props.children[0].props.className, 'muted public-share-selection-count');
    assert.equal(toolbar.props.children[1].type, 'button');
    assert.equal(nodes(toolbar).filter(node => node.type === 'button').length, 1);
    for (const action of nodes(rows(tree)[1]).filter(node => node.props?.className?.includes('action-icon'))) {
      assert.equal(action.props.className, 'button-link ghost icon-button action-icon');
    }
    assert.equal(nodes(tree).filter(node => node.props?.title === 'Preview').length, 1);
  } finally { s.cleanup(); }
});

test('public listing reuses Ctrl/Command+A, Escape and Shift row selection while Delete and F2 remain native', () => {
  const s = setup();
  try {
    s.render();
    assert.equal(s.key('a', { ctrlKey: true }).defaultPrevented, true); s.render();
    assert.deepEqual(s.changes.at(-1), ['folder', 'a.txt', 'b.txt']);
    assert.equal(s.key('Delete').defaultPrevented, false);
    assert.equal(s.key('F2').defaultPrevented, false);
    assert.equal(s.key('Escape').defaultPrevented, true); s.render();
    s.click(rows(s.render())[0], { ctrlKey: true }); s.render();
    s.click(rows(s.render())[2], { shiftKey: true }); s.render();
    assert.deepEqual(s.changes.at(-1), ['folder', 'a.txt', 'b.txt']);
    s.cleanup();
    assert.equal(listenersSize(s.listeners), 0);
    assert.equal(s.disposals(), 1);
  } finally { s.cleanup(); }
});
function listenersSize(listeners) { return [...listeners.values()].reduce((total, group) => total + group.size, 0); }

test('modified filename anchors preserve native new-tab behavior even with an existing selection', () => {
  const s = setup(['a.txt']);
  try {
    s.render();
    for (const modifiers of [{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }]) {
      const event = s.click(rows(s.render())[2], modifiers, dom({ anchor: true, row: true }));
      assert.equal(event.prevented, false); assert.equal(event.stopped, false);
      s.render(); assert.deepEqual(s.changes.at(-1), ['a.txt']);
    }
    assert.deepEqual(s.navigations, []);
    s.key('Escape'); s.render();
    s.click(rows(s.render())[2]);
    assert.deepEqual(s.navigations, [s.props.listing.entries[2].openUrl]);
  } finally { s.cleanup(); }
});

test('context menu is scoped to the public listing and native text selection or empty lists keep the browser menu', () => {
  const s = setup();
  try {
    const tree = s.render(), options = s.created[0];
    assert.equal(options.pageScope, 'public-directory');
    assert.equal(options.contextForEvent({ target: dom({ inside: false }) }), null);
    const before = s.closes();
    s.selectText({ isCollapsed: false, toString: () => 'file name' });
    let stopped = false;
    tree.props.onContextMenuCapture({ stopPropagation() { stopped = true; }, preventDefault() { assert.fail('native menu must remain'); } });
    assert.equal(stopped, true); assert.equal(s.closes(), before + 1);
  } finally { s.cleanup(); }
  const empty = setup([], listing([]));
  try {
    const tree = empty.render();
    assert.equal(tree.props['data-native-context-menu'], true);
    assert.equal(nodes(tree).find(node => node.type === 'td').props.colSpan, 6);
    assert.equal(empty.key('a', { ctrlKey: true }).defaultPrevented, false);
    assert.equal(nodes(tree).find(node => node.type === 'button').props.disabled, true);
  } finally { empty.cleanup(); }
});

test('selection ZIP stays a native byte navigation and the button and its children preserve selected rows', () => {
  const s = setup(['folder', 'a.txt']);
  try {
    let tree = s.render();
    const button = nodes(tree).find(node => node.type === 'button' && node.props.className.includes('shared-download-button'));
    button.props.onClick();
    assert.deepEqual(new URL(s.downloads[0], 'https://example.test').searchParams.getAll('items'), ['folder', 'a.txt']);
    assert.deepEqual(s.navigations, []);
    for (const target of [button, ...nodes(button.props.children)]) {
      assert.ok(['button', 'i', 'span'].includes(target.type));
      s.documentClick(dom({ classes: ['toolbar', ...button.props.className.split(' ')] }));
      tree = s.render(); assert.deepEqual(s.changes.at(-1), ['folder', 'a.txt']);
    }
    rows(tree)[0].props.onPointerDown({ button: 0, shiftKey: false, ctrlKey: false, metaKey: false, altKey: false,
      pointerId: 1, clientX: 0, clientY: 0, target: dom({ row: true }) });
    assert.equal(s.timers.size, 1);
    s.cleanup(); assert.equal(s.timers.size, 0);
  } finally { s.cleanup(); }
});

for (const classes of [['toolbar', 'public-share-toolbar'], ['toolbar', 'public-share-selection-count']]) {
  test(`public ${classes.at(-1)} background clears selection, disables ZIP and resets the Shift anchor`, () => {
    const s = setup(['folder', 'a.txt']);
    try {
      // Establish an anchor separately from history-restored selection.
      s.click(rows(s.render())[0], { ctrlKey: true }); s.render();
      s.documentClick(dom({ classes }));
      let tree = s.render();
      assert.deepEqual(s.changes.at(-1), []);
      assert.ok(rows(tree).every(row => row.props['aria-selected'] === false));
      assert.equal(nodes(tree).find(node => node.type === 'button').props.disabled, true);
      s.click(rows(tree)[2], { shiftKey: true }); tree = s.render();
      assert.deepEqual(s.changes.at(-1), ['b.txt']);
    } finally { s.cleanup(); }
  });
}

test('a retained pending directory cancels long presses and disables selection, ZIP and menus without clearing its state', () => {
  const s = setup(['a.txt']);
  try {
    let tree = s.render();
    rows(tree)[0].props.onPointerDown({ button: 0, shiftKey: false, pointerId: 1,
      clientX: 0, clientY: 0, target: dom({ row: true }) });
    assert.equal(s.timers.size, 1);
    const closes = s.closes();
    s.props.disabled = true; tree = s.render();
    assert.equal(s.timers.size, 0);
    assert.ok(s.closes() > closes);
    assert.equal(s.created[0].actions().length, 0);
    assert.equal(s.key('a', { ctrlKey: true }).defaultPrevented, false);
    assert.equal(s.key('Escape').defaultPrevented, false);
    const button = nodes(tree).find(node => node.type === 'button');
    assert.equal(button.props.disabled, true);
    button.props.onClick();
    s.click(rows(tree)[2]); s.render();
    assert.deepEqual(s.downloads, []); assert.deepEqual(s.navigations, []);
    assert.deepEqual(s.changes.at(-1), ['a.txt']);
    s.props.disabled = false; tree = s.render();
    assert.equal(nodes(tree).find(node => node.type === 'button').props.disabled, false);
    assert.equal(s.key('a', { ctrlKey: true }).defaultPrevented, true); s.render();
    assert.deepEqual(s.changes.at(-1), ['folder', 'a.txt', 'b.txt']);
  } finally { s.cleanup(); }
});

test('common context menu keeps the admin default root and permits a public root without falling back', () => {
  for (const custom of [false, true]) {
    const h = createHookHarness(), lookups = [], created = [], workspace = {};
    const document = { querySelector(selector) { lookups.push(selector); return workspace; } };
    const window = { EnderVaultContextMenus: { createActionMenu(options) { created.push(options); return { close() {}, dispose() {}, activeContext: () => null }; } } };
    const { useBrowserContextMenu } = load('shared/browser/useBrowserContextMenu.ts', { react: h.react,
      './browser-menu-context': { browserMenuContext: options => { assert.equal(options.workspace, workspace); return 'context'; } } }, { window, document });
    const options = { menuId: 'test', pageScope: 'test', entries: () => [], itemKey: item => item.name,
      keyAttribute: 'data-entry-name', selectedRef: { current: new Set() }, setSelected() {}, actions: () => [],
      contentKey: '', errorMessage: 'Failed', ...(custom ? { getRoot: () => workspace } : {}) };
    h.render(() => useBrowserContextMenu(options));
    assert.deepEqual(lookups, custom ? [] : ['.app-main']);
    assert.equal(created[0].contextForEvent({}), 'context');
    h.dispose();
  }
  const h = createHookHarness();
  const { useBrowserContextMenu } = load('shared/browser/useBrowserContextMenu.ts', { react: h.react, './browser-menu-context': { browserMenuContext } },
    { document: { querySelector() { assert.fail('custom missing root must not use admin root'); } }, window: { EnderVaultContextMenus: { createActionMenu() { assert.fail('no root'); } } } });
  h.render(() => useBrowserContextMenu({ getRoot: () => null, menuId: 'missing', pageScope: 'public',
    entries: () => [], itemKey: item => item.name, keyAttribute: 'data-entry-name', selectedRef: { current: new Set() },
    setSelected() {}, actions: () => [], contentKey: '', errorMessage: 'Failed' }));
  h.dispose();
});

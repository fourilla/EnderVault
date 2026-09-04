import assert from 'node:assert/strict';
import test from 'node:test';
import { browserMenuContext } from '../src/shared/browser/browser-menu-context.ts';
import { fileEntryMenuActions } from '../src/shared/browser/file-entry-menu-actions.ts';

function setup(keys, attribute = 'data-entry-path') {
  const entries = keys.map((key) => ({ key }));
  const rows = keys.map((key) => ({
    closest(selector) { return selector === '[data-context-item="true"]' ? this : null; },
    getAttribute(name) { return name === attribute ? key : null; },
  }));
  const background = { closest: () => null };
  const workspace = { contains: (target) => rows.includes(target) || target === background,
    querySelectorAll: () => rows };
  let clears = 0;
  const context = (target, selected = [], overrides = {}) => browserMenuContext({
    event: { target }, workspace, entries, itemKey: (entry) => entry.key, keyAttribute: attribute,
    selected: new Set(selected), clearSelection: () => { clears++; }, ...overrides,
  });
  return { rows, context, entries, background, clears: () => clears };
}

test('Files and Recent resolve root and deeply nested names from the current snapshot only', () => {
  const keys = ['root.txt', 'a/b/같은 이름 & + [1].txt', 'c/같은 이름 & + [1].txt'];
  const { rows, context, entries } = setup(keys);
  keys.forEach((key, index) => assert.equal(context(rows[index]).item.key, key));
  assert.equal(context(rows[1], [], { entries: [entries[0]] }), null);
});

test('bookmark rows use UUID identity even with duplicate display names', () => {
  const { rows, context, entries } = setup(['uuid-a', 'uuid-b'], 'data-bookmark-id');
  entries.forEach((entry) => { entry.name = 'Same title'; });
  assert.equal(context(rows[1]).item.key, 'uuid-b');
});

test('selection applies only when the target is selected; another target clears without selecting it', () => {
  const { rows, context, clears } = setup(['a', 'b', 'c']);
  assert.equal(context(rows[0], ['a']).mode, 'single');
  const multiple = context(rows[0], ['a', 'b']);
  assert.equal(multiple.mode, 'selection');
  assert.deepEqual(multiple.items.map((item) => item.key), ['a', 'b']);
  const other = context(rows[2], ['a', 'b']);
  assert.equal(other.mode, 'single');
  assert.deepEqual(other.items.map((item) => item.key), ['c']);
  assert.equal(clears(), 1);
});

test('foreign workspace rows, stale selections and native editing controls are not actionable', () => {
  const current = setup(['a', 'b']);
  const foreign = setup(['a']);
  assert.equal(current.context(foreign.rows[0]), null);
  assert.deepEqual(current.context(current.rows[0], ['a', 'gone']).items.map((item) => item.key), ['a']);
  current.rows[0].closest = (selector) => selector.includes('input') ? current.rows[0] : null;
  assert.equal(current.context(current.rows[0]), null);
  assert.equal(current.context(current.background).mode, 'background');
});

test('shared entry actions use the clicked entry and omit directory-dependent background operations', () => {
  const calls = [];
  const actions = Object.fromEntries(['downloadEntries', 'toggleFavorite', 'addEntriesToBuffer',
    'shareAndCopy', 'renameEntry', 'moveEntriesToTrash'].map((name) => [name, (item) => calls.push([name, item])]));
  const menu = fileEntryMenuActions({ actions, browse: (path) => calls.push(['browse', path]),
    openFile: (url) => calls.push(['detail', url]) });
  const file = { type: 'file', path: 'a/b/note.txt', detailUrl: '/details', downloadUrl: '/download' };
  const single = { mode: 'single', item: file, items: [file] };
  const visible = (context) => menu.filter((action) => !action.visible || action.visible(context));
  assert.deepEqual(visible({ mode: 'background', item: null, items: [] }), []);
  assert.equal(visible(single).some((action) => action.id === 'preview'), false);
  assert.equal(visible({ ...single, item: { ...file, previewUrl: '/preview' } })
    .some((action) => action.id === 'preview'), true);
  menu.find((action) => action.id === 'rename').run(single);
  menu.find((action) => action.id === 'move-to-trash').run(single);
  menu.find((action) => action.id === 'open').run(single);
  menu.find((action) => action.id === 'open').run({ ...single, item: { ...file, type: 'directory' } });
  assert.deepEqual(calls, [['renameEntry', file], ['moveEntriesToTrash', [file]],
    ['detail', '/details'], ['browse', file.path]]);
  const multiple = visible({ mode: 'selection', item: file, items: [file, file] }).map((action) => action.id);
  assert.deepEqual(multiple, ['download', 'add-to-buffer', 'move-to-trash']);
});

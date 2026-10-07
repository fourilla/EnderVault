import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { tableColumnsHarness } from './helpers/table-columns-harness.mjs';

async function compile(path) {
  const result = await build({ configFile: false, logLevel: 'silent', build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${path}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const sources = {};
for (const path of ['shared/browser/BrowserEntries.tsx', 'bookmarks/BookmarkEntries.tsx', 'shared/browser/StableTable.tsx',
  'settings/sections/GeneralSettings.tsx']) {
  sources[path] = await compile(path);
}
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.props?.children)];
}
function load(path, tableColumns) {
  const module = { exports: {} };
  vm.runInNewContext(sources[path], { module, exports: module.exports, require(id) {
    if (id === 'react/jsx-runtime') return jsx;
    if (id.endsWith('/useTableColumns')) return tableColumns;
    if (id.endsWith('/StableTable')) return { StableTable() {} };
    if (id.endsWith('/SelectionHeader')) return { SelectionHeader() {} };
    if (id.endsWith('/BrowserEntries')) return { icon: () => null };
    return {};
  } });
  return module.exports;
}

test('shared table columns keep the schema intact and remove only Actions, including dynamic columns', () => {
  const h = tableColumnsHarness(), schema = ['select', 'text', 'text', 'date', 'date', 'actions'];
  assert.deepEqual(Array.from(h.useTableColumns(schema).columns), schema);
  h.setShown(false);
  const hidden = h.useTableColumns(schema);
  assert.deepEqual(Array.from(hidden.columns), schema.slice(0, -1));
  assert.equal(hidden.columnCount, 5);
  assert.equal(hidden.showActions, false);
  assert.equal(schema.length, 6);
  const table = load('shared/browser/StableTable.tsx', h).StableTable({ columns: hidden.columns, actionCount: 4, children: null });
  assert.ok(!table.props.style['--stable-table-minimum'].includes('--column-actions'));
  assert.equal(nodes(table).filter(node => node.type === 'col').length, 5);
  assert.deepEqual(Array.from(h.useTableColumns(['text', 'date']).columns), ['text', 'date']);
});

test('Files, Search and Recent table variants remove the same Actions column without affecting grid cards', () => {
  const h = tableColumnsHarness(), { EntryTable, EntryGrid } = load('shared/browser/BrowserEntries.tsx', h);
  const entry = { path: 'photo.png', type: 'file', name: 'photo.png', detailUrl: '/files/detail?path=photo.png',
    previewUrl: '/files/preview?path=photo.png', downloadUrl: '/files/download?path=photo.png' };
  const base = { entries: [entry], selected: new Set(), onBrowse() {}, onSelect() {}, onFavorite() {}, itemInteractionProps: () => ({}) };
  for (const extra of [{}, { showLocation: true }, { showAccessed: true }, { showLocation: true, showAccessed: true, selectable: false }]) {
    h.setShown(true);
    const shown = EntryTable({ ...base, ...extra });
    const shownCount = nodes(shown).filter(node => node.type === 'td').length;
    h.setShown(false);
    const hidden = EntryTable({ ...base, ...extra });
    const columns = nodes(hidden).find(node => node.type?.name === 'StableTable').props.columns;
    assert.equal(nodes(hidden).filter(node => node.type === 'td').length, shownCount - 1);
    assert.equal(columns.length, shownCount - 1);
    assert.ok(!nodes(hidden).some(node => node.type === 'th' && node.props.children === 'Actions'));
    assert.ok(nodes(hidden).some(node => node.props?.['data-entry-path'] === entry.path));
  }
  const grid = EntryGrid(base);
  assert.ok(!nodes(grid).some(node => node.type === 'button'));
  assert.ok(nodes(grid).some(node => node.type === 'input' && node.props.type === 'checkbox'));
});

test('bookmark tables keep row identity and selection controls while hiding all action cells', () => {
  const h = tableColumnsHarness(), { BookmarkTable } = load('bookmarks/BookmarkEntries.tsx', h);
  const props = { entries: [{ id: 'bookmark', title: 'Site', type: 'link', metadataRefreshable: true }],
    heading: 'Links', selected: new Set(['bookmark']), browse() {}, select() {}, itemInteractionProps: () => ({}),
    toggleFavorite() {}, refreshMetadata() {} };
  assert.equal(nodes(BookmarkTable(props)).filter(node => node.type === 'td').length, 5);
  h.setShown(false);
  const tree = BookmarkTable(props);
  assert.equal(nodes(tree).filter(node => node.type === 'td').length, 4);
  assert.ok(!nodes(tree).some(node => node.type === 'button'));
  assert.ok(nodes(tree).some(node => node.props?.['data-bookmark-id'] === 'bookmark'));
  assert.equal(nodes(tree).find(node => node.type === 'input').props.checked, true);
});

test('visibility is a separate global list setting, not a color preset, browser cookie or CSS hiding rule', () => {
  const settings = readFileSync(new URL('../src/settings/sections/GeneralSettings.tsx', import.meta.url), 'utf8');
  assert.match(settings, /showTableActions: snapshot.browser.showTableActions/);
  assert.match(settings, /title="List Display"[\s\S]*?<SettingsToggle name="showTableActions"/);
  assert.match(settings, /void refreshBootstrap\(\)/);
  const hook = readFileSync(new URL('../src/shared/browser/useTableColumns.ts', import.meta.url), 'utf8');
  assert.match(hook, /bootstrap.browser.showTableActions/);
  assert.doesNotMatch(hook, /localStorage|cookie|matchMedia|document/);
  const appearance = readFileSync(new URL('../src/shared/appearance/presets.ts', import.meta.url), 'utf8');
  assert.ok(!appearance.includes('showTableActions'));
});

test('Settings initializes the global toggle, keeps it in Appearance & Browser and refreshes bootstrap only after saving', () => {
  for (const showTableActions of [true, false]) {
    const snapshot = { appearance: {}, browser: { defaultView: 'table', defaultSort: 'name', defaultDirection: 'asc',
      defaultPageSize: 200, showTableActions }, stickyNotes: { backgroundColor: '#1B3033', borderColor: '#4E8F8A', textColor: '#EAF6F4' },
      storage: {}, recent: {}, trash: {}, fileTools: {}, remoteDownload: {} };
    let values, afterSave, refreshes = 0;
    const module = { exports: {} };
    vm.runInNewContext(sources['settings/sections/GeneralSettings.tsx'], { module, exports: module.exports,
      document: { documentElement: { style: { setProperty() {} } } }, require(id) {
        if (id === 'react/jsx-runtime') return jsx;
        if (id === 'react') return { useCallback: fn => fn };
        if (id.endsWith('/presets')) return { contrastRatio: () => 5, applyAppearance() {} };
        if (id.endsWith('/AdminAppContext')) return { useAdminApp: () => ({ refreshBootstrap: () => { refreshes++; } }) };
        if (id.endsWith('/AppearanceFields')) return { appearanceValues: () => ({}), appearanceFromValues: () => ({}), AppearanceFields() {} };
        if (id.endsWith('/useSettingsSnapshot')) return { useSettingsSnapshot: () => ({ snapshot }) };
        if (id.endsWith('/useSettingsEditor')) return { useSettingsEditor(initial, endpoint, dirty, validate, prepare, saved) {
          assert.equal(endpoint, '/api/v1/settings/general'); values = initial; afterSave = saved;
          return { values: initial, change() {} };
        } };
        if (id.endsWith('/SettingsControls')) return Object.fromEntries(['SettingsSection', 'SettingsToggle', 'SettingsField', 'SettingsSaveBar']
          .map(name => [name, { [name]() {} }[name]]));
        return {};
      } });
    const render = scope => {
      const outer = module.exports.GeneralSettings({ scope, onDirtyChange() {} });
      const editor = outer.props.children;
      return editor.type(editor.props);
    };
    const tree = render('appearance');
    const toggle = nodes(tree).find(node => node.type?.name === 'SettingsToggle' && node.props.name === 'showTableActions');
    assert.equal(toggle.props.values.showTableActions, showTableActions);
    assert.equal(values.showTableActions, showTableActions);
    assert.equal(refreshes, 0);
    afterSave(values);
    assert.equal(refreshes, 1);
    assert.ok(!nodes(render('files')).some(node => node.props?.name === 'showTableActions'));
  }
});

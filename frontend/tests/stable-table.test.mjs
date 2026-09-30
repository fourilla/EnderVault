import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const source = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');

test('stable columns depend on the table schema rather than current row data', () => {
  const component = source('shared/browser/StableTable.tsx');
  assert.match(component, /<colgroup>/);
  assert.match(component, /column === 'text' \? undefined/);
  assert.match(component, /columns\.map\(column/);
  assert.match(component, /var\(--table-action-icon-size\)/);
  assert.doesNotMatch(component, /entries|\.length.*name/);
  const css = source('shared/browser/stable-table.css');
  assert.match(css, /\.table-wrap > \.stable-table/);
  assert.match(css, /table-layout: fixed/);
  assert.match(css, /min-width: var\(--stable-table-minimum\)/);
  assert.match(css, /white-space: nowrap/);
  assert.match(css, /\.table-item-label > \.hidden-badge \{\s*flex: 0 0 auto/);
});

test('phase one opts in browser, favorites and trash without global table layout changes', () => {
  for (const path of ['shared/browser/BrowserEntries.tsx', 'favorites/FavoritesApp.tsx', 'trash/TrashApp.tsx']) {
    assert.match(source(path), /<StableTable columns=/);
  }
  const browser = source('shared/browser/BrowserEntries.tsx');
  assert.match(browser, /showLocation \? \['text' as const\]/);
  assert.match(browser, /showAccessed \? \['date' as const\]/);
  assert.match(browser, /actionCount=\{onFavorite \? 4 : 3\}/);
  const common = readFileSync(new URL('../../src/main/resources/static/css/components/browser.css', import.meta.url), 'utf8');
  assert.doesNotMatch(common, /table-layout: fixed/);
});

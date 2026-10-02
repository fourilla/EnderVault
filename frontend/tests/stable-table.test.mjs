import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const source = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');

test('share targets and request destinations use encoded SPA links to the represented resource', () => {
  const shares = source('shares/SharedLinksApp.tsx');
  assert.match(shares, /<PathLink path=\{share.path\} directory=\{share.type === 'DIRECTORY'\}/);
  const requests = source('file-requests/FileRequestsApp.tsx');
  assert.match(requests, /<PathLink path=\{item.destinationPath \|\| ''\} directory label=\{item.destinationLabel\}/);
  const link = source('shared/browser/PathLink.tsx');
  assert.match(link, /directory \? '\/files' : '\/files\/detail'/);
  assert.match(link, /encodeURIComponent\(path\)/);
  assert.match(link, /event.button !== 0/);
  for (const modifier of ['ctrlKey', 'metaKey', 'shiftKey', 'altKey']) assert.ok(link.includes(`event.${modifier}`));
  assert.match(source('shared/browser/BrowserEntries.tsx'), /onNavigate=\{\(\) => onBrowse\(entry.parentPath\)\}/);
});

test('phase two keeps action capacity, selectable share URLs and request secondary information', () => {
  const bookmarks = source('bookmarks/BookmarkEntries.tsx');
  assert.match(bookmarks, /columns=\{\['select', 'text', 'type', 'date', 'actions'\]\} actionCount=\{4\}/);
  assert.match(bookmarks, /<OverflowMarquee text=\{entry.title\}/);
  const shares = source('shares/SharedLinksApp.tsx');
  assert.match(shares, /'type', 'date', 'status', 'actions'\]\} actionCount=\{4\}/);
  assert.match(shares, /colSpan=\{6\}/);
  assert.match(shares, /aria-label=\{`Created:/);
  assert.match(shares, /aria-label=\{`Expires:/);
  assert.match(shares, /<input readOnly value=\{share.url\}/);
  const requests = source('file-requests/FileRequestsApp.tsx');
  assert.match(requests, /<Link className="button-link ghost icon-button action-icon" title="Details"/);
  assert.match(requests, /<StableTable className="file-requests-table"/);
  assert.match(requests, /'usage', 'restrictions', 'date', 'status', 'actions'\]\} actionCount=\{3\}/);
  assert.match(requests, /<small>\{item.createdLabel\}<\/small>/);
  assert.match(requests, /<small><OverflowMarquee text=\{item.extensionsLabel\}/);
  assert.match(requests, /<td title=\{item.usageLabel\}><div className="table-cell-stack">/);
  assert.match(requests, /\{item.acceptedFiles\} \/ \{item.maxFiles\} files/);
  assert.match(requests, /formatBytes\(item.acceptedBytes\)/);
  assert.match(requests, /formatBytes\(item.maxTotalBytes\)/);
  const css = source('shared/browser/stable-table.css');
  assert.match(css, /\.stable-table td input\[readonly\] \{[^}]*min-width: 0/s);
  assert.match(css, /\.stable-table \.bookmark-favicon \{ flex: 0 0 22px/);
});

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

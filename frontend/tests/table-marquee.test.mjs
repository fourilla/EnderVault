import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync, existsSync } from 'node:fs';

const source = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');

test('marquee activates from the owning row, card or setting and respects reduced motion', () => {
  const css = source('shared/layout/overflow-marquee.css');
  assert.match(css, /:is\(\.table-wrap tbody > tr, \.browser-card, \.settings-spa-nav-item, \.settings-control-row\):hover \.overflow-marquee\.is-overflowing/);
  assert.match(css, /prefers-reduced-motion: reduce[\s\S]*animation: none !important/);
  const entries = source('shared/browser/BrowserEntries.tsx');
  assert.match(entries, /className="card-name"[^>]*><OverflowMarquee text=\{entry.name\}/);
  assert.match(entries, /files-grid-location[\s\S]*<OverflowMarquee text=\{entry.parentPath \|\| 'Root'\}/);
});

test('settings and browser tables share one overflow marquee implementation', () => {
  for (const path of ['settings/SettingsApp.tsx', 'settings/components/SettingsControls.tsx',
    'shared/browser/BrowserEntries.tsx', 'favorites/FavoritesApp.tsx', 'trash/TrashApp.tsx']) {
    assert.match(source(path), /import \{ OverflowMarquee \} from .*layout\/OverflowMarquee/);
    assert.match(source(path), /<OverflowMarquee text=/);
  }
  assert.equal(existsSync(new URL('../src/settings/components/OverflowMarquee.tsx', import.meta.url)), false);
  const component = source('shared/layout/OverflowMarquee.tsx');
  assert.match(component, /title=\{text\}/);
  assert.match(component, /observer\.disconnect\(\)/);
  const css = source('shared/layout/overflow-marquee.css');
  assert.match(css, /is-overflowing:hover/);
  assert.match(css, /prefers-reduced-motion: reduce/);
  assert.match(css, /\.item-icon \{ flex: 0 0 auto; \}/);
});

test('favorites and trash keep common action alignment and bookmark identifiers', () => {
  for (const area of ['favorites', 'trash']) {
    assert.doesNotMatch(source(`${area}/${area}-app.css`), /justify-content:\s*center/);
  }
  const favorites = source('favorites/FavoritesApp.tsx');
  assert.match(favorites, /<th>Target<\/th>/);
  assert.match(favorites, /<OverflowMarquee text=\{entry.targetLabel\}/);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync, existsSync } from 'node:fs';

const source = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');

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

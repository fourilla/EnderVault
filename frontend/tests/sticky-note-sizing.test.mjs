import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const css = readFileSync(new URL('../../src/main/resources/static/css/components/sticky-notes.css', import.meta.url), 'utf8');
const previewCss = readFileSync(new URL('../../src/main/resources/static/css/pages/settings.css', import.meta.url), 'utf8');
const tokens = JSON.parse(readFileSync(new URL('../../src/main/resources/appearance-tokens.json', import.meta.url), 'utf8'));
const rule = (selector, source = css) => {
  const start = source.indexOf(`${selector} {`);
  assert.ok(start >= 0, selector);
  return source.slice(start, source.indexOf('}', start) + 1);
};

test('sticky note internals use UI sizing tokens without inheriting file card sizing', () => {
  assert.match(css, /\.sticky-note-card,\s*\.sticky-note-theme-preview\s*\{[^}]*font-size: var\(--field-font-size\)/);
  assert.doesNotMatch(css, /--card-|--browser-card-/);
  assert.match(rule('.sticky-note-header'), /height: var\(--sticky-note-header-height\)/);
  assert.match(rule('.sticky-note-title'), /font-size: var\(--sticky-note-title-font-size\)/);
  assert.match(rule('.sticky-note-editor'), /padding: var\(--sticky-note-content-padding\)/);
  for (const dimension of ['width', 'min-width', 'height', 'min-height']) {
    assert.ok(rule('.sticky-note-actions button').includes(`${dimension}: var(--table-action-icon-size)`));
  }
  assert.match(rule('.sticky-note-actions button i'), /font-size: var\(--table-action-icon-font-size\)/);
});

test('collapsed height and resize clearance follow UI density while expanded bounds stay unchanged', () => {
  assert.match(rule('.sticky-note-card.is-collapsed'), /height: calc\(var\(--sticky-note-header-height\) \+ 2px\) !important/);
  assert.match(rule('.sticky-note-header'), /flex: 0 0 auto/);
  assert.match(rule('.sticky-note-status'), /flex: 0 0 auto/);
  assert.match(rule('.sticky-note-status'), /padding: 2px calc\(var\(--sticky-note-content-padding\) \+ var\(--sticky-note-resize-size\)\)/);
  const card = rule('.sticky-note-card');
  assert.match(card, /min-width: 220px/);
  assert.match(card, /min-height: 140px/);
  assert.match(card, /max-width: min\(600px, calc\(100vw - 16px\)\)/);
  assert.match(card, /max-height: min\(700px, calc\(100vh - 16px\)\)/);
  assert.doesNotMatch(card, /(?:^|;)\s*(?:width|height|transform):/);
});

test('all supported UI sizes leave room for header actions and readable note text', () => {
  assert.match(css, /--sticky-note-header-height: calc\(var\(--control-height\) \+ 2px\)/);
  assert.match(css, /--sticky-note-content-padding: calc\(\(var\(--control-height\) - 14px\) \/ 2\)/);
  assert.match(css, /--sticky-note-title-font-size: max\(11px, calc\(var\(--field-font-size\) - 2px\)\)/);
  assert.match(css, /--sticky-note-status-font-size: max\(11px, calc\(var\(--field-font-size\) - 3px\)\)/);
  const sizes = Object.values(tokens.controls);
  const heights = sizes.map((size) => parseFloat(size['--control-height']));
  const fonts = sizes.map((size) => parseFloat(size['--field-font-size']));
  assert.deepEqual(heights.map((height) => height + 2), [32, 36, 40, 44, 48]);
  assert.deepEqual(heights.map((height) => (height - 14) / 2), [8, 10, 12, 14, 16]);
  assert.deepEqual(fonts.map((size) => Math.max(11, size - 2)), [11, 12, 13, 14, 15]);
  assert.deepEqual(fonts.map((size) => Math.max(11, size - 3)), [11, 11, 12, 13, 14]);
  for (const size of sizes) {
    assert.ok(parseFloat(size['--table-action-icon-size']) < parseFloat(size['--control-height']) + 2);
  }
});

test('settings note preview reuses the actual note typography and spacing', () => {
  for (const [selector, token] of [
    ['.sticky-note-theme-preview header', '--sticky-note-header-height'],
    ['.sticky-note-theme-preview header', '--sticky-note-title-font-size'],
    ['.sticky-note-theme-preview p', '--sticky-note-content-padding'],
    ['.sticky-note-theme-preview footer', '--sticky-note-status-font-size'],
  ]) {
    assert.ok(rule(selector, previewCss).includes(`var(${token})`));
  }
});

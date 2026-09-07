import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { appearanceTokens, colorPresets } from '../src/shared/appearance/presets.ts';

const preview = readFileSync(new URL('../src/settings/components/AppearancePreview.tsx', import.meta.url), 'utf8');
const fields = readFileSync(new URL('../src/settings/components/AppearanceFields.tsx', import.meta.url), 'utf8');
const toastCss = readFileSync(new URL('../../src/main/resources/static/css/components/toasts.css', import.meta.url), 'utf8');

test('preview intercepts sample operations and only requests color field inspection', () => {
  assert.match(preview, /onPointerDownCapture/);
  assert.match(preview, /onClickCapture/);
  assert.match(preview, /event.preventDefault\(\)/);
  assert.match(preview, /if \(target.field\) onInspectColor\(target.field\)/);
  assert.doesNotMatch(preview, /name=|autoFocus|\.focus\(|fetch\(|dispatchEvent\(/);
  for (const button of preview.matchAll(/<button\b[^>]*>/g)) assert.match(button[0], /type="button"/);
  assert.match(preview, /appearanceTokens\(value\)/);
  assert.match(preview, /if \(valid\) lastValid.current = appearance/);
  assert.ok(fields.indexOf('<AppearancePreview') < fields.indexOf('settings-field-grid appearance-editor-fields'));
});

test('preview reuses selection, tab and notification styles', () => {
  for (const style of ['browser-card', 'is-selected', 'table-wrap', 'settings-spa-nav-item is-active', 'toast-content']) {
    assert.ok(preview.includes(style));
  }
  for (const type of ['info', 'success', 'warning', 'error']) assert.ok(preview.includes(`['${type}'`));
});

test('presets leave semantic status colors alone and warning toasts do not use focus color', () => {
  for (const preset of Object.values(colorPresets)) {
    const tokens = appearanceTokens({ ...preset, controlSize: 'medium', cardSize: 'medium' });
    for (const name of ['success', 'warning', 'danger']) assert.equal(tokens[`--${name}`], undefined);
  }
  assert.match(toastCss, /\.toast-warning\s*\{\s*border-left-color: var\(--warning\)/);
  assert.match(toastCss, /\.toast-warning \.toast-icon\s*\{\s*color: var\(--warning\)/);
});

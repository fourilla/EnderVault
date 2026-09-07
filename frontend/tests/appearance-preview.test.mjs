import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { appearanceTokens, colorPresets } from '../src/shared/appearance/presets.ts';

const preview = readFileSync(new URL('../src/settings/components/AppearancePreview.tsx', import.meta.url), 'utf8');
const fields = readFileSync(new URL('../src/settings/components/AppearanceFields.tsx', import.meta.url), 'utf8');
const toastCss = readFileSync(new URL('../../src/main/resources/static/css/components/toasts.css', import.meta.url), 'utf8');
const settingsCss = readFileSync(new URL('../src/settings/settings-app.css', import.meta.url), 'utf8');

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

test('card preview icons inspect the primary text color they inherit', () => {
  assert.match(preview, /className="card-thumb"[^>]*><i\b[^>]*data-preview-color="text"/);
});

test('sample states stay fixed and disabled colors remain inspectable', () => {
  assert.match(preview, /appearance-preview-primary appearance-preview-focus/);
  assert.doesNotMatch(preview, /ghost appearance-preview-focus/);
  assert.match(preview, /appearance-preview-disabled" aria-disabled="true" data-preview-color="panel" data-preview-border="border"/);
  assert.doesNotMatch(preview, /\sdisabled(?:\s|>)/);
  for (const state of ['primary', 'hover', 'disabled']) {
    assert.match(settingsCss, new RegExp(`\\.appearance-preview \\.appearance-preview-${state},\\s*\\.appearance-preview \\.appearance-preview-${state}:hover`));
  }
  assert.doesNotMatch(settingsCss, /\.appearance-preview-focus:hover/);
});

test('preview navigation text does not claim unused row space', () => {
  assert.match(settingsCss, /\.appearance-preview \.settings-spa-nav-item > span\s*\{[^}]*width: fit-content;[^}]*justify-self: start;/);
});

test('presets leave semantic status colors alone and warning toasts do not use focus color', () => {
  for (const preset of Object.values(colorPresets)) {
    const tokens = appearanceTokens({ ...preset, controlSize: 'medium', cardSize: 'medium' });
    for (const name of ['success', 'warning', 'danger']) assert.equal(tokens[`--${name}`], undefined);
  }
  assert.match(toastCss, /\.toast-warning\s*\{\s*border-left-color: var\(--warning\)/);
  assert.match(toastCss, /\.toast-warning \.toast-icon\s*\{\s*color: var\(--warning\)/);
});

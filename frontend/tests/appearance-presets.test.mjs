import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { appearanceSizes, appearanceSizeTokens, appearanceTokens, colorPresets, colorPresetLabels, contrastRatio, normalizeAppearanceColor } from '../src/shared/appearance/presets.ts';

test('preset names match available palettes and Amethyst is no longer offered', () => {
  assert.deepEqual(Object.keys(colorPresets), Object.keys(colorPresetLabels));
  assert.equal(Object.hasOwn(colorPresets, 'amethyst'), false);
});

test('Neon Genesis preserves the requested palette and display name', () => {
  assert.equal(colorPresetLabels.neonGenesis, 'Neon Genesis');
  assert.deepEqual(colorPresets.neonGenesis, {
    accent: '#7C4DFF', accentStrong: '#A7F542', button: '#6840D9', buttonHover: '#8156EE', buttonText: '#F8F7FC',
    background: '#0C0A10', panel: '#15121B', panelElevated: '#201B29', panelMuted: '#2B2535', border: '#484050', text: '#F4F1F7', mutedText: '#B9B1C1',
  });
});

test('appearance maps only supported properties and rejects style injection atomically', () => {
  const appearance = { ...colorPresets.endervault, controlSize: 'medium', cardSize: 'medium' };
  assert.equal(appearanceTokens(appearance)['--button-primary-bg'], '#5BBDB4');
  assert.equal(appearanceTokens(appearance)['--topbar-base-height'], '58px');
  assert.equal(appearanceTokens(appearance)['--topbar-height'], undefined);
  assert.equal(appearanceTokens(appearance)['--bg'], '#0F141A');
  assert.equal(appearanceTokens(appearance)['--text'], '#EDF3F7');
  assert.throws(() => appearanceTokens({ ...appearance, button: 'url(example)' }));
  assert.throws(() => appearanceTokens({ ...appearance, background: 'url(example)' }));
});

test('appearance accepts colors, not arbitrary CSS', () => {
  assert.equal(normalizeAppearanceColor('#aabbcc'), '#AABBCC');
  for (const value of ['red', '#fff', 'url(example)', '#123456;display:none']) {
    assert.throws(() => normalizeAppearanceColor(value));
  }
});

test('selection and keyboard focus follow the chosen palette in scoped previews too', () => {
  for (const preset of Object.values(colorPresets)) {
    const tokens = appearanceTokens({ ...preset, controlSize: 'medium', cardSize: 'medium' });
    assert.equal(tokens['--focus'], preset.accentStrong);
    assert.match(tokens['--accent-soft'], /var\(--accent\)/);
    assert.match(tokens['--item-selected-bg'], /var\(--accent\)/);
    assert.match(tokens['--item-selected-bg'], /var\(--panel\)/);
  }
});

test('table and settings density grow with controls without fixing row heights', () => {
  let previousPadding = 0;
  let previousSettingsPadding = 0;
  for (const size of appearanceSizes) {
    const tokens = appearanceSizeTokens(size, 'medium');
    const padding = parseFloat(tokens['--table-cell-padding']);
    const settingsPadding = parseFloat(tokens['--settings-row-padding-y']);
    assert.ok(padding > previousPadding);
    assert.ok(settingsPadding > previousSettingsPadding);
    assert.equal(tokens['--table-font-size'], tokens['--button-font-size']);
    previousPadding = padding;
    previousSettingsPadding = settingsPadding;
  }
});

test('preset contrast meets the baseline except the explicitly requested Neon Genesis hover palette', () => {
  for (const [name, preset] of Object.entries(colorPresets)) {
    assert.ok(contrastRatio(preset.button, preset.buttonText) >= 4.5);
    // Preserve the requested colors; this one hover pair is below the usual 4.5 baseline.
    if (name === 'neonGenesis') {
      assert.ok(Math.abs(contrastRatio(preset.buttonHover, preset.buttonText) - 4.37888309062683) < 0.000001);
    } else {
      assert.ok(contrastRatio(preset.buttonHover, preset.buttonText) >= 4.5);
    }
    for (const background of [preset.background, preset.panel, preset.panelElevated, preset.panelMuted]) {
      assert.ok(contrastRatio(background, preset.text) >= 4.5);
      assert.ok(contrastRatio(background, preset.mutedText) >= 4.5);
    }
  }
});

test('button and card typography scale independently without changing document text', () => {
  const compact = appearanceSizeTokens('extra-small', 'extra-large');
  const large = appearanceSizeTokens('extra-large', 'extra-small');
  assert.ok(parseInt(compact['--button-font-size']) < parseInt(large['--button-font-size']));
  assert.ok(parseInt(compact['--card-title-font-size']) > parseInt(large['--card-title-font-size']));
  assert.ok(parseInt(compact['--card-body-padding']) > parseInt(large['--card-body-padding']));
  assert.equal(compact['--font-size-base'], undefined);
  assert.equal(appearanceSizeTokens('medium', 'medium')['--card-meta-font-size'], '13px');
});

test('single-line field text and padding fit inside every control height', () => {
  for (const size of appearanceSizes) {
    const tokens = appearanceSizeTokens(size, 'medium');
    const line = parseFloat(tokens['--field-font-size']) * Number(tokens['--field-line-height']);
    const verticalPadding = parseFloat(tokens['--field-padding']);
    assert.ok(line + 2 * verticalPadding + 2 <= parseFloat(tokens['--control-height']), size);
  }
});

test('size steps grow together and card size is independent', () => {
  let previous = 0;
  for (const size of appearanceSizes) {
    const tokens = appearanceSizeTokens(size, 'medium');
    assert.ok(parseInt(tokens['--control-height']) > previous);
    assert.ok(parseInt(tokens['--topbar-base-height']) > parseInt(tokens['--icon-button-size']));
    assert.equal(tokens['--browser-card-min-width'], '190px');
    previous = parseInt(tokens['--control-height']);
  }
  assert.equal(appearanceSizeTokens('medium', 'extra-large')['--control-height'], '38px');
  assert.throws(() => appearanceSizeTokens('123px', 'medium'));
});

test('badge sizing is scoped to tables and file cards with safe values at every size', () => {
  const css = readFileSync(new URL('../../src/main/resources/static/css/components/browser.css', import.meta.url), 'utf8');
  const table = css.match(/\.table-wrap\s*\{([^}]+)\}/)[1];
  const cards = css.match(/\.browser-card,\s*\.readonly-file-card\s*\{([^}]+)\}/)[1];
  const badge = css.match(/\.status-badge\s*\{([^}]+)\}/)[1];
  assert.match(table, /--badge-font-size: calc\(var\(--table-font-size\) - 3px\)/);
  assert.match(cards, /--badge-font-size: calc\(var\(--card-meta-font-size\) - 1px\)/);
  assert.doesNotMatch(cards, /--control-height|--table-font-size/);
  assert.doesNotMatch(table, /--card-/);
  assert.match(badge, /var\(--badge-font-size, var\(--font-size-xs\)\)/);
  assert.match(badge, /var\(--badge-min-height, var\(--toast-close-size\)\)/);
  for (const scope of [table, cards]) {
    assert.match(scope, /--badge-padding-y: max\(2px,/);
    assert.match(scope, /--badge-padding-x: max\(7px,/);
  }
  assert.match(table, /--badge-min-height: max\(calc\(var\(--badge-font-size\) \* 2 \+ 2px\),/);
  for (const size of appearanceSizes) {
    const tokens = appearanceSizeTokens(size, size);
    assert.ok(parseFloat(tokens['--table-font-size']) - 3 >= 10);
    assert.ok(parseFloat(tokens['--card-meta-font-size']) - 1 >= 10);
    assert.ok((parseFloat(tokens['--control-height']) - 30) / 4 >= 0);
    assert.ok((parseFloat(tokens['--card-body-padding']) - 8) / 2 >= 0);
  }
});

test('hidden preview rows retain their opacity when inspected', () => {
  const css = readFileSync(new URL('../src/settings/settings-app.css', import.meta.url), 'utf8');
  assert.match(css, /\.appearance-preview \.is-hidden-item:hover\s*\{\s*opacity: 0\.68;/);
});

test('topbar responsive height remains CSS-owned and compact layout stays two rows', () => {
  const css = readFileSync(new URL('../src/app/app-shell.css', import.meta.url), 'utf8');
  for (const size of appearanceSizes) {
    assert.equal(appearanceSizeTokens(size, size)['--topbar-height'], undefined);
  }
  assert.match(css, /--topbar-height: max\(var\(--topbar-base-height\)/);
  assert.match(css, /grid-template-rows: repeat\(2, var\(--topbar-row-height\)\)/);
  assert.match(css, /\.topbar-actions \{ grid-column: 2; grid-row: 1; justify-content: flex-end;/);
  assert.doesNotMatch(css, /repeat\(3, var\(--topbar-row-height\)\)|grid-row: 3|\.topbar-actions\s*\{[^}]*justify-content: space-between/);
  assert.doesNotMatch(css, /max-width: 600px/);
});

test('file extension labels keep fixed typography and spacing regardless of appearance size', () => {
  const css = readFileSync(new URL('../../src/main/resources/static/css/components/browser.css', import.meta.url), 'utf8');
  const extension = css.match(/\.thumb-extension\s*\{([^}]+)\}/)[1];
  const center = css.match(/\.thumb-extension-center\s*\{([^}]+)\}/)[1];
  const overlay = css.match(/\.thumb-extension-overlay\s*\{([^}]+)\}/)[1];
  assert.match(extension, /font-size: var\(--font-size-xs\)/);
  assert.match(extension, /font-family: inherit/);
  assert.match(extension, /font-weight: 700/);
  assert.match(center, /padding: 9px var\(--space-xl\)/);
  assert.match(overlay, /padding: 5px var\(--space-md\)/);
  assert.doesNotMatch(extension + center + overlay, /--card-|--badge-|--control-/);
});

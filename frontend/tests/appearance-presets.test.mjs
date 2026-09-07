import assert from 'node:assert/strict';
import test from 'node:test';
import { appearanceSizes, appearanceSizeTokens, appearanceTokens, colorPresets, contrastRatio, normalizeAppearanceColor } from '../src/shared/appearance/presets.ts';

test('appearance maps only supported properties and rejects style injection atomically', () => {
  const appearance = { ...colorPresets.endervault, controlSize: 'medium', cardSize: 'medium' };
  assert.equal(appearanceTokens(appearance)['--button-primary-bg'], '#5BBDB4');
  assert.equal(appearanceTokens(appearance)['--topbar-height'], '58px');
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

test('preset button text remains legible for normal and hover backgrounds', () => {
  for (const preset of Object.values(colorPresets)) {
    assert.ok(contrastRatio(preset.button, preset.buttonText) >= 4.5);
    assert.ok(contrastRatio(preset.buttonHover, preset.buttonText) >= 4.5);
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
    assert.ok(parseInt(tokens['--topbar-height']) > parseInt(tokens['--icon-button-size']));
    assert.equal(tokens['--browser-card-min-width'], '190px');
    previous = parseInt(tokens['--control-height']);
  }
  assert.equal(appearanceSizeTokens('medium', 'extra-large')['--control-height'], '38px');
  assert.throws(() => appearanceSizeTokens('123px', 'medium'));
});

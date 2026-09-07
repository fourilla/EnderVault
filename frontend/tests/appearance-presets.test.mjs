import assert from 'node:assert/strict';
import test from 'node:test';
import { appearanceSizes, appearanceSizeTokens, appearanceTokens, colorPresets, contrastRatio, normalizeAppearanceColor } from '../src/shared/appearance/presets.ts';

test('appearance maps only supported properties and rejects style injection atomically', () => {
  const appearance = { ...colorPresets.endervault, controlSize: 'medium', cardSize: 'medium' };
  assert.equal(appearanceTokens(appearance)['--button-primary-bg'], '#5BBDB4');
  assert.equal(appearanceTokens(appearance)['--topbar-height'], '58px');
  assert.throws(() => appearanceTokens({ ...appearance, button: 'url(example)' }));
});

test('appearance accepts colors, not arbitrary CSS', () => {
  assert.equal(normalizeAppearanceColor('#aabbcc'), '#AABBCC');
  for (const value of ['red', '#fff', 'url(example)', '#123456;display:none']) {
    assert.throws(() => normalizeAppearanceColor(value));
  }
});

test('preset button text remains legible for normal and hover backgrounds', () => {
  for (const preset of Object.values(colorPresets)) {
    assert.ok(contrastRatio(preset.button, preset.buttonText) >= 4.5);
    assert.ok(contrastRatio(preset.buttonHover, preset.buttonText) >= 4.5);
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

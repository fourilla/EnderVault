export const appearanceSizes = ['extra-small', 'small', 'medium', 'large', 'extra-large'] as const;
export type AppearanceSize = typeof appearanceSizes[number];
export type Appearance = { accent: string; accentStrong: string; button: string; buttonHover: string; buttonText: string; controlSize: AppearanceSize; cardSize: AppearanceSize };

export function appearanceTokens(value: Appearance): Record<string, string> {
  return {
    ...appearanceSizeTokens(value.controlSize, value.cardSize),
    '--accent': normalizeAppearanceColor(value.accent),
    '--accent-strong': normalizeAppearanceColor(value.accentStrong),
    '--button-primary-bg': normalizeAppearanceColor(value.button),
    '--button-primary-hover-bg': normalizeAppearanceColor(value.buttonHover),
    '--button-primary-text': normalizeAppearanceColor(value.buttonText),
  };
}

export function applyAppearance(value: Appearance) {
  const tokens = appearanceTokens(value);
  Object.entries(tokens).forEach(([name, token]) => document.documentElement.style.setProperty(name, token));
}

export const colorPresets = {
  endervault: { accent: '#5BBDB4', accentStrong: '#79D2C8', button: '#5BBDB4', buttonHover: '#79D2C8', buttonText: '#071617' },
  blue: { accent: '#72B7F5', accentStrong: '#9ACFFF', button: '#72B7F5', buttonHover: '#9ACFFF', buttonText: '#101820' },
  rose: { accent: '#ECA0B9', accentStrong: '#F6BDCF', button: '#ECA0B9', buttonHover: '#F6BDCF', buttonText: '#231218' },
} as const;

const controlDimensions = {
  'extra-small': [30, 34, 32, 26, 24, 50, 56],
  small: [34, 38, 36, 30, 28, 54, 60],
  medium: [38, 42, 40, 36, 30, 58, 64],
  large: [42, 46, 44, 40, 34, 62, 68],
  'extra-large': [46, 50, 48, 44, 38, 66, 72],
} as const;

const cardWidths: Record<AppearanceSize, number> = {
  'extra-small': 150, small: 170, medium: 190, large: 230, 'extra-large': 270,
};

const dimensionTokens = [
  '--control-height', '--icon-button-size', '--nav-row-height', '--action-icon-size',
  '--table-action-icon-size', '--topbar-height', '--sidebar-rail-width',
] as const;

export function normalizeAppearanceColor(value: string): string {
  if (!/^#[0-9a-f]{6}$/i.test(value)) throw new Error('Use a six-digit hexadecimal color.');
  return value.toUpperCase();
}

export function appearanceSizeTokens(controls: AppearanceSize, cards: AppearanceSize): Record<string, string> {
  if (!appearanceSizes.includes(controls) || !appearanceSizes.includes(cards)) {
    throw new Error('Unknown appearance size.');
  }
  return {
    ...Object.fromEntries(dimensionTokens.map((token, index) => [token, `${controlDimensions[controls][index]}px`])),
    '--browser-card-min-width': `${cardWidths[cards]}px`,
  };
}

function relativeLuminance(hex: string): number {
  const normalized = normalizeAppearanceColor(hex);
  const linear = [1, 3, 5].map((offset) => {
    const channel = Number.parseInt(normalized.slice(offset, offset + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

export function contrastRatio(first: string, second: string): number {
  const a = relativeLuminance(first);
  const b = relativeLuminance(second);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

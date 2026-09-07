export const appearanceSizes = ['extra-small', 'small', 'medium', 'large', 'extra-large'] as const;
export type AppearanceSize = typeof appearanceSizes[number];
export const appearanceColorFields = {
  accent: 'Accent', accentStrong: 'Accent highlight', button: 'Button background', buttonHover: 'Button hover', buttonText: 'Button text',
  background: 'Page background', panel: 'Panel background', panelElevated: 'Elevated background', panelMuted: 'Muted background',
  border: 'Border', text: 'Primary text', mutedText: 'Secondary text',
} as const;
export type Appearance = Record<keyof typeof appearanceColorFields, string> & { controlSize: AppearanceSize; cardSize: AppearanceSize };

export function appearanceTokens(value: Appearance): Record<string, string> {
  return {
    ...appearanceSizeTokens(value.controlSize, value.cardSize),
    '--accent': normalizeAppearanceColor(value.accent),
    '--accent-strong': normalizeAppearanceColor(value.accentStrong),
    '--focus': normalizeAppearanceColor(value.accentStrong),
    '--accent-soft': 'color-mix(in srgb, var(--accent) 24%, var(--panel))',
    '--item-selected-bg': 'color-mix(in srgb, var(--accent) 18%, var(--panel))',
    '--item-selected-shadow': 'color-mix(in srgb, var(--accent) 28%, transparent)',
    '--button-primary-bg': normalizeAppearanceColor(value.button),
    '--button-primary-hover-bg': normalizeAppearanceColor(value.buttonHover),
    '--button-primary-text': normalizeAppearanceColor(value.buttonText),
    '--bg': normalizeAppearanceColor(value.background),
    '--panel': normalizeAppearanceColor(value.panel),
    '--panel-elevated': normalizeAppearanceColor(value.panelElevated),
    '--panel-muted': normalizeAppearanceColor(value.panelMuted),
    '--line': normalizeAppearanceColor(value.border),
    '--text': normalizeAppearanceColor(value.text),
    '--muted': normalizeAppearanceColor(value.mutedText),
    '--thumb-bg': normalizeAppearanceColor(value.panelMuted),
  };
}

export function applyAppearance(value: Appearance) {
  const tokens = appearanceTokens(value);
  Object.entries(tokens).forEach(([name, token]) => document.documentElement.style.setProperty(name, token));
}

export const colorPresets = {
  endervault: { accent: '#5BBDB4', accentStrong: '#79D2C8', button: '#5BBDB4', buttonHover: '#79D2C8', buttonText: '#071617',
    background: '#0F141A', panel: '#171D24', panelElevated: '#1D2530', panelMuted: '#202933', border: '#2F3A47', text: '#EDF3F7', mutedText: '#9BA8B7' },
  blue: { accent: '#428EFF', accentStrong: '#78AEFF', button: '#428EFF', buttonHover: '#78AEFF', buttonText: '#06090F',
    background: '#08090B', panel: '#111215', panelElevated: '#1B1D22', panelMuted: '#252830', border: '#414650', text: '#F5F7FA', mutedText: '#B1B7C2' },
  rose: { accent: '#FF3891', accentStrong: '#FF70AD', button: '#FF3891', buttonHover: '#FF70AD', buttonText: '#0A0205',
    background: '#030303', panel: '#0B0B0B', panelElevated: '#151515', panelMuted: '#202020', border: '#383838', text: '#FAFAFA', mutedText: '#B5B5B5' },
  olive: { accent: '#A8E63A', accentStrong: '#C7F56A', button: '#84B82D', buttonHover: '#95CB35', buttonText: '#0D1305',
    background: '#0B0D0B', panel: '#121512', panelElevated: '#1B1F1B', panelMuted: '#252A24', border: '#3C443A', text: '#EFF3EC', mutedText: '#A7B0A3' },
  graphite: { accent: '#E5E5E5', accentStrong: '#FFFFFF', button: '#E5E5E5', buttonHover: '#FFFFFF', buttonText: '#171717',
    background: '#1B1B1B', panel: '#242424', panelElevated: '#303030', panelMuted: '#383838', border: '#575757', text: '#FAFAFA', mutedText: '#BDBDBD' },
  gunmetal: { accent: '#AAB8C6', accentStrong: '#C7D2DD', button: '#3B4652', buttonHover: '#465462', buttonText: '#F1F4F7',
    background: '#0E1012', panel: '#16191C', panelElevated: '#20242A', panelMuted: '#292F35', border: '#3C444D', text: '#EEF1F4', mutedText: '#A7AFB8' },
  frost: { accent: '#88C0D0', accentStrong: '#9BD4E3', button: '#456582', buttonHover: '#52718E', buttonText: '#F2F5F8',
    background: '#151A21', panel: '#1D242E', panelElevated: '#252E3A', panelMuted: '#2E3947', border: '#465467', text: '#ECEFF4', mutedText: '#AAB6C4' },
  amethyst: { accent: '#BD93F9', accentStrong: '#D4B5FF', button: '#6F4AA8', buttonHover: '#7E59BA', buttonText: '#FAF7FF',
    background: '#14151C', panel: '#1C1D26', panelElevated: '#252733', panelMuted: '#303342', border: '#45485A', text: '#F8F8F2', mutedText: '#B7B9C8' },
} as const;
export const colorPresetLabels = { endervault: 'EnderVault', blue: 'Electric Blue', rose: 'Black & Hot Pink', olive: 'Olive', graphite: 'Graphite', gunmetal: 'Gunmetal', frost: 'EnderVault Frost', amethyst: 'Amethyst' } as const;

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
const controlTypography = {
  'extra-small': [13, 15, 12, 10], small: [14, 16, 13, 12], medium: [15, 17, 14, 14],
  large: [16, 18, 15, 16], 'extra-large': [17, 19, 16, 18],
} as const;
const cardTypography = {
  'extra-small': [13, 11, 8, 6], small: [14, 12, 10, 7], medium: [15, 13, 12, 8],
  large: [16, 14, 14, 9], 'extra-large': [17, 15, 16, 10],
} as const;

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
    '--button-font-size': `${controlTypography[controls][0]}px`,
    '--field-font-size': `${controlTypography[controls][0]}px`,
    '--field-line-height': '1.4',
    '--table-font-size': `${controlTypography[controls][0]}px`,
    '--table-cell-padding': `${[5, 7, 10, 12, 14][appearanceSizes.indexOf(controls)]}px 12px`,
    '--settings-row-padding-y': `${[4, 6, 8, 10, 12][appearanceSizes.indexOf(controls)]}px`,
    '--field-padding': `${[4, 5, 7, 8, 9][appearanceSizes.indexOf(controls)]}px 10px`,
    '--icon-button-font-size': `${controlTypography[controls][1]}px`,
    '--table-action-icon-font-size': `${controlTypography[controls][2]}px`,
    '--button-padding-x': `${controlTypography[controls][3]}px`,
    '--card-title-font-size': `${cardTypography[cards][0]}px`,
    '--card-meta-font-size': `${cardTypography[cards][1]}px`,
    '--card-body-padding': `${cardTypography[cards][2]}px`,
    '--card-body-gap': `${cardTypography[cards][3]}px`,
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

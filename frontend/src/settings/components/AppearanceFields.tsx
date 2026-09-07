import type { CSSProperties } from 'react';
import { appearanceSizes, appearanceTokens, colorPresets, contrastRatio, type Appearance } from '../../shared/appearance/presets';
import type { FormValues, FormValue } from '../types';
import { SettingsField, SettingsSection } from './SettingsControls';

export const appearanceValues = (appearance: Appearance): FormValues => Object.fromEntries(
  Object.entries(appearance).map(([key, value]) => [`appearance${key[0].toUpperCase()}${key.slice(1)}`, value]),
);
export const appearanceFromValues = (values: FormValues): Appearance => ({
  accent: String(values.appearanceAccent), accentStrong: String(values.appearanceAccentStrong),
  button: String(values.appearanceButton), buttonHover: String(values.appearanceButtonHover),
  buttonText: String(values.appearanceButtonText),
  controlSize: values.appearanceControlSize as Appearance['controlSize'],
  cardSize: values.appearanceCardSize as Appearance['cardSize'],
});

export function AppearanceFields({ values, change }: { values: FormValues; change: (name: string, value: FormValue) => void }) {
  const appearance = appearanceFromValues(values);
  const valid = [appearance.accent, appearance.accentStrong, appearance.button, appearance.buttonHover, appearance.buttonText]
    .every((value) => /^#[0-9a-f]{6}$/i.test(value));
  const style = valid ? appearanceTokens(appearance) as CSSProperties : undefined;
  const applyPreset = (preset: typeof colorPresets[keyof typeof colorPresets]) => {
    Object.entries(appearanceValues({ ...appearance, ...preset })).forEach(([key, value]) => change(key, value));
  };
  const options = appearanceSizes.map((value, index) => ({ value, label: ['Extra small', 'Small', 'Medium', 'Large', 'Extra large'][index] }));
  return <SettingsSection title="Interface Appearance" description="Shared administrator colors and sizing.">
    <div className="appearance-presets" role="group" aria-label="Color presets">
      {Object.entries(colorPresets).map(([name, preset]) => <button type="button" className="ghost appearance-swatch"
        key={name} title={`${name} colors`} aria-label={`${name} colors`} onClick={() => applyPreset(preset)}>
        <span style={{ backgroundColor: preset.button }} />{name}
      </button>)}
    </div>
    <div className="settings-field-grid">
      {[['Accent', 'Accent'], ['AccentStrong', 'Accent highlight'], ['Button', 'Button background'], ['ButtonHover', 'Button hover'], ['ButtonText', 'Button text']].map(([key, label]) => {
        const name = `appearance${key}`;
        return <label className="settings-field appearance-color-field" key={name}>
          <span className="settings-field-label">{label}</span>
          <span className="appearance-color-input">
            <input type="text" required pattern="#[0-9A-Fa-f]{6}" maxLength={7} aria-label={`${label} hex color`}
              value={String(values[name])} onChange={(event) => change(name, event.target.value)} />
            <input type="color" aria-label={`Choose ${label.toLowerCase()}`}
              value={/^#[0-9a-f]{6}$/i.test(String(values[name])) ? String(values[name]) : '#000000'}
              onChange={(event) => change(name, event.target.value.toUpperCase())} />
          </span>
        </label>;
      })}
      <SettingsField field={{ name: 'appearanceControlSize', label: 'Button size', type: 'select', options }} values={values} onChange={change} />
      <SettingsField field={{ name: 'appearanceCardSize', label: 'File card size', type: 'select', options }} values={values} onChange={change} />
    </div>
    {valid && <div className="appearance-preview" style={style}>
      <div className="appearance-preview-actions"><button type="button">Create</button><button type="button" className="ghost">Cancel</button>
        <button type="button" className="ghost icon-button" aria-label="Sample directory"><i className="fas fa-folder" aria-hidden="true" /></button></div>
      <div className="browser-grid">
        <article className="browser-card"><div className="card-thumb"><i className="fas fa-file-image" aria-hidden="true" /></div><div className="card-body"><span className="card-name">Example.png</span><span className="muted">Image</span></div></article>
      </div>
      <p className="muted">Button contrast: {contrastRatio(appearance.button, appearance.buttonText).toFixed(2)}:1
        {(contrastRatio(appearance.button, appearance.buttonText) < 4.5 || contrastRatio(appearance.buttonHover, appearance.buttonText) < 4.5) && ' - Low text contrast. Consider another color combination.'}</p>
    </div>}
    <button type="button" className="ghost icon-text-button" onClick={() => {
      Object.entries(appearanceValues({ ...colorPresets.endervault, controlSize: 'medium', cardSize: 'medium' })).forEach(([key, value]) => change(key, value));
    }}><i className="fas fa-arrow-rotate-left" aria-hidden="true" /><span>Reset appearance</span></button>
  </SettingsSection>;
}

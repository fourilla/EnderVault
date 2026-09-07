import { appearanceSizes, appearanceColorFields, colorPresets, colorPresetLabels, type Appearance } from '../../shared/appearance/presets';
import type { FormValues, FormValue } from '../types';
import { SettingsField, SettingsSection } from './SettingsControls';
import { AppearancePreview } from './AppearancePreview';

export const appearanceValues = (appearance: Appearance): FormValues => Object.fromEntries(
  Object.entries(appearance).map(([key, value]) => [`appearance${key[0].toUpperCase()}${key.slice(1)}`, value]),
);
export const appearanceFromValues = (values: FormValues): Appearance => ({
  ...Object.fromEntries(Object.keys(appearanceColorFields).map((key) => [key, String(values[`appearance${key[0].toUpperCase()}${key.slice(1)}`])])) as Pick<Appearance, keyof typeof appearanceColorFields>,
  controlSize: values.appearanceControlSize as Appearance['controlSize'],
  cardSize: values.appearanceCardSize as Appearance['cardSize'],
});

export function AppearanceFields({ values, change }: { values: FormValues; change: (name: string, value: FormValue) => void }) {
  const appearance = appearanceFromValues(values);
  const valid = (Object.keys(appearanceColorFields) as (keyof typeof appearanceColorFields)[]).map((key) => appearance[key])
    .every((value) => /^#[0-9a-f]{6}$/i.test(value));
  const applyPreset = (preset: typeof colorPresets[keyof typeof colorPresets]) => {
    Object.entries(appearanceValues({ ...appearance, ...preset })).forEach(([key, value]) => change(key, value));
  };
  const options = appearanceSizes.map((value, index) => ({ value, label: ['Extra small', 'Small', 'Medium', 'Large', 'Extra large'][index] }));
  return <SettingsSection title="Interface Appearance" description="Shared interface colors and sizing.">
    <div className="appearance-presets" role="group" aria-label="Color presets">
      {Object.entries(colorPresets).map(([name, preset]) => <button type="button" className="ghost appearance-swatch"
        key={name} title={`${colorPresetLabels[name as keyof typeof colorPresets]} colors`} aria-label={`${colorPresetLabels[name as keyof typeof colorPresets]} colors`} onClick={() => applyPreset(preset)}>
        <span style={{ backgroundColor: preset.background }} /><span style={{ backgroundColor: preset.button }} />{colorPresetLabels[name as keyof typeof colorPresets]}
      </button>)}
    </div>
    <div className="appearance-editor">
    <AppearancePreview appearance={appearance} valid={valid} />
    <div className="settings-field-grid appearance-editor-fields">
      {Object.entries(appearanceColorFields).map(([key, label]) => {
        const name = `appearance${key[0].toUpperCase()}${key.slice(1)}`;
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
    </div>
    <button type="button" className="ghost icon-text-button" onClick={() => {
      Object.entries(appearanceValues({ ...colorPresets.endervault, controlSize: 'medium', cardSize: 'medium' })).forEach(([key, value]) => change(key, value));
    }}><i className="fas fa-arrow-rotate-left" aria-hidden="true" /><span>Reset appearance</span></button>
  </SettingsSection>;
}

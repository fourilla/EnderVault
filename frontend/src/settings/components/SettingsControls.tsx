import { useState, type ChangeEvent, type ReactNode } from 'react';
import type { FormValue, FormValues } from '../types';

export type FieldOption = { value: string; label: string };

export type FieldDefinition = {
  name: string;
  label: string;
  type?: 'text' | 'number' | 'select' | 'textarea';
  description?: string;
  min?: string;
  max?: string;
  step?: string;
  minLength?: number;
  maxLength?: number;
  autoComplete?: string;
  unit?: string;
  restartRequired?: boolean;
  options?: FieldOption[];
  disabled?: boolean;
};

type ValuesProps = {
  values: FormValues;
  onChange: (name: string, value: FormValue) => void;
};

export function SettingsSaveBar({ dirty, saving, onSave, onDiscard }: {
  dirty: boolean;
  saving: boolean;
  onSave: () => void;
  onDiscard: () => void;
}) {
  return (
    <div className={`settings-save-bar${dirty ? ' is-dirty' : ''}`} aria-live="polite">
      <span className="settings-save-state">
        <i className="settings-save-indicator" aria-hidden="true" />
        {dirty ? 'Unsaved changes' : 'No unsaved changes'}
      </span>
      <span className="settings-save-actions">
        <button className="ghost" type="button" disabled={!dirty || saving} onClick={onDiscard}>Discard</button>
        <button className="primary" type="button" disabled={!dirty || saving} onClick={onSave}>
          {saving ? 'Saving...' : 'Save settings'}
        </button>
      </span>
    </div>
  );
}

export function SettingsSection({ id, title, description, action, children }: {
  id?: string;
  title: string;
  description: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section id={id} className="settings-detail-section">
      <header className="settings-subsection-heading">
        <div>
          <h3>{title}</h3>
          <p>{description}</p>
        </div>
        {action}
      </header>
      {children}
    </section>
  );
}

export function SettingsField({ field, values, onChange }: { field: FieldDefinition } & ValuesProps) {
  const value = String(values[field.name] ?? '');
  const update = (event: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    onChange(field.name, event.target.value);
  const label = (
    <span className="settings-field-label">
      <span>{field.label}</span>
      {field.restartRequired && <em className="settings-restart-note">Restart required</em>}
    </span>
  );

  let control: ReactNode;
  if (field.type === 'select') {
    control = (
      <select name={field.name} value={value} disabled={field.disabled} onChange={update}>
        {field.options?.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
      </select>
    );
  } else if (field.type === 'textarea') {
    control = <textarea name={field.name} rows={3} value={value} disabled={field.disabled} onChange={update} />;
  } else {
    const input = (
      <input
        name={field.name}
        type={field.type ?? 'text'}
        value={value}
        min={field.min}
        max={field.max}
        step={field.step}
        minLength={field.minLength}
        maxLength={field.maxLength}
        autoComplete={field.autoComplete}
        disabled={field.disabled}
        onChange={update}
      />
    );
    control = field.unit
      ? <div className="settings-unit-field">{input}<span>{field.unit}</span></div>
      : input;
  }

  return (
    <label className={field.disabled ? 'settings-dependent-locked' : undefined}>
      {label}
      {control}
      {field.description && <small>{field.description}</small>}
    </label>
  );
}

export function SettingsToggle({ name, label, description, values, onChange, disabled = false }: {
  name: string;
  label: string;
  description?: string;
  disabled?: boolean;
} & ValuesProps) {
  return (
    <label className={`settings-inline-toggle${disabled ? ' settings-dependent-locked' : ''}`}>
      <span>
        <strong>{label}</strong>
        {description && <small>{description}</small>}
      </span>
      <input
        className="settings-switch-input"
        name={name}
        type="checkbox"
        checked={Boolean(values[name])}
        disabled={disabled}
        onChange={(event) => onChange(name, event.target.checked)}
      />
    </label>
  );
}

export function SettingsPasswordField({ name, label, autoComplete, values, onChange, disabled = false }: {
  name: string;
  label: string;
  autoComplete: string;
  disabled?: boolean;
} & ValuesProps) {
  const [visible, setVisible] = useState(false);
  const value = String(values[name] ?? '');
  return (
    <label className={disabled ? 'settings-dependent-locked' : undefined}>
      <span>{label}</span>
      <span className="password-field">
        <input
          type={visible ? 'text' : 'password'}
          name={name}
          value={value}
          autoComplete={autoComplete}
          disabled={disabled}
          onChange={(event) => onChange(name, event.target.value)}
        />
        {value && (
          <button
            className="ghost icon-button password-toggle"
            type="button"
            title={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
            aria-label={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
            aria-pressed={visible}
            onClick={() => setVisible((current) => !current)}
          >
            <i className={`fas ${visible ? 'fa-eye-slash' : 'fa-eye'}`} aria-hidden="true" />
          </button>
        )}
      </span>
    </label>
  );
}

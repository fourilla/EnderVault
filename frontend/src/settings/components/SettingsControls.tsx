import { useState, type ChangeEvent, type ReactNode } from 'react';
import type { FormValue, FormValues } from '../types';
import { OverflowMarquee } from './OverflowMarquee';

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
  if (!dirty) return null;

  return (
    <div className="settings-save-bar is-dirty" aria-live="polite">
      <span className="settings-save-state">
        <i className="settings-save-indicator" aria-hidden="true" />
        Unsaved changes
      </span>
      <span className="settings-save-actions">
        <button className="ghost" type="button" disabled={saving} onClick={onDiscard}>Discard</button>
        <button className="primary" type="button" disabled={saving} onClick={onSave}>
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
    control = (
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
  }

  return (
    <label className={`settings-control-row settings-field-row${field.type === 'textarea' ? ' is-multiline' : ''}${field.disabled ? ' settings-dependent-locked' : ''}`}>
      <span className="settings-control-copy">
        {label}
        {field.description && <small><OverflowMarquee text={field.description} /></small>}
      </span>
      <span className="settings-control-area">
        <span className="settings-unit-field">
          {control}
          <span className={`settings-unit-slot${field.unit ? '' : ' is-empty'}`} aria-hidden="true">
            {field.unit || '\u00a0'}
          </span>
        </span>
      </span>
    </label>
  );
}

export function SettingsDirectoryField({ name, label, description, values, onChange, disabled = false }: {
  name: string;
  label: string;
  description?: string;
  disabled?: boolean;
} & ValuesProps) {
  return (
    <label className={`settings-control-row settings-field-row${disabled ? ' settings-dependent-locked' : ''}`}>
      <span className="settings-control-copy">
        <span className="settings-field-label"><span>{label}</span></span>
        {description && <small><OverflowMarquee text={description} /></small>}
      </span>
      <span className="settings-control-area">
        <span className="settings-unit-field">
          <span className="settings-directory-input">
            <input
              id={name}
              name={name}
              type="text"
              value={String(values[name] ?? '')}
              disabled={disabled}
              onChange={(event) => onChange(name, event.target.value)}
            />
            <button
              className="ghost icon-button"
              type="button"
              disabled={disabled}
              data-directory-picker-open
              data-directory-picker-target={name}
              title="Browse directories"
              aria-label={`Browse ${label.toLowerCase()}`}
            >
              <i className="fas fa-folder-open" aria-hidden="true" />
            </button>
          </span>
          <span className="settings-unit-slot is-empty" aria-hidden="true">{'\u00a0'}</span>
        </span>
      </span>
    </label>
  );
}

export function SettingsToggle({ name, label, description, restartRequired = false, values, onChange, disabled = false }: {
  name: string;
  label: string;
  description?: string;
  restartRequired?: boolean;
  disabled?: boolean;
} & ValuesProps) {
  return (
    <label className={`settings-control-row settings-inline-toggle${disabled ? ' settings-dependent-locked' : ''}`}>
      <span className="settings-control-copy">
        <span className="settings-field-label">
          <span>{label}</span>
          {restartRequired && <em className="settings-restart-note">Restart required</em>}
        </span>
        {description && <small><OverflowMarquee text={description} /></small>}
      </span>
      <span className="settings-control-area">
        <span className="settings-unit-field">
          <span className="settings-switch-control">
            <input
              className="settings-switch-input"
              name={name}
              type="checkbox"
              checked={Boolean(values[name])}
              disabled={disabled}
              onChange={(event) => onChange(name, event.target.checked)}
            />
          </span>
          <span className="settings-unit-slot is-empty" aria-hidden="true">{'\u00a0'}</span>
        </span>
      </span>
    </label>
  );
}

export function SettingsPasswordField({ name, label, autoComplete, values, onChange, disabled = false, description }: {
  name: string;
  label: string;
  autoComplete: string;
  disabled?: boolean;
  description?: string;
} & ValuesProps) {
  const [visible, setVisible] = useState(false);
  const value = String(values[name] ?? '');
  return (
    <label className={`settings-control-row settings-field-row${disabled ? ' settings-dependent-locked' : ''}`}>
      <span className="settings-control-copy">
        <span className="settings-field-label"><span>{label}</span></span>
        {description && <small><OverflowMarquee text={description} /></small>}
      </span>
      <span className="settings-control-area">
        <span className="settings-unit-field">
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
          <span className="settings-unit-slot is-empty" aria-hidden="true">{'\u00a0'}</span>
        </span>
      </span>
    </label>
  );
}

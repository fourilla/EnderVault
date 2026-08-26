import { useCallback, useMemo } from 'react';
import { SettingsField, SettingsSaveBar, SettingsSection, SettingsToggle } from '../components/SettingsControls';
import { useSettingsEditor } from '../hooks/useSettingsEditor';
import type { AdvancedSettingsSnapshot, FormValues } from '../types';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';

const toValues = (snapshot: AdvancedSettingsSnapshot): FormValues => Object.fromEntries(
  snapshot.groups.flatMap((group) => group.fields.map((field) => [
    field.name,
    field.type === 'boolean' ? field.value === 'true' : field.value,
  ])),
);

function AdvancedSettingsEditor({ snapshot, onDirtyChange }: {
  snapshot: AdvancedSettingsSnapshot;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const sensitiveNames = useMemo(() => new Set(snapshot.groups
    .flatMap((group) => group.fields)
    .filter((field) => field.confirmationRequired)
    .map((field) => field.name)), [snapshot]);
  const confirmSave = useCallback(async (values: FormValues, baseline: FormValues) => {
    const sensitiveChanged = [...sensitiveNames].some((name) => values[name] !== baseline[name]);
    if (!sensitiveChanged) return true;
    return window.EnderVault?.askConfirmation?.({
      title: 'Confirm security settings',
      message: 'Trusted proxy or passkey identity changes can affect client IP validation and sign-in. Save these changes?',
      confirmLabel: 'Save settings',
      danger: true,
    }) ?? Promise.resolve(window.confirm('These security settings can affect sign-in. Save these changes?'));
  }, [sensitiveNames]);
  const editor = useSettingsEditor(toValues(snapshot), '/api/v1/settings/advanced', onDirtyChange, confirmSave);

  return (
    <form className="general-settings-form advanced-settings-form settings-spa-form" onSubmit={(event) => { event.preventDefault(); void editor.save(); }}>
      <SettingsSaveBar {...editor} onSave={() => void editor.save()} onDiscard={editor.discard} />
      {snapshot.groups.map((group) => (
        <SettingsSection key={group.id} id={group.id} title={group.title} description={group.description}>
          <div className="settings-field-grid advanced-settings-grid">
            {group.fields.map((field) => field.type === 'boolean' ? (
              <SettingsToggle
                key={field.name}
                name={field.name}
                label={field.label}
                description={`${field.description}${field.restartRequired ? ' Restart required.' : ''}`}
                values={editor.values}
                onChange={editor.change}
              />
            ) : (
              <SettingsField
                key={field.name}
                field={{
                  name: field.name,
                  label: field.label,
                  type: field.type,
                  description: field.description,
                  min: field.min || undefined,
                  max: field.max || undefined,
                  step: field.step || undefined,
                  unit: field.unit || undefined,
                  restartRequired: field.restartRequired,
                  options: field.choices,
                }}
                values={editor.values}
                onChange={editor.change}
              />
            ))}
          </div>
        </SettingsSection>
      ))}
      <SettingsSection id="deployment" title="Deployment" description="Effective values managed outside the running web UI.">
        <dl className="settings-readonly-list">
          {snapshot.deployment.map((item) => (
            <div key={item.label}>
              <dt>{item.label}</dt>
              <dd><code>{item.value}</code><small>{item.description}</small></dd>
            </div>
          ))}
        </dl>
      </SettingsSection>
      <p className="settings-config-path">Stored in <code>{snapshot.configPath}</code></p>
    </form>
  );
}

export function AdvancedSettings({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error } = useSettingsSnapshot<AdvancedSettingsSnapshot>('/api/v1/settings/advanced');

  if (error) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading advanced settings...</div>;
  return <AdvancedSettingsEditor snapshot={snapshot} onDirtyChange={onDirtyChange} />;
}

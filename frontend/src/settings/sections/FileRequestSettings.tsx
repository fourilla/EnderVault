import { SettingsField, SettingsSaveBar, SettingsSection, SettingsToggle, type FieldDefinition } from '../components/SettingsControls';
import { useSettingsEditor } from '../hooks/useSettingsEditor';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';
import type { FileRequestSettingsSnapshot, FormValues } from '../types';

const toValues = (snapshot: FileRequestSettingsSnapshot): FormValues => ({
  enabled: snapshot.enabled,
  defaultExpirationDays: String(snapshot.defaultExpirationDays),
  defaultMaxFileSizeGb: snapshot.defaultMaxFileSizeGb,
  defaultMaxTotalGb: snapshot.defaultMaxTotalGb,
  defaultMaxFiles: String(snapshot.defaultMaxFiles),
  defaultUploaderNamePolicy: snapshot.defaultUploaderNamePolicy.toLowerCase(),
  customTokenEnabled: snapshot.customTokenEnabled,
  customTokenMinLength: String(snapshot.customTokenMinLength),
  customTokenMaxLength: String(snapshot.customTokenMaxLength),
  randomTokenBytes: String(snapshot.randomTokenBytes),
  maxConcurrentUploadsPerRequest: String(snapshot.maxConcurrentUploadsPerRequest),
  rateLimitEnabled: snapshot.rateLimitEnabled,
  rateLimitMaxAdmissions: String(snapshot.rateLimitMaxAdmissions),
  rateLimitWindowSeconds: String(snapshot.rateLimitWindowSeconds),
  accessLogDedupSeconds: String(snapshot.accessLogDedupSeconds),
});

const fields: FieldDefinition[] = [
  { name: 'defaultExpirationDays', label: 'Default expiration', type: 'number', min: '0', max: '365', unit: 'days', description: 'Use 0 for no expiration.' },
  { name: 'defaultUploaderNamePolicy', label: 'Uploader name', type: 'select', options: [
    { value: 'required', label: 'Required' }, { value: 'optional', label: 'Optional' }, { value: 'none', label: 'Do not collect' },
  ] },
  { name: 'defaultMaxFileSizeGb', label: 'Maximum file size', type: 'number', min: '0.001', max: '20', step: '0.001', unit: 'GiB' },
  { name: 'defaultMaxTotalGb', label: 'Total quota', type: 'number', min: '0.001', max: '100', step: '0.001', unit: 'GiB' },
  { name: 'defaultMaxFiles', label: 'Maximum files', type: 'number', min: '1', max: '1000' },
];

function Editor({ snapshot, onDirtyChange }: { snapshot: FileRequestSettingsSnapshot; onDirtyChange: (dirty: boolean) => void }) {
  const editor = useSettingsEditor(toValues(snapshot), '/api/v1/settings/file-requests', onDirtyChange);
  const enabled = Boolean(editor.values.enabled);
  const customTokenEnabled = enabled && Boolean(editor.values.customTokenEnabled);
  const rateLimitEnabled = enabled && Boolean(editor.values.rateLimitEnabled);
  const field = (definition: FieldDefinition, disabled = !enabled) => (
    <SettingsField key={definition.name} field={{ ...definition, disabled }} values={editor.values} onChange={editor.change} />
  );

  return (
    <form className="general-settings-form settings-spa-form" onSubmit={(event) => { event.preventDefault(); void editor.save(); }}>
      <SettingsSaveBar {...editor} onSave={() => void editor.save()} onDiscard={editor.discard} />
      <SettingsSection title="Availability" description="Controls whether public file request links can accept uploads."
        action={<label className="settings-heading-switch"><span>Enabled</span><input className="settings-switch-input" type="checkbox" checked={enabled} onChange={(event) => editor.change('enabled', event.target.checked)} /></label>}>
        <p className="settings-section-note">Existing request metadata remains available when uploads are disabled.</p>
      </SettingsSection>
      <SettingsSection title="Request Defaults" description="Initial limits used when an administrator creates a request link.">
        <div className="settings-field-grid settings-dependent-fields">{fields.map((definition) => field(definition))}</div>
      </SettingsSection>
      <SettingsSection title="Token Policy" description="Controls generated and custom capability tokens."
        action={<label className={`settings-heading-switch${enabled ? '' : ' settings-dependent-locked'}`}><span>Custom tokens</span><input className="settings-switch-input" type="checkbox" checked={Boolean(editor.values.customTokenEnabled)} disabled={!enabled} onChange={(event) => editor.change('customTokenEnabled', event.target.checked)} /></label>}>
        <div className="settings-field-grid settings-dependent-fields">
          {field({ name: 'randomTokenBytes', label: 'Random token bytes', type: 'number', min: '8', max: '64' })}
          <div className="settings-child-group">
            {field({ name: 'customTokenMinLength', label: 'Minimum custom token length', type: 'number', min: '1', max: '256' }, !customTokenEnabled)}
            {field({ name: 'customTokenMaxLength', label: 'Maximum custom token length', type: 'number', min: '1', max: '256' }, !customTokenEnabled)}
          </div>
        </div>
      </SettingsSection>
      <SettingsSection title="Admission Control" description="Limits concurrent uploads and repeated admission attempts from one IP."
        action={<label className={`settings-heading-switch${enabled ? '' : ' settings-dependent-locked'}`}><span>Rate limit</span><input className="settings-switch-input" type="checkbox" checked={Boolean(editor.values.rateLimitEnabled)} disabled={!enabled} onChange={(event) => editor.change('rateLimitEnabled', event.target.checked)} /></label>}>
        <div className="settings-field-grid settings-dependent-fields">
          {field({ name: 'maxConcurrentUploadsPerRequest', label: 'Concurrent uploads per request', type: 'number', min: '1', max: '2' })}
          {field({ name: 'accessLogDedupSeconds', label: 'Access log deduplication', type: 'number', min: '0', max: '86400', unit: 'seconds', description: 'Use 0 to log every accepted access.' })}
          <div className="settings-child-group">
            {field({ name: 'rateLimitMaxAdmissions', label: 'Maximum admissions', type: 'number', min: '1', max: '100000' }, !rateLimitEnabled)}
            {field({ name: 'rateLimitWindowSeconds', label: 'Admission window', type: 'number', min: '1', max: '86400', unit: 'seconds' }, !rateLimitEnabled)}
          </div>
        </div>
      </SettingsSection>
      <p className="settings-config-path">Stored in <code>{snapshot.configPath}</code></p>
    </form>
  );
}

export function FileRequestSettings({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error } = useSettingsSnapshot<FileRequestSettingsSnapshot>('/api/v1/settings/file-requests');
  if (error) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading file request settings...</div>;
  return <Editor snapshot={snapshot} onDirtyChange={onDirtyChange} />;
}

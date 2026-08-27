import { useCallback, type CSSProperties } from 'react';
import type { FormValue, FormValues, GeneralSettingsSnapshot } from '../types';
import {
  SettingsDirectoryField,
  SettingsField,
  SettingsSaveBar,
  SettingsSection,
  SettingsToggle,
  type FieldDefinition,
} from '../components/SettingsControls';
import { useSettingsEditor } from '../hooks/useSettingsEditor';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';

const browserFields: FieldDefinition[] = [
  { name: 'defaultView', label: 'Default view', type: 'select', options: [
    { value: 'table', label: 'Table' }, { value: 'grid', label: 'Grid' },
  ] },
  { name: 'defaultSort', label: 'Default sort', type: 'select', options: [
    { value: 'name', label: 'Name' }, { value: 'size', label: 'Size' },
    { value: 'modified', label: 'Modified' }, { value: 'type', label: 'Type' },
  ] },
  { name: 'defaultDirection', label: 'Default direction', type: 'select', options: [
    { value: 'asc', label: 'Ascending' }, { value: 'desc', label: 'Descending' },
  ] },
  { name: 'defaultPageSize', label: 'Default page size', type: 'number', min: '1', max: '1000' },
];

const textToolFields: FieldDefinition[] = [
  { name: 'textAutoLoadMaxMib', label: 'Text auto-load limit', type: 'number', min: '0.001', step: 'any', unit: 'MiB' },
  { name: 'textManualLoadMaxMib', label: 'Text manual-load limit', type: 'number', min: '0.001', step: 'any', unit: 'MiB' },
  { name: 'textDraftRetentionHours', label: 'Text draft retention', type: 'number', min: '1', unit: 'hours' },
  { name: 'textDraftCleanupIntervalMinutes', label: 'Draft cleanup interval', type: 'number', min: '1', step: 'any', unit: 'minutes' },
  { name: 'textDraftLeaseSeconds', label: 'Text editor lease', type: 'number', min: '30', unit: 'seconds' },
];

const comicToolFields: FieldDefinition[] = [
  { name: 'comicMaxPages', label: 'Comic max pages', type: 'number', min: '1', max: '50000' },
  { name: 'comicPageMaxMib', label: 'Comic page limit', type: 'number', min: '0.001', step: 'any', unit: 'MiB' },
  { name: 'comicInfoMaxKib', label: 'Comic info limit', type: 'number', min: '1', step: 'any', unit: 'KiB' },
];

const toValues = (snapshot: GeneralSettingsSnapshot): FormValues => ({
  defaultView: snapshot.browser.defaultView,
  defaultSort: snapshot.browser.defaultSort,
  defaultDirection: snapshot.browser.defaultDirection,
  defaultPageSize: String(snapshot.browser.defaultPageSize),
  stickyNoteBackgroundColor: snapshot.stickyNotes.backgroundColor,
  stickyNoteBorderColor: snapshot.stickyNotes.borderColor,
  stickyNoteTextColor: snapshot.stickyNotes.textColor,
  defaultConflictPolicy: snapshot.storage.defaultConflictPolicy,
  recentMaxItems: String(snapshot.recent.maxItems),
  recordDirectories: snapshot.recent.recordDirectories,
  trashRetentionDays: String(snapshot.trash.retentionDays),
  trashCleanupOnStartup: snapshot.trash.cleanupOnStartup,
  trashCleanupIntervalMinutes: snapshot.trash.cleanupIntervalMinutes,
  textAutoLoadMaxMib: snapshot.fileTools.textAutoLoadMaxMib,
  textManualLoadMaxMib: snapshot.fileTools.textManualLoadMaxMib,
  textDraftRetentionHours: String(snapshot.fileTools.textDraftRetentionHours),
  textDraftCleanupIntervalMinutes: snapshot.fileTools.textDraftCleanupIntervalMinutes,
  textDraftLeaseSeconds: String(snapshot.fileTools.textDraftLeaseSeconds),
  comicMaxPages: String(snapshot.fileTools.comicMaxPages),
  comicPageMaxMib: snapshot.fileTools.comicPageMaxMib,
  comicInfoMaxKib: snapshot.fileTools.comicInfoMaxKib,
  remoteEnabled: snapshot.remoteDownload.enabled,
  remoteDirectEnabled: snapshot.remoteDownload.directEnabled,
  remoteBlockPrivateNetworks: snapshot.remoteDownload.blockPrivateNetworks,
  remoteAllowedPorts: snapshot.remoteDownload.allowedPorts,
  remoteConnectTimeoutSeconds: String(snapshot.remoteDownload.connectTimeoutSeconds),
  remoteResponseTimeoutSeconds: String(snapshot.remoteDownload.responseTimeoutSeconds),
  remoteMaxRedirects: String(snapshot.remoteDownload.maxRedirects),
  remoteMaxFileSizeGib: snapshot.remoteDownload.maxFileSizeGib,
  remoteHistoryLimit: String(snapshot.remoteDownload.historyLimit),
  remoteWorkerThreads: String(snapshot.remoteDownload.workerThreads),
  remoteMaxRetries: String(snapshot.remoteDownload.maxRetries),
  remoteSkipInspectByDefault: snapshot.remoteDownload.skipInspectByDefault,
  remoteDefaultTargetDirectory: snapshot.remoteDownload.defaultTargetDirectory,
});

const relativeLuminance = (hex: string) => {
  const channels = [1, 3, 5].map((offset) => Number.parseInt(hex.slice(offset, offset + 2), 16) / 255);
  const linear = channels.map((channel) => channel <= 0.04045
    ? channel / 12.92
    : ((channel + 0.055) / 1.055) ** 2.4);
  return (0.2126 * linear[0]) + (0.7152 * linear[1]) + (0.0722 * linear[2]);
};

const contrastRatio = (first: string, second: string) => {
  const brighter = Math.max(relativeLuminance(first), relativeLuminance(second));
  const darker = Math.min(relativeLuminance(first), relativeLuminance(second));
  return (brighter + 0.05) / (darker + 0.05);
};

export type GeneralSettingsScope = 'appearance' | 'files' | 'file-tools' | 'remote-downloads';

function GeneralSettingsEditor({ snapshot, scope, onDirtyChange }: {
  snapshot: GeneralSettingsSnapshot;
  scope: GeneralSettingsScope;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const applyStickyNoteTheme = useCallback((values: FormValues) => {
    document.documentElement.style.setProperty('--sticky-note-bg', String(values.stickyNoteBackgroundColor));
    document.documentElement.style.setProperty('--sticky-note-border', String(values.stickyNoteBorderColor));
    document.documentElement.style.setProperty('--sticky-note-text', String(values.stickyNoteTextColor));
  }, []);
  const editor = useSettingsEditor(
    toValues(snapshot),
    '/api/v1/settings/general',
    onDirtyChange,
    undefined,
    undefined,
    applyStickyNoteTheme,
  );
  const remoteEnabled = Boolean(editor.values.remoteEnabled);
  const directEnabled = remoteEnabled && Boolean(editor.values.remoteDirectEnabled);
  const stickyContrast = contrastRatio(
    String(editor.values.stickyNoteBackgroundColor),
    String(editor.values.stickyNoteTextColor),
  );
  const stickyStyle = {
    '--sticky-note-preview-bg': editor.values.stickyNoteBackgroundColor,
    '--sticky-note-preview-border': editor.values.stickyNoteBorderColor,
    '--sticky-note-preview-text': editor.values.stickyNoteTextColor,
  } as CSSProperties;
  const field = (definition: FieldDefinition) => (
    <SettingsField key={definition.name} field={definition} values={editor.values} onChange={editor.change} />
  );

  const colorField = (name: string, label: string) => (
    <label className="sticky-note-color-field">
      <span>{label}</span>
      <span className="sticky-note-color-control">
        <output>{String(editor.values[name])}</output>
        <input
          type="color"
          name={name}
          value={String(editor.values[name])}
          aria-label={`Choose ${label.toLowerCase()} color`}
          onChange={(event) => editor.change(name, event.target.value.toUpperCase())}
        />
      </span>
    </label>
  );

  return (
    <form className="general-settings-form settings-spa-form" onSubmit={(event) => { event.preventDefault(); void editor.save(); }}>
      <SettingsSaveBar {...editor} onSave={() => void editor.save()} onDiscard={editor.discard} />

      {scope === 'appearance' && <SettingsSection title="Browser Defaults" description="Used when the browser has no saved preference cookie.">
        <div className="settings-field-grid">{browserFields.map(field)}</div>
      </SettingsSection>}

      {scope === 'appearance' && <SettingsSection id="sticky-note-theme" title="Sticky Notes" description="Sets one shared note theme for every administrator device.">
        <div className="sticky-note-theme-editor">
          <div className="sticky-note-theme-preview-stage">
            <article className="sticky-note-theme-preview" style={stickyStyle}>
              <header><strong>Project note</strong><i className="fas fa-note-sticky" aria-hidden="true" /></header>
              <p>Keep the next maintenance task visible without leaving this page.</p>
              <footer>Saved</footer>
            </article>
          </div>
          <div className="sticky-note-theme-fields">
            {colorField('stickyNoteBackgroundColor', 'Background')}
            {colorField('stickyNoteBorderColor', 'Border')}
            {colorField('stickyNoteTextColor', 'Text')}
            <p className={`sticky-note-theme-contrast${stickyContrast < 4.5 ? ' is-warning' : ''}`} role="status">
              Text contrast {stickyContrast.toFixed(2)}:1.
              {stickyContrast < 4.5 && ' A ratio of 4.5:1 or higher is recommended.'}
            </p>
            <button className="ghost icon-text-button sticky-note-theme-reset" type="button" onClick={() => {
              editor.change('stickyNoteBackgroundColor', snapshot.stickyNotes.defaultBackgroundColor);
              editor.change('stickyNoteBorderColor', snapshot.stickyNotes.defaultBorderColor);
              editor.change('stickyNoteTextColor', snapshot.stickyNotes.defaultTextColor);
            }}>
              <i className="fas fa-arrow-rotate-left" aria-hidden="true" /><span>Reset colors</span>
            </button>
          </div>
        </div>
      </SettingsSection>}

      {scope === 'files' && <SettingsSection title="File Conflicts" description="Default behavior when a file operation finds the same name.">
        <div className="settings-field-grid">
          {field({ name: 'defaultConflictPolicy', label: 'Default conflict policy', type: 'select', options: [
            { value: 'cancel', label: 'Cancel' },
            { value: 'rename', label: 'Rename automatically' },
            { value: 'overwrite', label: 'Overwrite files' },
          ] })}
        </div>
      </SettingsSection>}

      {scope === 'files' && <SettingsSection
        title="Recent Items"
        description="Controls the virtual Recent page and recording policy."
        action={<label className="settings-heading-switch"><span>Record directories</span><input className="settings-switch-input" type="checkbox" checked={Boolean(editor.values.recordDirectories)} onChange={(event) => editor.change('recordDirectories', event.target.checked)} /></label>}
      >
        <div className="settings-field-grid">{field({ name: 'recentMaxItems', label: 'Max items', type: 'number', min: '1', max: '1000' })}</div>
      </SettingsSection>}

      {scope === 'files' && <SettingsSection
        title="Trash"
        description="Retention applies to newly trashed items; scheduler timing may require restart."
        action={<label className="settings-heading-switch"><span>Cleanup on startup</span><input className="settings-switch-input" type="checkbox" checked={Boolean(editor.values.trashCleanupOnStartup)} onChange={(event) => editor.change('trashCleanupOnStartup', event.target.checked)} /></label>}
      >
        <div className="settings-field-grid">
          {field({ name: 'trashRetentionDays', label: 'Retention days', type: 'number', min: '1' })}
          {field({ name: 'trashCleanupIntervalMinutes', label: 'Cleanup interval', type: 'number', min: '1', step: 'any', unit: 'minutes', restartRequired: true })}
        </div>
      </SettingsSection>}

      {scope === 'file-tools' && <SettingsSection title="Text Tools" description="Loading thresholds, draft retention, and editor lease policy.">
        <div className="settings-field-grid">{textToolFields.map(field)}</div>
      </SettingsSection>}

      {scope === 'file-tools' && <SettingsSection title="Comic Tools" description="CBZ page-count, page-size, and metadata safety limits.">
        <div className="settings-field-grid">{comicToolFields.map(field)}</div>
      </SettingsSection>}

      {scope === 'remote-downloads' && <SettingsSection
        title="Availability"
        description="Controls the remote download subsystem without changing retained history."
        action={<label className="settings-heading-switch"><span>Enabled</span><input className="settings-switch-input" type="checkbox" checked={remoteEnabled} onChange={(event) => editor.change('remoteEnabled', event.target.checked)} /></label>}
      >
        <p className="settings-section-note">Existing jobs and history remain available while new downloads are disabled.</p>
      </SettingsSection>}

      {scope === 'remote-downloads' && <SettingsSection
        title="Direct URL Downloads"
        description="Network and inspection policy for HTTP(S) downloads."
      >
        <div className="settings-field-grid settings-dependent-fields">
          <SettingsToggle name="remoteDirectEnabled" label="Direct URL downloads" description="Allow downloads from exact URLs." values={editor.values} onChange={editor.change} disabled={!remoteEnabled} />
          <div className="settings-child-group">
            <SettingsToggle name="remoteBlockPrivateNetworks" label="Block private networks" description="Keep enabled to reduce SSRF risk." values={editor.values} onChange={editor.change} disabled={!directEnabled} />
            {[
              { name: 'remoteAllowedPorts', label: 'Allowed ports', disabled: !directEnabled },
              { name: 'remoteConnectTimeoutSeconds', label: 'Connect timeout', type: 'number' as const, min: '1', max: '3600', unit: 'seconds', disabled: !directEnabled },
              { name: 'remoteResponseTimeoutSeconds', label: 'Response timeout', type: 'number' as const, min: '1', max: '3600', unit: 'seconds', disabled: !directEnabled },
              { name: 'remoteMaxRedirects', label: 'Max redirects', type: 'number' as const, min: '0', max: '50', disabled: !directEnabled },
              { name: 'remoteMaxFileSizeGib', label: 'Maximum file size', type: 'number' as const, min: '0', step: 'any', unit: 'GiB', description: 'Use 0 for no application-level size limit.', disabled: !directEnabled },
            ].map(field)}
            <SettingsDirectoryField
              name="remoteDefaultTargetDirectory"
              label="Default destination"
              description="Vault-relative directory used when this browser has no remembered destination."
              values={editor.values}
              onChange={editor.change}
              disabled={!directEnabled}
            />
            <SettingsToggle name="remoteSkipInspectByDefault" label="Skip inspection by default" description="Send no metadata probe before confirmation and use one connection." values={editor.values} onChange={editor.change} disabled={!directEnabled} />
          </div>
        </div>
      </SettingsSection>}

      {scope === 'remote-downloads' && <SettingsSection title="Download Tasks" description="Concurrency, retry, and retained history policy.">
        <div className="settings-field-grid settings-dependent-fields">
          {[
            { name: 'remoteWorkerThreads', label: 'Worker threads', type: 'number' as const, min: '1', max: '8', unit: 'threads', restartRequired: true, disabled: !remoteEnabled },
            { name: 'remoteMaxRetries', label: 'Maximum retries', type: 'number' as const, min: '0', max: '5', disabled: !remoteEnabled },
            { name: 'remoteHistoryLimit', label: 'History limit', type: 'number' as const, min: '1', max: '1000', restartRequired: true, disabled: !remoteEnabled },
          ].map(field)}
        </div>
      </SettingsSection>}

      <p className="settings-config-path">Stored in <code>{snapshot.configPath}</code></p>
    </form>
  );
}

export function GeneralSettings({ scope, onDirtyChange }: { scope: GeneralSettingsScope; onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error } = useSettingsSnapshot<GeneralSettingsSnapshot>('/api/v1/settings/general');

  if (error) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading general settings...</div>;
  return <GeneralSettingsEditor snapshot={snapshot} scope={scope} onDirtyChange={onDirtyChange} />;
}

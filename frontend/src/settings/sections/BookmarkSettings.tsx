import { SettingsField, SettingsSaveBar, SettingsSection, SettingsToggle } from '../components/SettingsControls';
import { useSettingsEditor } from '../hooks/useSettingsEditor';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';
import type { BookmarkSettingsSnapshot, FormValues } from '../types';

const toValues = (snapshot: BookmarkSettingsSnapshot): FormValues => ({
  linkClickAction: snapshot.behavior.linkClickAction,
  metadataFetchEnabled: snapshot.metadata.metadataFetchEnabled,
  blockPrivateNetworks: snapshot.metadata.blockPrivateNetworks,
  allowedPorts: snapshot.metadata.allowedPorts,
  connectTimeoutSeconds: String(snapshot.metadata.connectTimeoutSeconds),
  responseTimeoutSeconds: String(snapshot.metadata.responseTimeoutSeconds),
  maxRedirects: String(snapshot.metadata.maxRedirects),
  htmlMaxKib: snapshot.metadata.htmlMaxKib,
  faviconMaxKib: snapshot.metadata.faviconMaxKib,
  faviconCacheDirectory: snapshot.cache.faviconCacheDirectory,
});

function Editor({ snapshot, onDirtyChange }: { snapshot: BookmarkSettingsSnapshot; onDirtyChange: (dirty: boolean) => void }) {
  const editor = useSettingsEditor(toValues(snapshot), '/api/v1/settings/bookmarks', onDirtyChange);
  const metadataEnabled = Boolean(editor.values.metadataFetchEnabled);
  const field = (name: string, label: string, options: Parameters<typeof SettingsField>[0]['field'] = { name, label }) => (
    <SettingsField key={name} field={{ ...options, name, label, disabled: options.disabled ?? !metadataEnabled }} values={editor.values} onChange={editor.change} />
  );

  return (
    <form className="general-settings-form settings-spa-form" onSubmit={(event) => { event.preventDefault(); void editor.save(); }}>
      <SettingsSaveBar {...editor} onSave={() => void editor.save()} onDiscard={editor.discard} />
      <SettingsSection title="Behavior" description="Controls how bookmark links behave in the list view.">
        <div className="settings-field-grid">
          <SettingsField field={{ name: 'linkClickAction', label: 'Link click action', type: 'select', options: [
            { value: 'open', label: 'Open link in a new tab' },
            { value: 'detail', label: 'Open bookmark detail page' },
          ] }} values={editor.values} onChange={editor.change} />
        </div>
      </SettingsSection>
      <SettingsSection title="Metadata Fetch" description="Fetch page titles and favicons for external HTTP(S) links."
        action={<label className="settings-heading-switch"><span>Enabled</span><input className="settings-switch-input" type="checkbox" checked={metadataEnabled} onChange={(event) => editor.change('metadataFetchEnabled', event.target.checked)} /></label>}>
        <div className="settings-field-grid settings-child-group">
          <SettingsToggle name="blockPrivateNetworks" label="Block private networks" description="Keep enabled to reduce SSRF risk against localhost and LAN addresses." values={editor.values} onChange={editor.change} disabled={!metadataEnabled} />
          {field('allowedPorts', 'Allowed ports')}
          {field('connectTimeoutSeconds', 'Connect timeout', { name: '', label: '', type: 'number', min: '1', max: '3600', unit: 'seconds' })}
          {field('responseTimeoutSeconds', 'Response timeout', { name: '', label: '', type: 'number', min: '1', max: '3600', unit: 'seconds' })}
          {field('maxRedirects', 'Max redirects', { name: '', label: '', type: 'number', min: '0', max: '50' })}
          {field('htmlMaxKib', 'HTML response limit', { name: '', label: '', type: 'number', min: '1', step: 'any', unit: 'KiB' })}
          {field('faviconMaxKib', 'Favicon response limit', { name: '', label: '', type: 'number', min: '1', step: 'any', unit: 'KiB' })}
        </div>
      </SettingsSection>
      <SettingsSection title="Favicon Cache" description="Cache location under the EnderVault metadata directory.">
        <div className="settings-field-grid">
          <SettingsField field={{ name: 'faviconCacheDirectory', label: 'Cache directory', restartRequired: true }} values={editor.values} onChange={editor.change} />
        </div>
      </SettingsSection>
      <p className="settings-config-path">Stored in <code>{snapshot.configPath}</code></p>
    </form>
  );
}

export function BookmarkSettings({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error } = useSettingsSnapshot<BookmarkSettingsSnapshot>('/api/v1/settings/bookmarks');
  if (error) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading bookmark settings...</div>;
  return <Editor snapshot={snapshot} onDirtyChange={onDirtyChange} />;
}

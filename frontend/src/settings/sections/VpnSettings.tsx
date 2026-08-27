import { SettingsField, SettingsSaveBar, SettingsSection, type FieldDefinition } from '../components/SettingsControls';
import { useSettingsEditor } from '../hooks/useSettingsEditor';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';
import type { FormValues, VpnSettingsSnapshot } from '../types';

const toValues = (snapshot: VpnSettingsSnapshot): FormValues => ({
  initialRoute: snapshot.initialRoute,
  enabled: snapshot.enabled,
  proxyHost: snapshot.proxyHost,
  proxyPort: String(snapshot.proxyPort),
  healthConnectTimeoutSeconds: snapshot.healthConnectTimeoutSeconds,
  tunnelHealthUrl: snapshot.tunnelHealthUrl,
  healthRequestTimeoutSeconds: snapshot.healthRequestTimeoutSeconds,
  healthCheckIntervalSeconds: snapshot.healthCheckIntervalSeconds,
});

function Editor({ snapshot, onDirtyChange }: { snapshot: VpnSettingsSnapshot; onDirtyChange: (dirty: boolean) => void }) {
  const editor = useSettingsEditor(toValues(snapshot), '/api/v1/settings/vpn', onDirtyChange);
  const enabled = Boolean(editor.values.enabled);
  const field = (definition: FieldDefinition) => (
    <SettingsField key={definition.name} field={{ ...definition, disabled: !enabled }} values={editor.values} onChange={editor.change} />
  );
  return (
    <form className="general-settings-form settings-spa-form" onSubmit={(event) => { event.preventDefault(); void editor.save(); }}>
      <SettingsSaveBar {...editor} onSave={() => void editor.save()} onDiscard={editor.discard} />
      <SettingsSection title="Outbound Policy" description="Controls which route EnderVault selects when the application starts.">
        <div className="settings-field-grid">
          <SettingsField
            field={{
              name: 'initialRoute',
              label: 'Initial outbound route',
              type: 'select',
              description: 'VPN required starts in VPN mode and rejects routed requests until the tunnel is healthy.',
              restartRequired: true,
              options: [
                { value: 'direct', label: 'Direct' },
                { value: 'vpn-required', label: 'VPN required' },
              ],
            }}
            values={editor.values}
            onChange={editor.change}
          />
        </div>
      </SettingsSection>
      <SettingsSection title="Proxy Connection" description="Provides the private HTTP proxy used when the global outbound route is set to VPN."
        action={<label className="settings-heading-switch"><span>Enabled</span><input className="settings-switch-input" type="checkbox" checked={enabled} onChange={(event) => editor.change('enabled', event.target.checked)} /></label>}>
        <div className="settings-field-grid settings-dependent-fields">
          {field({ name: 'proxyHost', label: 'Proxy host', description: 'Use a private Compose service name or internal proxy address.' })}
          {field({ name: 'proxyPort', label: 'Proxy port', type: 'number', min: '1', max: '65535' })}
          {field({ name: 'healthConnectTimeoutSeconds', label: 'Proxy health connect timeout', type: 'number', min: '0.1', max: '60', step: '0.1', unit: 'seconds' })}
        </div>
      </SettingsSection>
      <SettingsSection title="Tunnel Health" description="Optionally requires the VPN container health endpoint to return HTTP 200.">
        <div className="settings-field-grid settings-dependent-fields">
          {field({ name: 'tunnelHealthUrl', label: 'Tunnel health URL', description: 'Leave empty for generic proxy TCP-only health.' })}
          {field({ name: 'healthRequestTimeoutSeconds', label: 'Tunnel health request timeout', type: 'number', min: '0.1', max: '60', step: '0.1', unit: 'seconds' })}
          {field({ name: 'healthCheckIntervalSeconds', label: 'Health check interval', type: 'number', min: '1', step: '0.1', unit: 'seconds', restartRequired: true })}
        </div>
      </SettingsSection>
      <p className="settings-config-path">Stored in <code>{snapshot.configPath}</code></p>
    </form>
  );
}

export function VpnSettings({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error } = useSettingsSnapshot<VpnSettingsSnapshot>('/api/v1/settings/vpn');
  if (error) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading VPN settings...</div>;
  return <Editor snapshot={snapshot} onDirtyChange={onDirtyChange} />;
}

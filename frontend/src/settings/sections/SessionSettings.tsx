import { SettingsField, SettingsSaveBar, SettingsSection } from '../components/SettingsControls';
import { AppNavigationLink } from '../../app/AppNavigationLink';
import { useSettingsEditor } from '../hooks/useSettingsEditor';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';
import type { FormValues, SessionSettingsSnapshot } from '../types';

const toValues = (snapshot: SessionSettingsSnapshot): FormValues => ({
  maxConcurrentSessions: String(snapshot.maxConcurrentSessions),
  sessionIdleTimeoutMinutes: String(snapshot.sessionIdleTimeoutMinutes),
});

function Editor({ snapshot, onDirtyChange }: { snapshot: SessionSettingsSnapshot; onDirtyChange: (dirty: boolean) => void }) {
  const editor = useSettingsEditor(toValues(snapshot), '/api/v1/settings/sessions', onDirtyChange);
  const savedConcurrentLimit = Number(editor.baseline.maxConcurrentSessions);
  const savedIdleTimeout = Number(editor.baseline.sessionIdleTimeoutMinutes);
  const concurrentLabel = savedConcurrentLimit === 0 ? 'Unlimited' : String(savedConcurrentLimit);
  const idleTimeoutLabel = savedIdleTimeout === 0 ? 'Unlimited' : `${savedIdleTimeout} minutes`;
  return (
    <form className="general-settings-form settings-spa-form" onSubmit={(event) => { event.preventDefault(); void editor.save(); }}>
      <SettingsSaveBar {...editor} onSave={() => void editor.save()} onDiscard={editor.discard} />
      <SettingsSection title="Limits" description="Controls concurrent logins and idle expiration for password and passkey sessions.">
        <div className="settings-field-grid">
          <SettingsField field={{ name: 'maxConcurrentSessions', label: 'Maximum concurrent sessions', type: 'number', min: '0', max: '1000', description: 'Use 0 for unlimited. A new login expires the oldest session when the limit is reached.' }} values={editor.values} onChange={editor.change} />
          <SettingsField field={{ name: 'sessionIdleTimeoutMinutes', label: 'Idle timeout', type: 'number', min: '0', max: '525600', unit: 'minutes', description: 'Use 0 for unlimited. This measures inactivity, not total signed-in time.' }} values={editor.values} onChange={editor.change} />
        </div>
      </SettingsSection>
      <SettingsSection title="Current State" description="Timeout changes apply immediately; the concurrent limit applies on the next login.">
        <div className="account-status-grid">
          <article><span>Active sessions</span><strong>{snapshot.activeSessions}</strong></article>
          <article><span>Concurrent limit</span><strong>{concurrentLabel}</strong></article>
          <article><span>Idle timeout</span><strong>{idleTimeoutLabel}</strong></article>
        </div>
        <div className="settings-inline-actions">
          <AppNavigationLink className="ghost icon-text-button" href="/admin/sessions">
            <i className="fas fa-laptop" aria-hidden="true" /><span>Manage active sessions</span>
          </AppNavigationLink>
        </div>
      </SettingsSection>
      <p className="settings-config-path">Stored in <code>{snapshot.configPath}</code></p>
    </form>
  );
}

export function SessionSettings({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error } = useSettingsSnapshot<SessionSettingsSnapshot>('/api/v1/settings/sessions');
  if (error) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading session settings...</div>;
  return <Editor snapshot={snapshot} onDirtyChange={onDirtyChange} />;
}

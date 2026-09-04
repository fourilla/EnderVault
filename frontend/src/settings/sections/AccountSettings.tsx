import { SettingsField, SettingsPasswordField, SettingsSaveBar, SettingsSection } from '../components/SettingsControls';
import { useSettingsEditor } from '../hooks/useSettingsEditor';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';
import type { AccountSettingsSnapshot, FormValues } from '../types';

const toValues = (snapshot: AccountSettingsSnapshot): FormValues => ({
  username: snapshot.username,
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
  passwordLoginEnabled: snapshot.passwordLoginEnabled,
});

const clearPasswords = (values: FormValues): FormValues => ({
  ...values,
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
});

function Editor({ snapshot, onDirtyChange }: { snapshot: AccountSettingsSnapshot; onDirtyChange: (dirty: boolean) => void }) {
  const editor = useSettingsEditor(toValues(snapshot), '/api/v1/settings/account', onDirtyChange, undefined, clearPasswords);
  const passwordLoginEnabled = Boolean(editor.values.passwordLoginEnabled);
  return (
    <form className="general-settings-form settings-spa-form" onSubmit={(event) => { event.preventDefault(); void editor.save(); }}>
      <SettingsSaveBar {...editor} onSave={() => void editor.save()} onDiscard={editor.discard} />
      <SettingsSection title="Credentials" description="Changing the admin ID or password requires the current password.">
        <div className="settings-field-grid">
          <SettingsField field={{ name: 'username', label: 'Admin ID', minLength: 3, maxLength: 64, autoComplete: 'username' }} values={editor.values} onChange={editor.change} />
          <SettingsPasswordField name="currentPassword" label="Current password" autoComplete="current-password" values={editor.values} onChange={editor.change} />
          <SettingsPasswordField name="newPassword" label="New password" autoComplete="new-password" values={editor.values} onChange={editor.change} />
          <SettingsPasswordField name="confirmPassword" label="Confirm new password" autoComplete="new-password" values={editor.values} onChange={editor.change} />
        </div>
      </SettingsSection>
      <SettingsSection title="ID/Password Login" description="Disable this only after registering at least one passkey."
        action={<label className="settings-heading-switch"><span className={`status-badge ${passwordLoginEnabled ? 'active' : 'expired'}`}>{passwordLoginEnabled ? 'Enabled' : 'Disabled'}</span><input className="settings-switch-input" type="checkbox" checked={passwordLoginEnabled} onChange={(event) => editor.change('passwordLoginEnabled', event.target.checked)} /></label>}>
        <div className="account-status-grid">
          <article><span>Passkeys</span><strong>{snapshot.passkeysEnabled ? 'Enabled' : 'Disabled'}</strong></article>
          <article><span>Registered passkeys</span><strong>{snapshot.passkeyCount}</strong></article>
          <article><span>ID/PW login</span><strong>{passwordLoginEnabled ? 'Enabled' : 'Disabled'}</strong></article>
        </div>
      </SettingsSection>
      <p className="settings-config-path">Stored in <code>{snapshot.configPath}</code></p>
    </form>
  );
}

export function AccountSettings({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error } = useSettingsSnapshot<AccountSettingsSnapshot>('/api/v1/settings/account');
  if (error) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading account settings...</div>;
  return <Editor snapshot={snapshot} onDirtyChange={onDirtyChange} />;
}

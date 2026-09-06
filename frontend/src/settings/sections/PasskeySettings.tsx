import { useEffect, useState } from 'react';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';
import { registerPasskey } from '../passkey-client';
import { postJson, showError, showNotification } from '../settings-api';
import type { ActionResponse, PasskeySettingsSnapshot } from '../types';

export function PasskeySettings({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error, refresh } = useSettingsSnapshot<PasskeySettingsSnapshot>('/api/v1/settings/passkeys');
  const [label, setLabel] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => onDirtyChange(false), [onDirtyChange]);

  const register = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const result = await registerPasskey(label);
      showNotification(result.notification);
      setLabel('');
      refresh();
    } catch (reason) {
      showError(reason);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id: string, name: string) => {
    const confirmed = await window.EnderVault!.askConfirmation({
      title: 'Delete passkey?',
      message: `Delete ${name}? This device will no longer be able to use that credential.`,
      confirmLabel: 'Delete passkey',
      danger: true,
    });
    if (!confirmed) return;
    try {
      const result = await postJson<ActionResponse>(`/api/v1/settings/passkeys/${encodeURIComponent(id)}/delete`);
      showNotification(result.notification);
      refresh();
    } catch (reason) {
      showError(reason);
    }
  };

  if (error && !snapshot) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading passkeys...</div>;
  return (
    <div className="settings-spa-form general-settings-form">
      {error && <div className="settings-spa-error" role="alert">{error} Showing the last loaded values.
        <button className="ghost" type="button" onClick={refresh}>Retry</button>
      </div>}
      <section className="settings-detail-section">
        <header className="settings-subsection-heading"><div><h3>Device Registration</h3><p>Register trusted devices for passwordless login.</p></div></header>
        <div className="passkey-register-form">
          <label><span>Device name</span><input value={label} maxLength={80} placeholder="My laptop" onChange={(event) => setLabel(event.target.value)} /></label>
          <button className="primary icon-text-button" type="button" disabled={!snapshot.enabled || busy} onClick={() => void register()}><i className="fas fa-key" aria-hidden="true" /><span>{busy ? 'Registering...' : 'Register'}</span></button>
        </div>
        <div className="passkey-note-list">
          <p className="passkey-note">Passkey registration requires HTTPS or localhost. RP ID: {snapshot.rpId}</p>
          <p className="passkey-note">Allowed origins: {snapshot.allowedOrigins.join(', ')}</p>
          {!snapshot.passwordLoginEnabled && <p className="passkey-note">Password login is disabled. Keep at least one working passkey.</p>}
        </div>
      </section>
      <section className="settings-detail-section">
        <header className="settings-subsection-heading"><div><h3>Registered Passkeys</h3><p>{snapshot.credentials.length} total</p></div></header>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>Credential</th><th>Transports</th><th>Backup</th><th>Created</th><th>Last used</th><th>Actions</th></tr></thead>
            <tbody>
              {snapshot.credentials.map((credential) => (
                <tr key={credential.id}>
                  <td><span className="item-name">{credential.label}</span></td>
                  <td title={credential.credentialId}>{credential.shortCredentialId}</td>
                  <td>{credential.transports}</td>
                  <td><span className={`status-badge ${credential.backedUp ? 'active' : 'expired'}`}>{credential.backedUp ? 'Backed up' : 'Local'}</span></td>
                  <td>{credential.created}</td><td>{credential.lastUsed}</td>
                  <td><button className="ghost action-icon" type="button" title="Delete passkey" aria-label={`Delete ${credential.label}`} onClick={() => void remove(credential.id, credential.label)}><i className="fas fa-trash-can" aria-hidden="true" /></button></td>
                </tr>
              ))}
              {snapshot.credentials.length === 0 && <tr><td className="empty" colSpan={7}>No passkeys registered.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

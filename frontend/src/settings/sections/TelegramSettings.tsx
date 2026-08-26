import { useState } from 'react';
import { SettingsField, SettingsPasswordField, SettingsSaveBar, SettingsSection } from '../components/SettingsControls';
import { useSettingsEditor } from '../hooks/useSettingsEditor';
import { useSettingsSnapshot } from '../hooks/useSettingsSnapshot';
import { saveSettings, showError } from '../settings-api';
import type { FormValues, TelegramSettingsSnapshot } from '../types';

const toValues = (snapshot: TelegramSettingsSnapshot): FormValues => {
  const values: FormValues = {
    enabled: snapshot.enabled,
    botToken: snapshot.botToken,
    chatId: snapshot.chatId,
    activityKeys: snapshot.groups.flatMap((group) => group.activities.map((activity) => activity.key)),
  };
  snapshot.groups.forEach((group) => group.activities.forEach((activity) => {
    values[`activity.${activity.key}`] = activity.enabled;
  }));
  return values;
};

function Editor({ snapshot, onDirtyChange }: { snapshot: TelegramSettingsSnapshot; onDirtyChange: (dirty: boolean) => void }) {
  const editor = useSettingsEditor(toValues(snapshot), '/api/v1/settings/telegram-alerts', onDirtyChange);
  const [testing, setTesting] = useState(false);
  const enabled = Boolean(editor.values.enabled);
  const sendTest = async () => {
    if (testing) return;
    setTesting(true);
    try {
      await saveSettings('/api/v1/settings/telegram-alerts/test', editor.values);
    } catch (error) {
      showError(error);
    } finally {
      setTesting(false);
    }
  };

  return (
    <form className="general-settings-form settings-spa-form" onSubmit={(event) => { event.preventDefault(); void editor.save(); }}>
      <SettingsSaveBar {...editor} onSave={() => void editor.save()} onDiscard={editor.discard} />
      <SettingsSection title="Telegram Alerts" description="Send selected activity events through the configured Telegram bot."
        action={<label className="settings-heading-switch"><span>{enabled ? 'Enabled' : 'Disabled'}</span><input className="settings-switch-input" type="checkbox" checked={enabled} onChange={(event) => editor.change('enabled', event.target.checked)} /></label>}>
        <p className="settings-section-note">Connection details can be tested even while automatic alerts are disabled.</p>
      </SettingsSection>
      <SettingsSection title="Telegram Connection" description="Configure the bot target used for selected activity alerts."
        action={<button className="ghost icon-text-button" type="button" disabled={testing} onClick={() => void sendTest()}><i className="fas fa-paper-plane" aria-hidden="true" /><span>{testing ? 'Sending...' : 'Send test message'}</span></button>}>
        <div className="settings-field-grid">
          <SettingsPasswordField name="botToken" label="Bot token" autoComplete="off" values={editor.values} onChange={editor.change} />
          <SettingsField field={{ name: 'chatId', label: 'Chat ID', autoComplete: 'off' }} values={editor.values} onChange={editor.change} />
        </div>
      </SettingsSection>
      <SettingsSection title="Activity Types" description="Choose which log categories are sent to Telegram.">
        <div className={`telegram-activity-settings${enabled ? '' : ' settings-dependent-locked'}`}>
          {snapshot.groups.map((group) => (
            <section className="telegram-activity-group" key={group.name}>
              <h4>{group.name}</h4>
              <div className="telegram-activity-grid">
                {group.activities.map((activity) => (
                  <label className="telegram-activity-toggle" key={activity.key}>
                    <input
                      type="checkbox"
                      checked={Boolean(editor.values[`activity.${activity.key}`])}
                      disabled={!enabled}
                      onChange={(event) => editor.change(`activity.${activity.key}`, event.target.checked)}
                    />
                    <span><strong>{activity.label}</strong><small>{activity.type}</small></span>
                  </label>
                ))}
              </div>
            </section>
          ))}
        </div>
      </SettingsSection>
      <p className="settings-config-path">Stored in <code>{snapshot.configPath}</code></p>
    </form>
  );
}

export function TelegramSettings({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { snapshot, error } = useSettingsSnapshot<TelegramSettingsSnapshot>('/api/v1/settings/telegram-alerts');
  if (error) return <div className="settings-spa-error">{error}</div>;
  if (!snapshot) return <div className="settings-spa-loading">Loading Telegram settings...</div>;
  return <Editor snapshot={snapshot} onDirtyChange={onDirtyChange} />;
}

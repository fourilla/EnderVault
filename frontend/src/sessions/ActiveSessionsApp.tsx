import { useEffect, useRef, useState } from 'react';
import { AppNavigationLink } from '../app/AppNavigationLink';
import { useAdminApp } from '../app/AdminAppContext';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { loadActiveSessions, revokeActiveSession } from './session-api';
import type { ActiveSession } from './types';

export function ActiveSessionsApp() {
  const { refreshBootstrap } = useAdminApp();
  const [sessions, setSessions] = useState<ActiveSession[] | null>(null);
  const [selectedSession, setSelectedSession] = useState<ActiveSession | null>(null);
  const [busyId, setBusyId] = useState('');
  const [error, setError] = useState('');
  const detailDialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    void loadActiveSessions(controller.signal)
      .then((payload) => setSessions(payload.sessions))
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Active sessions could not be loaded.');
        }
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const dialog = detailDialog.current;
    if (selectedSession && dialog && !dialog.open) dialog.showModal();
  }, [selectedSession]);

  const closeDetails = () => {
    detailDialog.current?.close();
    setSelectedSession(null);
  };

  const revoke = async (session: ActiveSession) => {
    setBusyId(session.managementId);
    try {
      const body = await revokeActiveSession(session.managementId);
      if (body.redirectUrl) {
        window.EnderVault!.navigateWithNotification(body);
        return;
      }
      setSessions((current) => current?.filter((item) => item.managementId !== session.managementId) ?? []);
      await refreshBootstrap();
    } catch (reason) {
      toastError(reason, 'Session could not be revoked.');
    } finally {
      setBusyId('');
    }
  };

  return (
    <>
      <PageHeader title="Active Sessions" />

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {!sessions && !error && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" />
          <span>Loading active sessions...</span>
        </section>
      )}
      {sessions && (
        <section className="dashboard-panel sessions-panel">
          <header className="section-heading">
            <div>
              <h2>Signed-in Devices</h2>
              <p>Revoke a forgotten or unrecognized browser session.</p>
            </div>
            <AppNavigationLink className="ghost icon-text-button" href="/admin/settings?section=sessions">
              {icon('fas fa-sliders')}<span>Session settings</span>
            </AppNavigationLink>
          </header>

          <p className="session-security-note">
            Device names are inferred from the browser User-Agent and are informational only.
            Revocation blocks the session from subsequent requests.
          </p>

          <section className="table-wrap" aria-label="Active sessions">
            <table className="sessions-table">
              <colgroup>
                <col className="session-col-device" />
                <col className="session-col-ip" />
                <col className="session-col-auth" />
                <col className="session-col-time" />
                <col className="session-col-time" />
                <col className="session-col-actions" />
              </colgroup>
              <thead>
                <tr><th>Device</th><th>IP</th><th>Authentication</th><th>Signed in</th><th>Expires</th><th className="session-actions-cell">Actions</th></tr>
              </thead>
              <tbody>
                {sessions.map((session) => (
                  <tr key={session.managementId}>
                    <td>
                      <div className="session-device-cell" title={session.userAgent}>
                        <span>{icon('fas fa-laptop')}<strong>{session.deviceLabel}</strong></span>
                        {session.current && <span className="status-badge active">Current</span>}
                      </div>
                    </td>
                    <td className="session-truncate" title={session.ip}>{session.ip}</td>
                    <td className="session-truncate" title={session.authMethodLabel}>{session.authMethodLabel}</td>
                    <td className="session-truncate" title={session.createdLabel}>{session.createdLabel}</td>
                    <td className="session-truncate" title={session.expiresLabel}>{session.expiresLabel}</td>
                    <td className="session-actions-cell">
                      <div className="table-actions">
                        <button className="ghost icon-button action-icon" type="button" title="View session details"
                          aria-label="View session details" onClick={() => setSelectedSession(session)}>
                          {icon('fas fa-circle-info')}
                        </button>
                        <button className="danger icon-button action-icon" type="button"
                          title={session.current ? 'Revoke current session' : 'Revoke session'}
                          aria-label={session.current ? 'Revoke current session' : 'Revoke session'}
                          disabled={Boolean(busyId)} onClick={() => void revoke(session)}>
                          {icon('fas fa-user-slash')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {sessions.length === 0 && (
                  <tr className="empty-row"><td colSpan={6} className="empty">No active sessions.</td></tr>
                )}
              </tbody>
            </table>
          </section>
        </section>
      )}

      <dialog ref={detailDialog} className="admin-detail-modal" aria-labelledby="sessionDetailTitle"
        onClose={() => setSelectedSession(null)}
        onClick={(event) => { if (event.target === event.currentTarget) closeDetails(); }}>
        <article className="admin-detail-modal-card">
          <header className="admin-detail-modal-header">
            <div>
              <h2 id="sessionDetailTitle">Session Details</h2>
              <p>{selectedSession ? `${selectedSession.deviceLabel} · ${selectedSession.current ? 'Current session' : 'Active session'}` : '-'}</p>
            </div>
            <button className="ghost icon-button action-icon admin-detail-modal-close" type="button"
              title="Close details" aria-label="Close details" onClick={closeDetails}>
              {icon('fas fa-xmark')}
            </button>
          </header>
          <dl className="admin-detail-grid">
            <div><dt>Account</dt><dd>{selectedSession?.username ?? '-'}</dd></div>
            <div><dt>Status</dt><dd>{selectedSession ? (selectedSession.current ? 'Current session' : 'Active session') : '-'}</dd></div>
            <div><dt>Device</dt><dd>{selectedSession?.deviceLabel ?? '-'}</dd></div>
            <div><dt>IP</dt><dd>{selectedSession?.ip ?? '-'}</dd></div>
            <div><dt>Authentication</dt><dd>{selectedSession?.authMethodLabel ?? '-'}</dd></div>
            <div><dt>Signed in</dt><dd>{selectedSession?.createdLabel ?? '-'}</dd></div>
            <div><dt>Last active</dt><dd>{selectedSession?.lastActiveLabel ?? '-'}</dd></div>
            <div><dt>Expires</dt><dd>{selectedSession?.expiresLabel ?? '-'}</dd></div>
            <div><dt>User-Agent</dt><dd>{selectedSession?.userAgent || '-'}</dd></div>
          </dl>
        </article>
      </dialog>
    </>
  );
}

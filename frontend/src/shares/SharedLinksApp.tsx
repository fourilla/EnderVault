import { useEffect, useState } from 'react';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { deleteExpiredShares, deleteShare, loadShares, revokeShare } from './share-api';
import type { ShareLink } from './types';

export function SharedLinksApp() {
  const [shares, setShares] = useState<ShareLink[] | null>(null);
  const [error, setError] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const [busy, setBusy] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    void loadShares(controller.signal)
      .then(setShares)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Shared links could not be loaded.');
        }
      });
    return () => controller.abort();
  }, [refreshToken]);

  const reload = () => setRefreshToken((value) => value + 1);

  const run = async (key: string, action: () => Promise<unknown>, fallback: string) => {
    setBusy(key);
    try {
      await action();
      reload();
    } catch (reason) {
      toastError(reason, fallback);
    } finally {
      setBusy('');
    }
  };

  const copy = async (value: string, message: string) => {
    try {
      const client = window.EnderVault;
      if (client && await client.copyText(value)) {
        client.showToast('success', message);
      }
    } catch (reason) {
      toastError(reason, 'Link could not be copied.');
    }
  };

  return (
    <>
      <PageHeader
        title="Shared Links"
        actions={shares && shares.length > 0 ? (
            <button className="ghost" type="button" disabled={Boolean(busy)}
              onClick={() => void run('expired', deleteExpiredShares, 'Expired links could not be deleted.')}>
              Delete expired links
            </button>
        ) : undefined}
      />

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {!shares && !error && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" />
          <span>Loading shared links...</span>
        </section>
      )}
      {shares && (
        <section className="table-wrap" aria-label="Shared links">
          <table>
            <thead>
              <tr><th>Link</th><th>Target</th><th>Type</th><th>Created</th><th>Expires</th><th>Status</th><th>Actions</th></tr>
            </thead>
            <tbody>
              {shares.map((share) => (
                <tr key={share.token} data-share-status={share.statusClass}>
                  <td><input readOnly value={share.url} aria-label={`Share URL for ${share.path}`} /></td>
                  <td><span className="item-name" title={share.path}>{share.path}</span></td>
                  <td>{share.type}</td>
                  <td>{share.createdLabel}</td>
                  <td>{share.expiresLabel}</td>
                  <td><span className={`status-badge ${share.statusClass}`}>{share.statusLabel}</span></td>
                  <td>
                    <div className="table-actions">
                      <button className="ghost icon-button action-icon" type="button" title="Copy link" aria-label="Copy link"
                        onClick={() => void copy(share.url, 'Share link copied.')}>{icon('fas fa-link')}</button>
                      {share.directDownloadUrl && (
                        <button className="ghost icon-button action-icon" type="button" title="Copy direct download link"
                          aria-label="Copy direct download link"
                          onClick={() => void copy(share.directDownloadUrl!, 'Direct download link copied.')}>
                          {icon('fas fa-file-arrow-down')}
                        </button>
                      )}
                      {share.active && (
                        <button className="danger icon-button action-icon" type="button" title="Revoke" aria-label="Revoke"
                          disabled={Boolean(busy)}
                          onClick={() => void run(`revoke:${share.token}`, () => revokeShare(share.token), 'Share link could not be revoked.')}>
                          {icon('fas fa-link-slash')}
                        </button>
                      )}
                      <button className="ghost icon-button action-icon" type="button" title="Delete" aria-label="Delete"
                        disabled={Boolean(busy)}
                        onClick={() => void run(`delete:${share.token}`, () => deleteShare(share.token), 'Share link could not be deleted.')}>
                        {icon('fas fa-trash-can')}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {shares.length === 0 && <tr className="empty-row"><td colSpan={7} className="empty">No shared links yet.</td></tr>}
            </tbody>
          </table>
        </section>
      )}
    </>
  );
}

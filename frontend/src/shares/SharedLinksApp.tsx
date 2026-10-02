import { LoadingState } from '../shared/layout/LoadingState';
import { StableTable } from '../shared/browser/StableTable';
import { PathLink } from '../shared/browser/PathLink';
import { useEffect, useState } from 'react';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { FloatingPageActions } from '../app/FloatingPageActions';
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
      <PageHeader title="Shared Links" />
      {shares && shares.length > 0 && <FloatingPageActions mode="single" label="Delete expired links"
        icon="fas fa-broom" disabled={Boolean(busy)}
        onAction={() => void run('expired', deleteExpiredShares, 'Expired links could not be deleted.')} />}

      <div className="page-feedback-layout">
      {error && <PageErrorPanel title="Shared links unavailable" message={error} stale={shares !== null}
        actions={<button type="button" className="icon-text-button" onClick={reload}>
          {icon('fas fa-arrows-rotate')}<span>Retry</span>
        </button>} />}
      {!shares && !error && (
        <LoadingState label="Loading shared links..." />
      )}
      {shares && (
        <section className="table-wrap" aria-label="Shared links">
          <StableTable columns={['text', 'text', 'type', 'date', 'status', 'actions']} actionCount={4}>
            <thead>
              <tr><th>Link</th><th>Target</th><th>Type</th><th>Dates</th><th>Status</th><th>Actions</th></tr>
            </thead>
            <tbody>
              {shares.map((share) => (
                <tr key={share.token} data-share-status={share.statusClass}>
                  <td><input readOnly value={share.url} aria-label={`Share URL for ${share.path}`} /></td>
                  <td><PathLink path={share.path} directory={share.type === 'DIRECTORY'} /></td>
                  <td>{share.type}</td>
                  <td><div className="table-cell-stack">
                    <span title="Created" aria-label={`Created: ${share.createdLabel}`}>
                      <i className="fas fa-calendar-plus" aria-hidden="true" /> {share.createdLabel}
                    </span>
                    <span title="Expires" aria-label={`Expires: ${share.expiresLabel}`}>
                      <i className="fas fa-hourglass-end" aria-hidden="true" /> {share.expiresLabel}
                    </span>
                  </div></td>
                  <td><div className="table-cell-stack">
                    <span className={`status-badge ${share.statusClass}`}>{share.statusLabel}</span>
                    <span className={`status-badge ${share.previewEnabled ? 'active' : 'info'}`}>
                      Preview {share.previewEnabled ? 'on' : 'off'}
                    </span>
                  </div></td>
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
              {shares.length === 0 && <tr className="empty-row"><td colSpan={6} className="empty">No shared links yet.</td></tr>}
            </tbody>
          </StableTable>
        </section>
      )}
      </div>
    </>
  );
}

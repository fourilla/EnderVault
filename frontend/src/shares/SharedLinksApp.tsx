import { LoadingState } from '../shared/layout/LoadingState';
import { StableTable } from '../shared/browser/StableTable';
import { PathLink } from '../shared/browser/PathLink';
import { type FormEvent, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useRouteSearch } from '../app/RouteSearch';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { FloatingPageActions } from '../app/FloatingPageActions';
import { deleteExpiredShares, deleteShare, loadShares, revokeShare } from './share-api';
import type { ShareLink } from './types';

export function SharedLinksApp() {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeQuery = searchParams.get('q') ?? '';
  const [snapshot, setSnapshot] = useState<{ query: string; shares: ShareLink[] } | null>(null);
  const [feedback, setFeedback] = useState<{ query: string; message: string } | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [busy, setBusy] = useState('');
  const [query, setQuery] = useState(activeQuery);
  const shares = snapshot?.query === activeQuery ? snapshot.shares : null;
  const error = feedback?.query === activeQuery ? feedback.message : '';

  useEffect(() => {
    const controller = new AbortController();
    setFeedback(null);
    void loadShares(controller.signal, activeQuery)
      .then((shares) => {
        if (!controller.signal.aborted) setSnapshot({ query: activeQuery, shares });
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setFeedback({ query: activeQuery,
            message: reason instanceof Error ? reason.message : 'Shared links could not be loaded.' });
        }
      });
    return () => controller.abort();
  }, [activeQuery, refreshToken]);

  useEffect(() => setQuery(activeQuery), [activeQuery]);
  const search = (event: FormEvent) => {
    event.preventDefault();
    const next = new URLSearchParams(searchParams);
    const trimmed = query.trim();
    if (trimmed) next.set('q', trimmed);
    else next.delete('q');
    setSearchParams(next);
  };
  useRouteSearch({ label: 'Search shared links', placeholder: 'Search target paths',
    appliedQuery: activeQuery, value: query, onChange: setQuery, onSubmit: search, schemaScope: 'shares',
    onReset: () => {
      if (!activeQuery) return;
      const next = new URLSearchParams(searchParams);
      next.delete('q');
      setSearchParams(next);
    } });

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
      {shares && (shares.length > 0 || activeQuery) && <FloatingPageActions mode="single" label="Delete expired links"
        icon="fas fa-broom" disabled={Boolean(busy)}
        onAction={() => void run('expired', deleteExpiredShares, 'Expired links could not be deleted.')} />}

      <div className="page-feedback-layout">
      {error && <PageErrorPanel title="Shared links unavailable" message={error} stale={shares !== null}
        actions={<button type="button" className="icon-text-button" onClick={reload}>
          {icon('fas fa-arrows-rotate')}<span>Retry</span>
        </button>} />}
      {!shares && !error && (
        <LoadingState label={activeQuery ? 'Searching shared links...' : 'Loading shared links...'} />
      )}
      {shares && (
        <section className="table-wrap" aria-label="Shared links">
          <StableTable columns={['text', 'text', 'type', 'date', 'status', 'actions']} actionCount={4}>
            <thead>
              <tr><th>Link</th><th>Target</th><th>Type</th><th>Created / Expires</th><th>Status</th><th>Actions</th></tr>
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
              {shares.length === 0 && <tr className="empty-row"><td colSpan={6} className="empty">
                {activeQuery ? 'No shared links match this search.' : 'No shared links yet.'}
              </td></tr>}
            </tbody>
          </StableTable>
        </section>
      )}
      </div>
    </>
  );
}

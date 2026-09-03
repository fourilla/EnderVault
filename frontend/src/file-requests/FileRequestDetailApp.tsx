import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AppNavigationLink } from '../app/AppNavigationLink';
import { toastError } from '../shared/api/form-api';
import { canRetainSnapshot } from '../shared/api/snapshot-errors';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import {
  cancelFileRequestUploads,
  deleteFileRequest,
  loadFileRequest,
  revokeFileRequest,
} from './file-request-api';
import type { FileRequestDetailPayload } from './types';

export function FileRequestDetailApp() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const [snapshot, setPayload] = useState<FileRequestDetailPayload | null>(null);
  const [failure, setFailure] = useState<{ id: string; message: string } | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [busy, setBusy] = useState('');
  const payload = snapshot?.item.id === id ? snapshot : null;
  const error = failure?.id === id ? failure.message : '';

  useEffect(() => {
    const controller = new AbortController();
    setFailure(null);
    void loadFileRequest(id, controller.signal)
      .then((next) => { if (!controller.signal.aborted) setPayload(next); })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          if (!canRetainSnapshot(reason)) setPayload(null);
          setFailure({ id, message: reason instanceof Error ? reason.message : 'File request could not be loaded.' });
        }
      });
    return () => controller.abort();
  }, [id, refreshToken]);

  useEffect(() => {
    if (!payload) return;
    document.title = `${payload.item.title} - EnderVault File Request`;
    const stickyContext = {
      targetType: 'FILE_REQUEST',
      targetKey: payload.item.id,
      surface: 'DETAIL',
      label: payload.item.title,
    };
    void window.EnderVaultStickyNotes?.setContext(stickyContext);
    document.dispatchEvent(new CustomEvent('endervault:sticky-context-changed', {
      detail: stickyContext,
    }));
  }, [payload]);

  const reload = () => setRefreshToken((value) => value + 1);
  const run = async (key: string, action: () => Promise<unknown>, fallback: string, after?: () => void) => {
    setBusy(key);
    try {
      await action();
      after ? after() : reload();
    } catch (reason) {
      toastError(reason, fallback);
    } finally {
      setBusy('');
    }
  };

  const confirmAndRun = async (options: {
    title: string; message: string; confirmLabel: string; key: string;
    action: () => Promise<unknown>; fallback: string; after?: () => void;
  }) => {
    const confirmed = await window.EnderVault?.askConfirmation({
      title: options.title,
      message: options.message,
      confirmLabel: options.confirmLabel,
      danger: true,
    });
    if (confirmed) await run(options.key, options.action, options.fallback, options.after);
  };

  if (error && !payload) return <section className="dashboard-panel browser-load-error" role="alert">{error}</section>;
  if (!payload) return <section className="browser-load-progress" role="status" aria-live="polite">
    <i className="fas fa-spinner fa-spin" aria-hidden="true" /><span>Loading file request...</span>
  </section>;

  const { item } = payload;
  return (
    <div className="dashboard-workspace file-request-detail-workspace">
      <PageHeader title={item.title} />
      {error && <section className="browser-load-error" role="alert">{error} Showing the last loaded values.
        <button className="ghost" type="button" onClick={reload}>Retry</button>
      </section>}

      <section className="dashboard-panel">
        <header className="section-heading">
          <div><h2>Request Policy</h2><p>Request definitions are immutable. Duplicate this request to issue a revised policy and token.</p></div>
          <span className={`status-badge ${item.statusClass}`}>{item.statusLabel}</span>
        </header>
        <dl className="file-request-policy-grid">
          <Policy label="Title" value={item.title} wide />
          <Policy label="Description" value={item.description || 'No description'} wide muted={!item.description} description />
          <Policy label="Destination" value={item.destinationLabel} />
          <Policy label="Uploader name" value={item.uploaderNameLabel} />
          <Policy label="Per-file limit" value={item.fileLimitLabel} />
          <Policy label="Total quota" value={item.totalLimitLabel} />
          <Policy label="Maximum files" value={String(item.maxFiles)} />
          <Policy label="Extensions" value={item.extensionsLabel} />
          <Policy label="Created" value={item.createdLabel} />
          <Policy label="Expires" value={item.expiresLabel} />
          <Policy label="Usage" value={item.usageLabel} wide />
        </dl>
        <div className="file-request-detail-actions">
          <button className="ghost icon-text-button" type="button" onClick={() => void copyLink(item.url)}>
            {icon('fas fa-link')}<span>Copy link</span>
          </button>
          <Link className="button-link ghost icon-text-button" to={`/admin/file-requests?copyFrom=${encodeURIComponent(item.id)}`}>
            {icon('fas fa-copy')}<span>Duplicate</span>
          </Link>
          {item.active && <button className="danger icon-text-button" type="button" disabled={Boolean(busy)}
            onClick={() => void confirmAndRun({
              title: 'Revoke file request',
              message: 'Revoke this file request? Active uploads will stop on their next protocol request.',
              confirmLabel: 'Revoke', key: 'revoke', action: () => revokeFileRequest(item.id),
              fallback: 'File request could not be revoked.',
            })}>{icon('fas fa-link-slash')}<span>Revoke</span></button>}
        </div>
      </section>

      <section className="dashboard-panel">
        <header className="section-heading">
          <div><h2>Active Uploads</h2><p>Uploads that still hold a resumable session for this request.</p></div>
          <span className={`status-badge ${payload.activeUploads.length === 0 ? 'active' : 'warning'}`}>{payload.activeUploads.length} active</span>
        </header>
        <div className="table-wrap compact-table"><table>
          <thead><tr><th>File</th><th>Uploader</th><th>Size</th><th>Status</th><th>Started</th><th>Expires</th></tr></thead>
          <tbody>
            {payload.activeUploads.map((upload) => <tr key={upload.id}>
              <td className="table-primary-text" title={upload.originalFilename}>{upload.originalFilename}</td>
              <td>{upload.submittedBy}</td><td>{upload.sizeLabel}</td><td>{upload.status}</td>
              <td>{upload.createdLabel}</td><td>{upload.expiresLabel}</td>
            </tr>)}
            {payload.activeUploads.length === 0 && <tr className="empty-row"><td colSpan={6} className="empty">No active uploads.</td></tr>}
          </tbody>
        </table></div>
        {payload.activeUploads.length > 0 && <div className="form-actions end">
          <button className="danger icon-text-button" type="button" disabled={Boolean(busy)}
            onClick={() => void confirmAndRun({
              title: 'Cancel active uploads', message: 'Cancel every active upload for this request? Uploaded chunks will be discarded.',
              confirmLabel: 'Cancel uploads', key: 'cancel', action: () => cancelFileRequestUploads(item.id),
              fallback: 'Active uploads could not be canceled.',
            })}><span>Cancel active uploads</span></button>
        </div>}
      </section>

      <section className="dashboard-panel">
        <header className="section-heading">
          <div><h2>Pending Files</h2><p>Staged files waiting for an administrator&apos;s conflict decision.</p></div>
          {payload.pendingDecisions.length > 0 && (
            <AppNavigationLink className="button-link ghost" href="/admin/pending-decisions">Review all</AppNavigationLink>
          )}
        </header>
        <div className="table-wrap compact-table"><table>
          <thead><tr><th>File</th><th>Uploader</th><th>Size</th><th>Received</th></tr></thead>
          <tbody>
            {payload.pendingDecisions.map((pending) => <tr key={pending.id}>
              <td className="table-primary-text" title={pending.originalFilename}>{pending.originalFilename}</td>
              <td>{pending.submittedBy}</td><td>{pending.sizeLabel}</td><td>{pending.createdLabel}</td>
            </tr>)}
            {payload.pendingDecisions.length === 0 && <tr className="empty-row"><td colSpan={4} className="empty">No pending files.</td></tr>}
          </tbody>
        </table></div>
      </section>

      <section className="dashboard-panel">
        <header className="section-heading">
          <div><h2>Recent Activity</h2><p>The latest activity entries carrying this request ID.</p></div>
          <Link className="button-link ghost" to={`/admin/logs?q=${encodeURIComponent(item.id)}`}>Open logs</Link>
        </header>
        <div className="table-wrap compact-table"><table>
          <thead><tr><th>Time</th><th>Type</th><th>IP</th><th>Message</th></tr></thead>
          <tbody>
            {payload.activityHistory.map((entry) => <tr key={entry.id}>
              <td>{entry.timestampLabel}</td><td>{entry.typeLabel}</td><td>{entry.ipLabel}</td>
              <td className="table-primary-text" title={entry.messageLabel}>{entry.messageLabel}</td>
            </tr>)}
            {payload.activityHistory.length === 0 && <tr className="empty-row"><td colSpan={4} className="empty">No matching activity entries.</td></tr>}
          </tbody>
        </table></div>
      </section>

      {!item.active && <section className="dashboard-panel file-request-delete-panel">
        <header className="section-heading"><div><h2>Delete Request</h2><p>Deletion is available only after active uploads and pending files are cleared.</p></div></header>
        <div className="form-actions end"><button className="danger icon-text-button" type="button"
          disabled={!payload.canDelete || Boolean(busy)}
          onClick={() => void confirmAndRun({
            title: 'Delete file request', message: 'Permanently delete this file request record?', confirmLabel: 'Delete request',
            key: 'delete', action: () => deleteFileRequest(item.id), fallback: 'File request could not be deleted.',
            after: () => navigate('/admin/file-requests', { replace: true }),
          })}>{icon('fas fa-trash-can')}<span>Delete request</span></button></div>
      </section>}
    </div>
  );
}

function Policy({ label, value, wide = false, muted = false, description = false }: {
  label: string; value: string; wide?: boolean; muted?: boolean; description?: boolean;
}) {
  return <div className={wide ? 'file-request-policy-wide' : undefined}>
    <dt>{label}</dt><dd className={`${description ? 'file-request-policy-description' : ''}${muted ? ' muted' : ''}`.trim()}>{value}</dd>
  </div>;
}

async function copyLink(url: string) {
  if (await window.EnderVault?.copyText(url)) window.EnderVault?.showToast('success', 'File request link copied.');
}

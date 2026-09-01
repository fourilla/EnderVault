import { type FormEvent, useEffect, useRef, useState } from 'react';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { BrowserPagination } from '../shared/browser/BrowserPagination';
import { PageHeader } from '../shared/layout/PageHeader';
import { deleteActivityLog, loadActivityLogs } from './activity-log-api';
import type {
  ActivityLogEntry,
  ActivityLogFilterDraft,
  ActivityLogPayload,
  ActivityLogQuery,
} from './types';
import './activity-logs-app.css';

const filterDraft = (query: ActivityLogQuery): ActivityLogFilterDraft => ({
  text: query.text,
  type: query.type,
  status: query.status,
  order: query.order,
  from: query.from,
  to: query.to,
  size: String(query.size),
});

const setParam = (params: URLSearchParams, name: string, value: string) => {
  if (value) params.set(name, value);
  else params.delete(name);
};

export function ActivityLogsApp() {
  const [payload, setPayload] = useState<ActivityLogPayload | null>(null);
  const [draft, setDraft] = useState<ActivityLogFilterDraft | null>(null);
  const [fileDraft, setFileDraft] = useState('');
  const [locationSearch, setLocationSearch] = useState(window.location.search);
  const [refreshToken, setRefreshToken] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [selectedEntry, setSelectedEntry] = useState<ActivityLogEntry | null>(null);
  const detailDialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const restoreHistory = () => setLocationSearch(window.location.search);
    window.addEventListener('popstate', restoreHistory);
    return () => window.removeEventListener('popstate', restoreHistory);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadActivityLogs(locationSearch, controller.signal)
      .then((nextPayload) => {
        setPayload(nextPayload);
        setDraft(filterDraft(nextPayload.query));
        setFileDraft(nextPayload.selectedFile);
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Activity logs could not be loaded.');
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [locationSearch, refreshToken]);

  useEffect(() => {
    const dialog = detailDialog.current;
    if (selectedEntry && dialog && !dialog.open) dialog.showModal();
  }, [selectedEntry]);

  const navigate = (params: URLSearchParams) => {
    const search = params.toString();
    const nextSearch = search ? `?${search}` : '';
    const nextUrl = `/admin/logs${nextSearch}`;
    if (nextSearch === locationSearch) {
      setRefreshToken((value) => value + 1);
      return;
    }
    window.history.pushState({}, '', nextUrl);
    setLocationSearch(nextSearch);
  };

  const appliedParams = (query = payload?.query) => {
    const params = new URLSearchParams();
    if (!payload || !query) return params;
    setParam(params, 'file', payload.selectedFile);
    setParam(params, 'q', query.text);
    setParam(params, 'type', query.type);
    setParam(params, 'status', query.status);
    setParam(params, 'order', query.order);
    setParam(params, 'from', query.from);
    setParam(params, 'to', query.to);
    params.set('page', String(query.page));
    params.set('size', String(query.size));
    return params;
  };

  const applyFilters = (event: FormEvent) => {
    event.preventDefault();
    if (!payload || !draft) return;
    const params = new URLSearchParams();
    setParam(params, 'file', payload.selectedFile);
    setParam(params, 'q', draft.text.trim());
    setParam(params, 'type', draft.type);
    setParam(params, 'status', draft.status);
    setParam(params, 'order', draft.order);
    setParam(params, 'from', draft.from);
    setParam(params, 'to', draft.to);
    params.set('page', '1');
    params.set('size', draft.size);
    navigate(params);
  };

  const openLogFile = (event: FormEvent) => {
    event.preventDefault();
    if (!payload) return;
    const params = appliedParams();
    setParam(params, 'file', fileDraft);
    params.set('page', '1');
    navigate(params);
  };

  const resetFilters = () => {
    const params = new URLSearchParams();
    if (payload) setParam(params, 'file', payload.selectedFile);
    navigate(params);
  };

  const goToPage = (page: number) => {
    const params = appliedParams();
    params.set('page', String(page));
    navigate(params);
  };

  const deleteSelectedLog = async () => {
    if (!payload?.selectedFileDeletable) return;
    const selected = payload.files.find((file) => file.name === payload.selectedFile);
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Delete activity log',
      message: `Delete ${selected?.label ?? payload.selectedFile}? This cannot be undone.`,
      confirmLabel: 'Delete log',
      danger: true,
    });
    if (!confirmed) return;
    setBusy(true);
    try {
      await deleteActivityLog(payload.selectedFile);
      navigate(new URLSearchParams());
    } catch (reason) {
      toastError(reason, 'Activity log could not be deleted.');
    } finally {
      setBusy(false);
    }
  };

  const closeDetails = () => detailDialog.current?.close();
  const entries = payload?.entries ?? [];

  return (
    <>
      <PageHeader title="Logs" />

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {loading && !payload && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" />
          <span>Loading activity logs...</span>
        </section>
      )}

      {payload && draft && (
        <section className="dashboard-grid logs-layout" aria-label="Activity logs">
          <article className="dashboard-panel logs-panel">
            <header className="section-heading log-panel-heading">
              <div className="log-heading-main">
                <h2>Activity Logs</h2>
                <p>Showing {payload.firstIndex}-{payload.lastIndex} of {payload.matchedCount} matched ({payload.totalCount} total)</p>
              </div>
              <div className="log-heading-actions">
                <form className="log-file-select-form" onSubmit={openLogFile}>
                  <label>
                    <span>Log file</span>
                    <select value={fileDraft} onChange={(event) => setFileDraft(event.target.value)} aria-label="Log file">
                      {payload.files.map((file) => (
                        <option key={file.name} value={file.name}>
                          {file.label} - {file.sizeLabel} - {file.modifiedLabel}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button className="ghost icon-button action-icon" type="submit" disabled={loading}
                    title="Open log file" aria-label="Open log file">
                    {icon('fas fa-folder-open')}
                  </button>
                </form>
                {payload.selectedFileDeletable && (
                  <button className="ghost icon-button action-icon" type="button" disabled={busy}
                    title="Delete selected log file" aria-label="Delete selected log file"
                    onClick={() => void deleteSelectedLog()}>
                    {icon('fas fa-trash-can')}
                  </button>
                )}
              </div>
            </header>

            <form className="log-filter-form" onSubmit={applyFilters}>
              <div className="log-filter-row log-filter-row-primary">
                <label className="log-filter-search">
                  <span>Search</span>
                  <input type="text" value={draft.text} placeholder="Message, path, IP, token"
                    onChange={(event) => setDraft({ ...draft, text: event.target.value })} />
                </label>
                <label className="log-filter-type">
                  <span>Type</span>
                  <select value={draft.type} onChange={(event) => setDraft({ ...draft, type: event.target.value })}>
                    <option value="">All types</option>
                    {payload.typeOptions.map((type) => <option key={type} value={type}>{type}</option>)}
                  </select>
                </label>
                <label className="log-filter-status">
                  <span>Status</span>
                  <select value={draft.status} onChange={(event) => setDraft({ ...draft, status: event.target.value })}>
                    <option value="all">All</option>
                    <option value="success">Success</option>
                    <option value="failed">Failed</option>
                  </select>
                </label>
                <label className="log-filter-order">
                  <span>Order</span>
                  <select value={draft.order} onChange={(event) => setDraft({ ...draft, order: event.target.value })}>
                    <option value="newest">Newest first</option>
                    <option value="oldest">Oldest first</option>
                  </select>
                </label>
              </div>
              <div className="log-filter-row log-filter-row-secondary">
                <label className="log-filter-from">
                  <span>From</span>
                  <input type="datetime-local" step="1" value={draft.from}
                    onChange={(event) => setDraft({ ...draft, from: event.target.value })} />
                </label>
                <label className="log-filter-to">
                  <span>To</span>
                  <input type="datetime-local" step="1" value={draft.to}
                    onChange={(event) => setDraft({ ...draft, to: event.target.value })} />
                </label>
                <label className="log-filter-size">
                  <span>Page size</span>
                  <select value={draft.size} onChange={(event) => setDraft({ ...draft, size: event.target.value })}>
                    {payload.pageSizeOptions.map((size) => <option key={size} value={size}>{size}</option>)}
                  </select>
                </label>
                <div className="log-filter-actions">
                  <button className="log-action-button" type="submit" disabled={loading}
                    title="Apply filters" aria-label="Apply filters">
                    {icon('fas fa-check')}<span>Apply</span>
                  </button>
                  <button className="ghost log-action-button" type="button" disabled={loading}
                    title="Clear filters" aria-label="Clear filters" onClick={resetFilters}>
                    {icon('fas fa-arrow-rotate-left')}<span>Reset</span>
                  </button>
                </div>
              </div>
            </form>

            {loading && (
              <div className="browser-load-progress log-refresh-progress" role="status" aria-live="polite">
                <i className="fas fa-spinner fa-spin" aria-hidden="true" /><span>Refreshing logs...</span>
              </div>
            )}

            {entries.length > 0 && (
              <div className="table-wrap log-table-wrap">
                <table className="log-table">
                  <thead><tr>
                    <th>Time</th><th>Status</th><th>Type</th><th>Actor</th><th>IP</th><th>Message</th><th>Details</th>
                  </tr></thead>
                  <tbody>
                    {entries.map((entry, index) => (
                      <tr key={`${entry.id}:${entry.timestampLabel}:${index}`}>
                        <td className="log-time">{entry.timestampLabel}</td>
                        <td><span className={`status-badge ${entry.statusClass}`}>{entry.statusLabel}</span></td>
                        <td><code>{entry.type}</code></td>
                        <td>{entry.actorLabel}</td>
                        <td>{entry.ipLabel}</td>
                        <td className="log-message">
                          <span>{entry.messageLabel}</span>
                          {(entry.pathLabel !== '-' || entry.targetPathLabel !== '-') && (
                            <small>{entry.pathLabel} -&gt; {entry.targetPathLabel}</small>
                          )}
                        </td>
                        <td className="log-details-cell">
                          <button className="ghost icon-button action-icon" type="button"
                            title="View full log entry" aria-label="View full log entry"
                            onClick={() => setSelectedEntry(entry)}>
                            {icon('fas fa-circle-info')}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {!loading && entries.length === 0 && (
              <p className="dashboard-empty">
                {payload.totalCount > 0 ? 'No log entries match the current filters.' : 'No activity entries yet.'}
              </p>
            )}

            <BrowserPagination
              page={{
                number: payload.page,
                totalPages: payload.totalPages,
              }}
              onPageChange={goToPage}
              ariaLabel="Log pages"
              disabled={loading}
            />
          </article>
        </section>
      )}

      <dialog ref={detailDialog} className="admin-detail-modal" aria-labelledby="logDetailTitle"
        onClose={() => setSelectedEntry(null)}
        onClick={(event) => { if (event.target === event.currentTarget) closeDetails(); }}>
        <article className="admin-detail-modal-card">
          <header className="admin-detail-modal-header">
            <div>
              <h2 id="logDetailTitle">{selectedEntry?.type ?? 'Log Entry'}</h2>
              <p>{selectedEntry ? `${selectedEntry.timestampLabel} · ${selectedEntry.statusLabel}` : '-'}</p>
            </div>
            <button className="ghost icon-button action-icon admin-detail-modal-close" type="button"
              title="Close details" aria-label="Close details" onClick={closeDetails}>
              {icon('fas fa-xmark')}
            </button>
          </header>
          <p className="log-detail-line">{selectedEntry?.detailLine ?? '-'}</p>
          <dl className="admin-detail-grid">
            <div><dt>ID</dt><dd>{selectedEntry?.id ?? '-'}</dd></div>
            <div><dt>Actor</dt><dd>{selectedEntry?.actorLabel ?? '-'}</dd></div>
            <div><dt>IP</dt><dd>{selectedEntry?.ipLabel ?? '-'}</dd></div>
            <div><dt>Message</dt><dd>{selectedEntry?.messageLabel ?? '-'}</dd></div>
            <div><dt>Path</dt><dd>{selectedEntry?.pathLabel ?? '-'}</dd></div>
            <div><dt>Target</dt><dd>{selectedEntry?.targetPathLabel ?? '-'}</dd></div>
            <div><dt>Metadata</dt><dd>{selectedEntry?.metadataLabel ?? '-'}</dd></div>
          </dl>
        </article>
      </dialog>
    </>
  );
}

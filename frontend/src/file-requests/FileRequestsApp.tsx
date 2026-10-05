import { LoadingState } from '../shared/layout/LoadingState';
import { StableTable } from '../shared/browser/StableTable';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { PathLink } from '../shared/browser/PathLink';
import { formatBytes } from '../shared/format-bytes';
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useRouteSearch } from '../app/RouteSearch';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { SelectionHeader } from '../shared/browser/SelectionHeader';
import { useItemSelection } from '../shared/browser/useItemSelection';
import { useSelectionShortcuts } from '../shared/browser/useSelectionShortcuts';
import { useBrowserContextMenu } from '../shared/browser/useBrowserContextMenu';
import { useLocationGuard } from '../shared/browser/ListingHistoryContext';
import { ListItemActions, useListItemActions } from '../shared/browser/ListItemActions';
import { ListItemSelectionActions } from '../shared/browser/ListItemSelectionActions';
import { listItemMenuActions, type ListItemBulkResult } from '../shared/browser/list-item-actions';
import { fileRequestItemIdentity, fileRequestItemKey, fileRequestListActions } from './file-request-list-actions';
import {
  createFileRequest,
  deleteExpiredFileRequests,
  loadFileRequests,
} from './file-request-api';
import type { FileRequestCreateValues, FileRequestItem, FileRequestListPayload } from './types';

const valuesFrom = (payload: FileRequestListPayload): FileRequestCreateValues => ({
  title: payload.defaults.title,
  description: payload.defaults.description,
  destinationPath: payload.defaults.destinationPath,
  uploaderNamePolicy: payload.defaults.uploaderNamePolicy,
  expirationDays: String(payload.defaults.expirationDays),
  maxFileSizeGb: payload.defaults.maxFileSizeGb,
  maxTotalGb: payload.defaults.maxTotalGb,
  maxFiles: String(payload.defaults.maxFiles),
  allowedExtensions: payload.defaults.allowedExtensions,
  customToken: '',
});

const emptyRequests: FileRequestItem[] = [];
const keepRowClick = () => {};

export function FileRequestsApp() {
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const activeQuery = searchParams.get('q') ?? '';
  const createContext = JSON.stringify([searchParams.get('destinationPath') ?? '', searchParams.get('copyFrom') ?? '']);
  const [snapshot, setSnapshot] = useState<{ search: string; context: string; payload: FileRequestListPayload } | null>(null);
  const [values, setValues] = useState<FileRequestCreateValues | null>(null);
  const [feedback, setFeedback] = useState<{ search: string; message: string } | null>(null);
  const [bulkFeedback, setBulkFeedback] = useState<{ context: string; failures: ListItemBulkResult['results'] } | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [busy, setBusy] = useState('');
  const [query, setQuery] = useState(activeQuery);
  const loadedContext = useRef<string | null>(null);
  const payload = snapshot?.context === createContext ? snapshot.payload : null;
  const requests = snapshot?.search === location.search ? snapshot.payload.requests : null;
  const error = feedback?.search === location.search ? feedback.message : '';
  const listRef = useRef<HTMLElement>(null);
  const pageBusy = useRef(false);
  const items = requests ?? emptyRequests;
  const selectable = requests !== null && !error;
  const isCurrent = useLocationGuard();
  const reload = () => setRefreshToken((value) => value + 1);
  const selection = useItemSelection({ items, enabled: selectable, locationKey: location.search,
    itemKey: fileRequestItemKey, openItem: keepRowClick });
  const actions = useListItemActions({ items, definitions: fileRequestListActions(navigate), enabled: selectable,
    blocked: () => pageBusy.current, contextKey: location.key, isCurrent,
    itemKey: fileRequestItemKey, itemIdentity: fileRequestItemIdentity, reload,
    selectedIds: () => [...selection.selectedRef.current],
    bulkResolved: (result, actionId) => {
      const succeeded = new Set(result.results.filter((item) => item.status === 'APPLIED').map((item) => item.id));
      selection.setSelected((current) => new Set([...current].filter((id) => !succeeded.has(id))));
      if (actionId === 'request-delete') setSnapshot((current) => current ? { ...current,
        payload: { ...current.payload, requests: current.payload.requests.filter((item) => !succeeded.has(item.id)) } } : current);
      setBulkFeedback({ context: location.key, failures: result.results.filter((item) => item.status !== 'APPLIED') });
    } });
  const menuContent = useMemo(() => ({ items, error, selected: selection.selected }), [items, error, selection.selected]);
  useBrowserContextMenu({ menuId: 'fileRequestsContextMenu', pageScope: 'file-requests-react', entries: () => items,
    itemKey: fileRequestItemKey, keyAttribute: 'data-request-id',
    selectedRef: selection.selectedRef, setSelected: selection.setSelected,
    actions: () => listItemMenuActions(actions, fileRequestItemKey), contentKey: menuContent, contextKey: location.search,
    errorMessage: 'The file request action failed.' });
  useSelectionShortcuts({ enabled: selectable && items.length > 0, contextKey: location.search,
    selectedCount: selection.selectedItems.length, selectAll: selection.selectAll,
    clearSelection: selection.clearSelection, scope: () => listRef.current });

  useEffect(() => {
    const controller = new AbortController();
    setFeedback(null);
    void loadFileRequests(location.search, controller.signal)
      .then((next) => {
        if (controller.signal.aborted) return;
        setSnapshot({ search: location.search, context: createContext, payload: next });
        if (loadedContext.current !== createContext) {
          setValues(valuesFrom(next));
          loadedContext.current = createContext;
        }
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setFeedback({ search: location.search,
            message: reason instanceof Error ? reason.message : 'File requests could not be loaded.' });
        }
      });
    return () => controller.abort();
  }, [location.search, createContext, refreshToken]);

  useEffect(() => setQuery(activeQuery), [activeQuery]);
  const search = (event: FormEvent) => {
    event.preventDefault();
    const next = new URLSearchParams(searchParams);
    const trimmed = query.trim();
    if (trimmed) next.set('q', trimmed);
    else next.delete('q');
    setSearchParams(next);
  };
  useRouteSearch({ label: 'Search file requests', placeholder: 'Search titles or destinations',
    appliedQuery: activeQuery, value: query, onChange: setQuery, onSubmit: search, schemaScope: 'file-requests',
    onReset: () => {
      if (!activeQuery) return;
      const next = new URLSearchParams(searchParams);
      next.delete('q');
      setSearchParams(next);
    } });

  const change = (name: keyof FileRequestCreateValues, value: string) => {
    setValues((current) => current ? { ...current, [name]: value } : current);
  };

  const create = async (event: FormEvent) => {
    event.preventDefault();
    if (!values || pageBusy.current || actions.isBusy() || !isCurrent()) return;
    pageBusy.current = true;
    setBusy('create');
    try {
      const response = await createFileRequest(values);
      if (isCurrent()) {
        if (response.redirectUrl) navigate(response.redirectUrl);
        else reload();
      }
    } catch (reason) {
      toastError(reason, 'File request could not be created.');
    } finally {
      pageBusy.current = false;
      setBusy('');
    }
  };

  const mutate = async (key: string, action: () => Promise<unknown>, fallback: string) => {
    if (pageBusy.current || actions.isBusy() || !isCurrent()) return;
    pageBusy.current = true;
    setBusy(key);
    try {
      await action();
      if (isCurrent()) reload();
    } catch (reason) {
      toastError(reason, fallback);
    } finally {
      pageBusy.current = false;
      setBusy('');
    }
  };

  const deleteExpired = async () => {
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Delete expired requests',
      message: 'Delete expired requests that have no active uploads or pending files?',
      confirmLabel: 'Delete expired',
      danger: true,
    });
    if (confirmed) await mutate('expired', deleteExpiredFileRequests, 'Expired file requests could not be deleted.');
  };

  const failures = new Map(bulkFeedback && bulkFeedback.context === location.key
    ? bulkFeedback.failures.map((item) => [item.id, item.message]) : []);

  return (
    <div className="dashboard-workspace file-requests-workspace">
      <PageHeader title="File Requests" />
      {error && <PageErrorPanel title="File requests unavailable" message={error} stale={requests !== null}
        actions={<button type="button" className="icon-text-button" onClick={reload}>
          {icon('fas fa-arrows-rotate')}<span>Retry</span>
        </button>} />}
      {!payload && !error && (
        <LoadingState label="Loading file requests..." />
      )}
      {payload && values && (
        <>
          <section className={`dashboard-panel${payload.enabled ? '' : ' is-disabled'}`}>
            <header className="section-heading">
              <div><h2>Create Request</h2><p>Issue a capability link that lets its holder upload into one vault directory.</p></div>
              <span className={`status-badge ${payload.enabled ? 'active' : 'expired'}`}>
                {payload.enabled ? 'Enabled' : 'Disabled'}
              </span>
            </header>
            <form className="file-request-form" onSubmit={(event) => void create(event)}>
              <fieldset disabled={!payload.enabled || Boolean(busy) || actions.isBusy()}>
                <div className="file-request-field-grid">
                  <label><span>Title</span><input required maxLength={120} placeholder="Upload files"
                    value={values.title} onChange={(event) => change('title', event.target.value)} /></label>
                  <label className="file-request-description-field"><span>Description</span>
                    <textarea maxLength={2000} rows={3} placeholder="Optional instructions for the uploader"
                      value={values.description} onChange={(event) => change('description', event.target.value)} /></label>
                  <label><span>Destination</span><span className="input-action-field">
                    <input id="fileRequestDestination" placeholder="Vault root" autoComplete="off"
                      value={values.destinationPath}
                      onInput={(event) => change('destinationPath', event.currentTarget.value)}
                      onChange={(event) => change('destinationPath', event.target.value)} />
                    <button className="input-action-button" type="button" data-directory-picker-open
                      data-directory-picker-target="fileRequestDestination" title="Browse directories" aria-label="Browse directories">
                      {icon('fas fa-folder-open')}
                    </button>
                  </span></label>
                  <label><span>Uploader name</span><select value={values.uploaderNamePolicy}
                    onChange={(event) => change('uploaderNamePolicy', event.target.value)}>
                    {payload.uploaderNamePolicies.map((policy) => <option key={policy.value} value={policy.value}>{policy.label}</option>)}
                  </select></label>
                  <NumberField label="Expires after" value={values.expirationDays} suffix="days (0 = never)" min="0" max="365"
                    onChange={(value) => change('expirationDays', value)} />
                  <NumberField label="Maximum file size" value={values.maxFileSizeGb} suffix="GiB" min="0.001" max="20" step="0.001"
                    onChange={(value) => change('maxFileSizeGb', value)} />
                  <NumberField label="Total quota" value={values.maxTotalGb} suffix="GiB" min="0.001" max="100" step="0.001"
                    onChange={(value) => change('maxTotalGb', value)} />
                  <label><span>Maximum files</span><input type="number" required min={1} max={1000}
                    value={values.maxFiles} onChange={(event) => change('maxFiles', event.target.value)} /></label>
                  <label><span>Allowed extensions</span><input placeholder="jpg, png, pdf (blank = all)"
                    value={values.allowedExtensions} onChange={(event) => change('allowedExtensions', event.target.value)} /></label>
                  <label className="file-request-token-field"><span>Custom token</span><input pattern="[A-Za-z0-9_-]+"
                    minLength={payload.customTokenMinLength} maxLength={payload.customTokenMaxLength}
                    disabled={!payload.customTokensEnabled}
                    placeholder={payload.customTokensEnabled ? 'Leave blank for a secure random token' : 'Custom tokens are disabled in Settings'}
                    value={values.customToken} onChange={(event) => change('customToken', event.target.value)} /></label>
                </div>
                {payload.defaults.duplicating && <p className="file-request-duplicate-note">
                  Review this copy before creating it. A new token will be issued and the original request remains unchanged.
                </p>}
                <div className="form-actions end"><button className="icon-text-button" type="submit">
                  {icon('fas fa-inbox')}<span>Create Request</span>
                </button></div>
              </fieldset>
            </form>
          </section>

          <section ref={listRef} className="dashboard-panel" aria-label="Issued requests">
            <header className="section-heading">
              <div><h2>Issued Requests</h2><p>Revoke active links before deleting their records.</p></div>
              {requests && (requests.length > 0 || activeQuery) && <button className="danger" type="button" disabled={Boolean(busy) || actions.isBusy()}
                onClick={() => void deleteExpired()}>Delete expired</button>}
            </header>
            {!requests && !error && <LoadingState label={activeQuery ? 'Searching file requests...' : 'Loading file requests...'} />}
            {requests && (
            <div className="table-wrap compact-table"><StableTable className="file-requests-table"
              columns={['select', 'text', 'text', 'usage', 'restrictions', 'date', 'status', 'actions']} actionCount={3}>
              <thead><tr><SelectionHeader total={items.length} selected={selection.selectedItems.length} disabled={!selectable}
                onChange={(checked) => checked ? selection.selectAll() : selection.clearSelection()}
                label="Select all file requests in this result" />
                <th>Request</th><th>Destination</th><th>Usage</th><th>Restrictions</th><th>Created / Expires</th><th>Status</th><th>Actions</th></tr></thead>
              <tbody>
                {requests.map((item) => <tr key={item.id} data-context-item="true" data-request-id={item.id}
                  className={selection.selected.has(item.id) ? 'is-selected' : undefined} {...selection.itemInteractionProps(item)}>
                  <td className="select-cell"><input type="checkbox" className="row-select-checkbox"
                    checked={selection.selected.has(item.id)} disabled={!selectable} aria-label={`Select ${item.title}`}
                    onChange={(event) => selection.selectItem(item, event.currentTarget.checked)} /></td>
                  <td><Link className="table-primary-text" to={`/admin/file-requests/${item.id}`}><OverflowMarquee text={item.title} /></Link></td>
                  <td><PathLink path={item.destinationPath || ''} directory label={item.destinationLabel} /></td>
                  <td title={item.usageLabel}><div className="table-cell-stack">
                    <span>{item.acceptedFiles} / {item.maxFiles} files</span>
                    <span>{formatBytes(item.acceptedBytes)} / {formatBytes(item.maxTotalBytes)}</span>
                  </div></td>
                  <td><span>{item.fileLimitLabel} each</span><small><OverflowMarquee text={item.extensionsLabel} /></small></td>
                  <td><div className="table-cell-stack">
                    <span title="Created" aria-label={`Created: ${item.createdLabel}`}>
                      <i className="fas fa-calendar-plus" aria-hidden="true" /> {item.createdLabel}
                    </span>
                    <span title="Expires" aria-label={`Expires: ${item.expiresLabel}`}>
                      <i className="fas fa-hourglass-end" aria-hidden="true" /> {item.expiresLabel}
                    </span>
                  </div></td>
                  <td><div className="table-cell-stack"><span className={`status-badge ${item.statusClass}`}>{item.statusLabel}</span>
                    {failures.has(item.id) && <small title={failures.get(item.id)}><OverflowMarquee text={failures.get(item.id)!} /></small>}
                  </div></td>
                  <td><ListItemActions item={item} itemKey={fileRequestItemKey} actions={actions} /></td>
                </tr>)}
                {requests.length === 0 && <tr className="empty-row"><td colSpan={8} className="empty">
                  {activeQuery ? 'No file requests match this search.' : 'No file requests have been issued.'}
                </td></tr>}
              </tbody>
            </StableTable></div>)}
          </section>
        </>
      )}
      {requests && <ListItemSelectionActions items={selection.selectedItems} itemKey={fileRequestItemKey}
        actions={actions} label="Selected file request actions" />}
    </div>
  );
}

function NumberField({ label, value, suffix, min, max, step, onChange }: {
  label: string; value: string; suffix: string; min: string; max: string; step?: string; onChange: (value: string) => void;
}) {
  return <label><span>{label}</span><span className="field-with-suffix">
    <input type="number" required min={min} max={max} step={step} value={value} onChange={(event) => onChange(event.target.value)} />
    <span>{suffix}</span>
  </span></label>;
}

import { LoadingState } from '../shared/layout/LoadingState';
import { StableTable } from '../shared/browser/StableTable';
import { PathLink } from '../shared/browser/PathLink';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { type FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { useRouteSearch } from '../app/RouteSearch';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { ListItemSelectionActions } from '../shared/browser/ListItemSelectionActions';
import { SelectionHeader } from '../shared/browser/SelectionHeader';
import { useItemSelection } from '../shared/browser/useItemSelection';
import { useSelectionShortcuts } from '../shared/browser/useSelectionShortcuts';
import { useBrowserContextMenu } from '../shared/browser/useBrowserContextMenu';
import { useLocationGuard } from '../shared/browser/ListingHistoryContext';
import { ListItemActions, useListItemActions } from '../shared/browser/ListItemActions';
import { listItemMenuActions, type ListItemBulkResult } from '../shared/browser/list-item-actions';
import { shareItemIdentity, shareItemKey, shareListActions } from './share-list-actions';
import { deleteExpiredShares, loadShares } from './share-api';
import type { ShareLink } from './types';

const emptyShares: ShareLink[] = [];
const keepRowClick = () => {};

export function SharedLinksApp() {
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const activeQuery = searchParams.get('q') ?? '';
  const [snapshot, setSnapshot] = useState<{ query: string; shares: ShareLink[] } | null>(null);
  const [feedback, setFeedback] = useState<{ query: string; message: string } | null>(null);
  const [bulkFeedback, setBulkFeedback] = useState<{ context: string; failures: Map<string, string> } | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [busy, setBusy] = useState('');
  const [query, setQuery] = useState(activeQuery);
  const shares = snapshot?.query === activeQuery ? snapshot.shares : null;
  const error = feedback?.query === activeQuery ? feedback.message : '';
  const listRef = useRef<HTMLElement>(null);
  const pageBusy = useRef(false);
  const items = shares ?? emptyShares;
  const selectable = shares !== null && !error;
  const isCurrent = useLocationGuard();
  const reload = () => setRefreshToken((value) => value + 1);
  const selection = useItemSelection({ items, enabled: selectable, locationKey: activeQuery,
    itemKey: shareItemKey, openItem: keepRowClick });
  const actions = useListItemActions({ items, definitions: shareListActions, enabled: selectable,
    blocked: () => pageBusy.current, contextKey: location.key, isCurrent,
    itemKey: shareItemKey, itemIdentity: shareItemIdentity, reload,
    selectedIds: () => [...selection.selectedRef.current],
    bulkResolved: (result: ListItemBulkResult, actionId) => {
      const applied = new Set(result.results.filter(item => item.status === 'APPLIED').map(item => item.id));
      selection.setSelected(new Set([...selection.selectedRef.current].filter(id => !applied.has(id))));
      if (actionId === 'share-delete') setSnapshot(current => current && current.query === activeQuery
        ? { ...current, shares: current.shares.filter(item => !applied.has(item.token)) } : current);
      setBulkFeedback({ context: location.key,
        failures: new Map(result.results.filter(item => item.status !== 'APPLIED').map(item => [item.id, item.message])) });
    } });
  const failures = bulkFeedback && bulkFeedback.context === location.key ? bulkFeedback.failures : new Map<string, string>();
  const menuContent = useMemo(() => ({ items, error, selected: selection.selected }), [items, error, selection.selected]);
  useBrowserContextMenu({ menuId: 'sharedLinksContextMenu', pageScope: 'shares-react', entries: () => items,
    itemKey: shareItemKey, keyAttribute: 'data-share-token',
    selectedRef: selection.selectedRef, setSelected: selection.setSelected,
    actions: () => listItemMenuActions(actions, shareItemKey), contentKey: menuContent, contextKey: activeQuery,
    errorMessage: 'The share link action failed.' });
  useSelectionShortcuts({ enabled: selectable && items.length > 0, contextKey: activeQuery,
    selectedCount: selection.selectedItems.length, selectAll: selection.selectAll,
    clearSelection: selection.clearSelection, scope: () => listRef.current,
    deleteSelection: async (isCurrentSelection) => {
      if (isCurrentSelection()) await actions.runSelected([...selection.selectedRef.current], 'share-delete');
    } });

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

  const run = async (key: string, action: () => Promise<unknown>, fallback: string) => {
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

  return (
    <>
      <PageHeader title="Shared Links" />
      {shares && (shares.length > 0 || activeQuery) && <ListItemSelectionActions
        items={selection.selectedItems} itemKey={shareItemKey} actions={actions} label="Shared link actions"
        pageActions={<button type="button" className="danger icon-button" aria-label="Delete expired links"
          title="Delete expired links from the entire list, not only this search or selection."
          disabled={!selectable || Boolean(busy) || actions.isBusy()}
          onClick={() => void run('expired', deleteExpiredShares, 'Expired links could not be deleted.')}>
          {icon('fas fa-broom')}
        </button>} />}

      <div className="page-feedback-layout">
      {error && <PageErrorPanel title="Shared links unavailable" message={error} stale={shares !== null}
        actions={<button type="button" className="icon-text-button" onClick={reload}>
          {icon('fas fa-arrows-rotate')}<span>Retry</span>
        </button>} />}
      {!shares && !error && (
        <LoadingState label={activeQuery ? 'Searching shared links...' : 'Loading shared links...'} />
      )}
      {shares && (
        <section ref={listRef} className="table-wrap" aria-label="Shared links">
          <StableTable columns={['select', 'text', 'text', 'type', 'date', 'status', 'actions']} actionCount={4}>
            <thead>
              <tr><SelectionHeader total={items.length} selected={selection.selectedItems.length} disabled={!selectable}
                onChange={(checked) => checked ? selection.selectAll() : selection.clearSelection()}
                label="Select all shared links in this result" />
                <th>Link</th><th>Target</th><th>Type</th><th>Created / Expires</th><th>Status</th><th>Actions</th></tr>
            </thead>
            <tbody>
              {shares.map((share) => (
                <tr key={share.token} data-share-status={share.statusClass} data-context-item="true" data-share-token={share.token}
                  className={selection.selected.has(share.token) ? 'is-selected' : undefined} {...selection.itemInteractionProps(share)}>
                  <td className="select-cell"><input type="checkbox" className="row-select-checkbox"
                    checked={selection.selected.has(share.token)} disabled={!selectable} aria-label={`Select share for ${share.path}`}
                    onChange={(event) => selection.selectItem(share, event.currentTarget.checked)} /></td>
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
                    {failures.has(share.token) && <OverflowMarquee text={failures.get(share.token)!} />}
                  </div></td>
                  <td>
                    <ListItemActions item={share} itemKey={shareItemKey} actions={actions} />
                  </td>
                </tr>
              ))}
              {shares.length === 0 && <tr className="empty-row"><td colSpan={7} className="empty">
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

import { LoadingState } from '../shared/layout/LoadingState';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { StableTable } from '../shared/browser/StableTable';
import { useTableColumns } from '../shared/browser/useTableColumns';
import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useRouteSearch } from '../app/RouteSearch';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { ListItemActions } from '../shared/browser/ListItemActions';
import { ListItemSelectionActions } from '../shared/browser/ListItemSelectionActions';
import { SelectionHeader } from '../shared/browser/SelectionHeader';
import { useSelectableActionList } from '../shared/browser/useSelectableActionList';
import { trashItemIdentity, trashItemKey, trashListActions } from './trash-list-actions';
import { emptyTrash, loadTrash } from './trash-api';
import type { TrashItem, TrashPayload } from './types';
import './trash-app.css';

const emptyItems: TrashItem[] = [];

export function TrashApp() {
  const table = useTableColumns(['select', 'text', 'text', 'type', 'size', 'date', 'date', 'actions']);
  const [params, setParams] = useSearchParams();
  const activeQuery = params.get('q') ?? '';
  const [snapshot, setSnapshot] = useState<{ query: string; payload: TrashPayload } | null>(null);
  const [loading, setLoading] = useState(true);
  const [feedback, setFeedback] = useState<{ query: string; message: string } | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [busyAction, setBusyAction] = useState('');
  const [query, setQuery] = useState(activeQuery);
  const payload = snapshot?.query === activeQuery ? snapshot.payload : null;
  const error = feedback?.query === activeQuery ? feedback.message : '';
  const items = payload?.items ?? emptyItems;
  const selectable = payload !== null && !error;
  const pageBusy = useRef(false);
  const reload = () => setRefreshToken(value => value + 1);
  const { selection, actions, listRef, isCurrent, failures } = useSelectableActionList({ items, enabled: selectable,
    contextKey: activeQuery, itemKey: trashItemKey, itemIdentity: trashItemIdentity, definitions: trashListActions,
    reload, blocked: () => pageBusy.current, itemLabel: 'trash items', deleteActionId: 'trash-delete',
    menuId: 'trashContextMenu', pageScope: 'trash-react', keyAttribute: 'data-trash-id',
    removeApplied: ids => setSnapshot(current => current && current.query === activeQuery
      ? { ...current, payload: { items: current.payload.items.filter(item => !ids.has(item.id)) } } : current) });

  useEffect(() => setQuery(activeQuery), [activeQuery]);

  useRouteSearch({ label: 'Search trash', placeholder: 'Search trash...', value: query,
    appliedQuery: activeQuery, onChange: setQuery, schemaScope: 'trash',
    onSubmit: event => {
      event.preventDefault();
      const next = new URLSearchParams(params);
      if (query.trim()) next.set('q', query.trim()); else next.delete('q');
      setParams(next);
    },
    onReset: () => {
      if (!activeQuery) return;
      const next = new URLSearchParams(params);
      next.delete('q');
      setParams(next);
    },
  });

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setFeedback(null);
    void loadTrash(controller.signal, activeQuery)
      .then(data => {
        if (!controller.signal.aborted) setSnapshot({ query: activeQuery, payload: data });
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setFeedback({ query: activeQuery,
            message: reason instanceof Error ? reason.message : 'Trash items could not be loaded.' });
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [activeQuery, refreshToken]);

  const empty = async () => {
    if (pageBusy.current || actions.isBusy() || !selectable || !isCurrent()) return;
    pageBusy.current = true;
    setBusyAction('empty');
    try {
      const confirmed = await window.EnderVault?.askConfirmation({ title: 'Empty trash',
        message: 'Permanently delete every item in trash? This cannot be undone.', confirmLabel: 'Empty trash', danger: true });
      if (!confirmed || !isCurrent()) return;
      await emptyTrash();
      if (isCurrent()) reload();
    } catch (reason) {
      toastError(reason, 'Trash could not be emptied.');
    } finally {
      pageBusy.current = false;
      // Release this component's lock even when its Router visit has changed.
      setBusyAction('');
    }
  };

  return (
    <>
      <PageHeader title="Trash" />
      {payload && (items.length > 0 || activeQuery) && <ListItemSelectionActions items={selection.selectedItems}
        itemKey={trashItemKey} actions={actions} label="Trash actions"
        pageActions={<button type="button" className="danger icon-button" title="Empty all trash, not only this search or selection"
          aria-label="Empty trash" disabled={!selectable || Boolean(busyAction) || actions.isBusy()}
          onClick={() => void empty()}>{icon('fas fa-broom')}</button>} />}

      <div className="page-feedback-layout">
      {error && <PageErrorPanel title="Trash unavailable" message={error} stale={payload !== null}
        actions={<button type="button" className="icon-text-button" disabled={loading} onClick={reload}>
          {icon('fas fa-arrows-rotate')}<span>Retry</span>
        </button>} />}
      {!payload && !error && (
        <LoadingState label="Loading trash..." />
      )}
      {payload && items.length > 0 && (
        <section ref={listRef} className="table-wrap" aria-label="Trash items">
            <StableTable columns={table.columns} actionCount={2}>
              <thead>
                <tr>
                  <SelectionHeader total={items.length} selected={selection.selectedItems.length} disabled={!selectable}
                    onChange={checked => checked ? selection.selectAll() : selection.clearSelection()} label="Select all trash items in this result" />
                  <th>Name</th><th>Original path</th><th>Type</th><th>Size</th>
                  <th>Deleted</th><th>Expires</th>{table.showActions && <th>Actions</th>}
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id} data-context-item="true" data-trash-id={item.id}
                    className={selection.selected.has(item.id) ? 'is-selected' : undefined} {...selection.itemInteractionProps(item)}>
                    <td className="select-cell"><input type="checkbox" className="row-select-checkbox" checked={selection.selected.has(item.id)}
                      disabled={!selectable} aria-label={`Select ${item.originalName}`}
                      onChange={event => selection.selectItem(item, event.currentTarget.checked)} /></td>
                    <td>
                      <span className="item-name" title={item.originalName}>
                        {icon(item.directory ? 'fas fa-folder item-icon' : 'fas fa-file item-icon')}
                        <OverflowMarquee text={item.originalName} />
                      </span>
                      {failures.has(item.id) && <small role="status">{failures.get(item.id)}</small>}
                    </td>
                    <td><span className="path-cell"><OverflowMarquee text={item.originalPath} /></span></td>
                    <td>{item.typeLabel}</td>
                    <td>{item.sizeLabel}</td>
                    <td>{item.deletedLabel}</td>
                    <td>{item.expiresLabel}</td>
                    {table.showActions && <td>
                      <ListItemActions item={item} itemKey={trashItemKey} actions={actions} />
                    </td>}
                  </tr>
                ))}
              </tbody>
            </StableTable>
        </section>
      )}
      {payload && items.length === 0 && <p className="empty browser-grid-empty">
        {activeQuery ? 'No trash items match this search.' : 'No trash items.'}
      </p>}
      </div>
    </>
  );
}

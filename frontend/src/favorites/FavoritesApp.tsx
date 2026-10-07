import { LoadingState } from '../shared/layout/LoadingState';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { StableTable } from '../shared/browser/StableTable';
import { useTableColumns } from '../shared/browser/useTableColumns';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppNavigationLink } from '../app/AppNavigationLink';
import { ListItemActions } from '../shared/browser/ListItemActions';
import { ListItemSelectionActions } from '../shared/browser/ListItemSelectionActions';
import { SelectionHeader } from '../shared/browser/SelectionHeader';
import { useSelectableActionList } from '../shared/browser/useSelectableActionList';
import { favoriteItemIdentity, favoriteItemKey, favoriteListActions } from './favorite-list-actions';
import { icon } from '../shared/browser/BrowserEntries';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { loadFavorites } from './favorite-api';
import type { FavoriteEntry, FavoritesPayload } from './types';
import './favorites-app.css';

const emptyItems: FavoriteEntry[] = [];

export function FavoritesApp() {
  const table = useTableColumns(['select', 'text', 'type', 'text', 'date', 'actions']);
  const [payload, setPayload] = useState<FavoritesPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const items = payload?.items ?? emptyItems;
  const selectable = payload !== null && !error;
  const reload = () => setRefreshToken(value => value + 1);
  const { selection, actions, listRef, failures } = useSelectableActionList({ items, enabled: selectable,
    contextKey: 'favorites', itemKey: favoriteItemKey, itemIdentity: favoriteItemIdentity,
    definitions: favoriteListActions(items), reload, itemLabel: 'favorites', deleteActionId: 'favorite-remove',
    menuId: 'favoritesContextMenu', pageScope: 'favorites-react', keyAttribute: 'data-favorite-path',
    removeApplied: ids => setPayload(current => current ? { items: current.items.filter(item => !ids.has(item.path)) } : current) });

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadFavorites(controller.signal)
      .then(data => { if (!controller.signal.aborted) setPayload(data); })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Favorites could not be loaded.');
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [refreshToken]);

  useEffect(() => {
    const refresh = () => { if (!actions.isBusy()) reload(); };
    document.addEventListener('endervault:favorites-changed', refresh);
    return () => document.removeEventListener('endervault:favorites-changed', refresh);
  }, [actions]);

  return (
    <>
      <section className="breadcrumb-panel" aria-label="Favorites heading">
        <div className="breadcrumb-main">
          <p className="breadcrumb-label">Pinned locations</p>
          <nav className="breadcrumbs"><Link className="current" to="/files/favorites">Favorites</Link></nav>
        </div>
      </section>

      {payload && items.length > 0 && <ListItemSelectionActions items={selection.selectedItems}
        itemKey={favoriteItemKey} actions={actions} label="Favorite actions" />}

      <div className="page-feedback-layout">
      {error && <PageErrorPanel title="Favorites unavailable" message={error} stale={payload !== null}
        actions={<button type="button" className="icon-text-button" disabled={loading}
          onClick={() => setRefreshToken((value) => value + 1)}>
          {icon('fas fa-arrows-rotate')}<span>Retry</span>
        </button>} />}
      {loading && !payload && (
        <LoadingState label="Loading favorites..." />
      )}
      {payload && (
        <section ref={listRef} className="dashboard-panel favorites-panel" aria-label="Favorite files and directories">
          <header className="section-heading">
            <h2>Favorite Items</h2>
            <p>{payload.items.length} item(s)</p>
          </header>
          <div className="table-wrap compact-table">
            <StableTable columns={table.columns} actionCount={3}>
              <thead>
                <tr><SelectionHeader total={items.length} selected={selection.selectedItems.length} disabled={!selectable}
                  onChange={checked => checked ? selection.selectAll() : selection.clearSelection()} label="Select all favorites in this list" />
                  <th>Name</th><th>Type</th><th>Target</th><th>Added</th>{table.showActions && <th>Actions</th>}</tr>
              </thead>
              <tbody>
                {items.map(entry => (
                  <tr key={entry.path} data-context-item="true" data-favorite-path={entry.path}
                    className={[entry.hidden ? 'is-hidden-item' : '', selection.selected.has(entry.path) ? 'is-selected' : ''].filter(Boolean).join(' ')}
                    {...selection.itemInteractionProps(entry)}>
                    <td className="select-cell"><input type="checkbox" className="row-select-checkbox" checked={selection.selected.has(entry.path)}
                      disabled={!selectable} aria-label={`Select ${entry.name}`}
                      onChange={event => selection.selectItem(entry, event.currentTarget.checked)} /></td>
                    <td>
                      <div className="table-item-label">
                      <AppNavigationLink className="item-name" href={entry.openUrl}
                        target={entry.openInNewTab ? '_blank' : undefined}
                        rel={entry.openInNewTab ? 'noopener noreferrer' : undefined}
                        title={entry.targetLabel}>
                        <i className={`${entry.iconClass} item-icon`} aria-hidden="true" /><OverflowMarquee text={entry.name} />
                      </AppNavigationLink>
                      {entry.hidden && <span className="status-badge expired hidden-badge">Hidden</span>}
                      </div>
                      {failures.has(entry.path) && <small role="status">{failures.get(entry.path)}</small>}
                    </td>
                    <td>{entry.typeLabel}</td>
                    <td><span className="path-cell"><OverflowMarquee text={entry.targetLabel} /></span></td>
                    <td>{entry.createdLabel}</td>
                    {table.showActions && <td>
                      <ListItemActions item={entry} itemKey={favoriteItemKey} actions={actions} />
                    </td>}
                  </tr>
                ))}
                {payload.items.length === 0 && (
                  <tr className="empty-row"><td colSpan={table.columnCount} className="empty">No favorites yet.</td></tr>
                )}
              </tbody>
            </StableTable>
          </div>
        </section>
      )}
      </div>
    </>
  );
}

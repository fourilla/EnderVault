import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { EntryGrid, EntryTable, icon } from '../shared/browser/BrowserEntries';
import { BrowserPagination } from '../shared/browser/BrowserPagination';
import { ViewOptionsControl } from '../shared/browser/ViewOptionsControl';
import type { BrowserEntry } from '../shared/browser/types';
import { useEntrySelection } from '../shared/browser/useEntrySelection';
import { useNavigationScroll } from '../shared/browser/useNavigationScroll';
import { createFileEntryActions } from '../shared/browser/file-entry-actions';
import { fileEntryMenuActions } from '../shared/browser/file-entry-menu-actions';
import { useBrowserContextMenu } from '../shared/browser/useBrowserContextMenu';
import { useListingRefresh } from '../shared/browser/useListingRefresh';
import { useAdminApp } from '../app/AdminAppContext';
import { useRouteSearch } from '../app/RouteSearch';
import { FloatingPageActions } from '../app/FloatingPageActions';
import { notify, postForm, toastError } from '../shared/api/form-api';
import { canonicalRecentState, loadRecentPayload } from './recent-api';
import { recentHistory } from './recent-history';
import { useListingHistory, useListingSnapshot } from '../shared/browser/ListingHistoryContext';
import type { RecentHistoryState, RecentPayload, RecentSort } from './types';
import './recent-app.css';

export function RecentApp() {
  const routeNavigate = useNavigate();
  const adminApp = useAdminApp();
  const openFile = useCallback((detailUrl: string) => routeNavigate(detailUrl), [routeNavigate]);
  const { state, key: historyKey, remember, navigate: navigateHistory, isCurrent, ready } = useListingHistory(recentHistory);
  const [payload, setPayload] = useListingSnapshot<RecentPayload>(historyKey);
  const [searchText, setSearchText] = useState(state.query);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const stateRef = useRef(state);
  const payloadRef = useRef(payload);
  useNavigationScroll(payload, loading, state.scrollTop, historyKey, ready);
  stateRef.current = state;
  payloadRef.current = payload;

  const effectiveState = useCallback(() => payloadRef.current
    ? canonicalRecentState(stateRef.current, payloadRef.current)
    : stateRef.current, []);

  const navigate = useCallback((next: RecentHistoryState, replace = false) => {
    if (!isCurrent()) return;
    remember(effectiveState());
    const normalized = { ...next, scrollTop: next.scrollTop || 0 };
    navigateHistory(normalized, replace);
  }, [effectiveState, remember, navigateHistory, isCurrent]);

  useEffect(() => {
    if (!isCurrent()) return;
    const controller = new AbortController();
    const requestState = effectiveState();
    setLoading(true);
    setError('');
    void loadRecentPayload(requestState, controller.signal)
      .then((next) => {
        if (!isCurrent() || controller.signal.aborted) return;
        setPayload(next);
        const canonical = canonicalRecentState(requestState, next);
        stateRef.current = canonical;
        remember(canonical);
      })
      .catch((reason: unknown) => {
        if (isCurrent() && !controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Recent items could not be loaded.');
        }
      })
      .finally(() => {
        if (isCurrent() && !controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [state, refreshToken, remember, isCurrent, effectiveState]);

  useEffect(() => {
    setSearchText(state.query);
  }, [historyKey, state.query]);

  const entries = useMemo(
    () => payload ? [...payload.directories, ...payload.entries] : [],
    [payload],
  );
  const openDirectory = useCallback((path: string) => {
    routeNavigate('/files?path=' + encodeURIComponent(path));
  }, [routeNavigate]);
  const selection = useEntrySelection(entries, true, openDirectory,
    [state.query, state.page].join('\u0000'), openFile);
  const reload = useCallback(() => setRefreshToken((current) => current + 1), []);
  useListingRefresh(reload);
  const actions = createFileEntryActions({
    selectedEntries: selection.selectedEntries, setSelected: selection.setSelected, setPayload, reload,
    zipDownloadUrl: '/files/recent/download.zip',
  });

  const removeEntries = async (entries: BrowserEntry[]) => {
    if (entries.length === 0) return;
    try {
      const body = await postForm('/api/v1/recent/remove', {
        paths: entries.map((entry) => entry.path),
        q: state.query,
        page: state.page,
      });
      notify(body);
      selection.setSelected(new Set());
      reload();
    } catch (reason) {
      toastError(reason, 'Recent items could not be removed.');
    }
  };

  const menuActions = fileEntryMenuActions({ actions, browse: openDirectory, openFile,
    createFileRequest: adminApp.bootstrap.capabilities.fileRequests ? (path) => routeNavigate('/admin/file-requests?'
      + new URLSearchParams({ destinationPath: path }).toString()) : undefined });
  menuActions.push({ id: 'remove-from-recent', group: 'history', icon: 'fas fa-clock-rotate-left',
    label: ({ mode, items }) => mode === 'selection' ? `Remove ${items.length} selected from recent` : 'Remove from recent',
    visible: ({ mode }) => mode !== 'background', run: ({ items }) => removeEntries(items) });
  useBrowserContextMenu({
    menuId: 'recentContextMenu', pageScope: 'recent-react', entries: () => entries,
    itemKey: (entry) => entry.path, keyAttribute: 'data-entry-path',
    selectedRef: selection.selectedRef, setSelected: selection.setSelected,
    actions: () => menuActions, contentKey: payload, errorMessage: 'The file action failed.',
  });

  const clearRecent = async () => {
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Clear recent history',
      message: 'Remove every item from recent history?',
      confirmLabel: 'Clear recent',
      danger: true,
    });
    if (!confirmed) return;
    try {
      const body = await postForm('/api/v1/recent/clear', {});
      notify(body);
      selection.setSelected(new Set());
      reload();
    } catch (reason) {
      toastError(reason, 'Recent history could not be cleared.');
    }
  };

  const submitSearch = (event: FormEvent) => {
    event.preventDefault();
    navigate({ ...effectiveState(), query: searchText.trim(), page: 1, scrollTop: 0 });
  };
  const applyPreferences = (updates: Partial<RecentHistoryState>) =>
    navigate({ ...effectiveState(), ...updates, page: 1, scrollTop: 0 });
  const resetPreferences = async () => {
    const body = await postForm('/api/v1/browser-preferences/reset', { target: 'recent', q: state.query });
    notify(body);
    navigate({ ...effectiveState(), page: 1, view: undefined, sort: undefined,
      direction: undefined, hidden: undefined, pageSize: undefined, scrollTop: 0 }, true);
  };
  const preferences = payload?.preferences;
  const current = effectiveState();
  useRouteSearch({ label: 'Search in recent', value: searchText,
    onChange: setSearchText, onSubmit: submitSearch });
  return (
    <>
      <section className="breadcrumb-panel" aria-label="Current path">
        <div className="breadcrumb-main">
          <p className="breadcrumb-label">Virtual location</p>
          <nav className="breadcrumbs"><Link className="current" to="/files/recent">Recent</Link></nav>
        </div>
      </section>

      <FloatingPageActions mode="menu" label="Recent actions and view options" selectedCount={selection.selectedEntries.length}>
        <div className="toolbar-cluster" aria-label="Recent browser controls">
          <div className="toolbar-actions file-actions" aria-label="Recent actions">
            <button className="icon-button" type="button" disabled={selection.selectedEntries.length === 0}
              title="Download selected" aria-label="Download selected" onClick={() => actions.downloadEntries()}>
              {icon('fas fa-file-zipper')}
            </button>
            <button className="icon-button danger" type="button" disabled={selection.selectedEntries.length === 0}
              title="Remove selected from recent" aria-label="Remove selected from recent"
              onClick={() => void removeEntries(selection.selectedEntries)}>
              {icon('fas fa-clock-rotate-left')}
            </button>
            <button className="ghost icon-button" type="button" disabled={!payload || payload.totalItems === 0}
              title="Clear recent" aria-label="Clear recent" onClick={() => void clearRecent()}>
              {icon('fas fa-broom')}
            </button>
          </div>
          <div className="toolbar-actions browser-controls" aria-label="View option controls">
            <button className="ghost icon-button" type="button"
              title={preferences?.view === 'grid' ? 'Switch to table view' : 'Switch to grid view'}
              aria-label={preferences?.view === 'grid' ? 'Switch to table view' : 'Switch to grid view'}
              onClick={() => applyPreferences({ view: preferences?.view === 'grid' ? 'table' : 'grid' })}>
              {icon(preferences?.view === 'grid' ? 'fas fa-bars' : 'fas fa-border-all')}
            </button>
            <ViewOptionsControl<RecentSort> key={historyKey}
              value={{ sort: preferences?.sort || current.sort || 'recent',
                direction: preferences?.direction || current.direction || 'desc',
                hidden: preferences?.hidden || current.hidden || 'hide',
                pageSize: preferences?.pageSize || current.pageSize || 200 }}
              sorts={[{ value: 'recent', label: 'Recent' }, { value: 'name', label: 'Name' },
                { value: 'size', label: 'Size' }, { value: 'modified', label: 'Modified' }, { value: 'type', label: 'Type' }]}
              pageSizes={preferences?.pageSizeOptions || [50, 100, 200, 500]}
              apply={applyPreferences} reset={resetPreferences} />
          </div>
        </div>
      </FloatingPageActions>

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {loading && !payload && <p className="empty browser-grid-empty">Loading recent items...</p>}
      {payload && (
        <>
          {payload.directories.length > 0 && (
            <section className="browser-section" aria-label="Recent directories">
              <header className="section-heading"><h2>Directories ({payload.directories.length})</h2></header>
              <EntryTable entries={payload.directories} showAccessed onBrowse={openDirectory}
                selected={selection.selected} onSelect={selection.selectEntry}
                onFavorite={actions.toggleFavorite} itemInteractionProps={selection.itemInteractionProps} />
            </section>
          )}
          {payload.entries.length > 0 && (
            <section className="browser-section" aria-label="Recent files">
              <header className="section-heading">
                <h2>Files ({payload.page.totalItems})</h2>
                <p>Showing {payload.page.startItem}-{payload.page.endItem}</p>
              </header>
              {payload.preferences.view === 'grid' ? (
                <EntryGrid entries={payload.entries} showAccessed onBrowse={openDirectory}
                  selected={selection.selected} onSelect={selection.selectEntry}
                  itemInteractionProps={selection.itemInteractionProps} />
              ) : (
                <EntryTable entries={payload.entries} showAccessed onBrowse={openDirectory}
                  selected={selection.selected} onSelect={selection.selectEntry}
                  onFavorite={actions.toggleFavorite} itemInteractionProps={selection.itemInteractionProps} />
              )}
            </section>
          )}
          {payload.directories.length === 0 && payload.entries.length === 0 && (
            <p className="empty browser-grid-empty">
              {payload.search.performed ? 'No recent items matched your search.' : 'No recent items yet.'}
            </p>
          )}
          <BrowserPagination page={payload.page}
            onPageChange={(page) => navigate({ ...effectiveState(), page, scrollTop: 0 })}
            ariaLabel="Recent file pages" />
        </>
      )}
    </>
  );
}

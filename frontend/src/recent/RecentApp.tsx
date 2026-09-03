import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { EntryGrid, EntryTable, icon } from '../shared/browser/BrowserEntries';
import { BrowserPagination } from '../shared/browser/BrowserPagination';
import type { BrowserEntry } from '../shared/browser/types';
import { useEntrySelection } from '../shared/browser/useEntrySelection';
import { useNavigationScroll } from '../shared/browser/useNavigationScroll';
import { togglePathFavorite } from '../shared/api/favorite-api';
import { notify, postForm, toastError } from '../shared/api/form-api';
import { canonicalRecentState, loadRecentPayload } from './recent-api';
import {
  defaultRecentState,
  initialRecentState,
  parseRecentState,
  rememberRecentState,
} from './recent-history';
import type { RecentHistoryState, RecentPayload, RecentSort } from './types';
import './recent-app.css';

export function RecentApp() {
  const routeNavigate = useNavigate();
  const openFile = useCallback((detailUrl: string) => routeNavigate(detailUrl), [routeNavigate]);
  const [state, setState] = useState<RecentHistoryState>(() => initialRecentState());
  const [payload, setPayload] = useState<RecentPayload | null>(null);
  const [searchText, setSearchText] = useState(state.query);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const stateRef = useRef(state);
  const payloadRef = useRef(payload);
  const requestScroll = useNavigationScroll(payload, loading, state.scrollTop);
  stateRef.current = state;
  payloadRef.current = payload;

  const effectiveState = useCallback(() => payloadRef.current
    ? canonicalRecentState(stateRef.current, payloadRef.current)
    : stateRef.current, []);

  const navigate = useCallback((next: RecentHistoryState, replace = false) => {
    const current = { ...effectiveState(), scrollTop: Math.max(0, Math.round(window.scrollY)) };
    rememberRecentState(current, true);
    const normalized = { ...next, scrollTop: next.scrollTop || 0 };
    requestScroll(normalized.scrollTop);
    rememberRecentState(normalized, replace);
    setState(normalized);
  }, [effectiveState, requestScroll]);

  useEffect(() => {
    rememberRecentState(state, true);
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadRecentPayload(state, controller.signal)
      .then((next) => {
        if (controller.signal.aborted) return;
        setPayload(next);
        const canonical = canonicalRecentState(state, next);
        stateRef.current = canonical;
        rememberRecentState(canonical, true);
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Recent items could not be loaded.');
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [state, refreshToken]);

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      const restored = parseRecentState(event.state) || defaultRecentState();
      requestScroll(restored.scrollTop);
      setSearchText(restored.query);
      setState(restored);
    };
    const onPageHide = () => rememberRecentState({
      ...effectiveState(), scrollTop: Math.max(0, Math.round(window.scrollY)),
    }, true);
    window.addEventListener('popstate', onPopState);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      window.removeEventListener('popstate', onPopState);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [effectiveState, requestScroll]);

  const entries = useMemo(
    () => payload ? [...payload.directories, ...payload.entries] : [],
    [payload],
  );
  const openDirectory = useCallback((path: string) => {
    routeNavigate('/files?path=' + encodeURIComponent(path));
  }, [routeNavigate]);
  const selection = useEntrySelection(entries, true, openDirectory,
    [state.query, state.page].join('\u0000'), openFile);
  const reload = () => setRefreshToken((current) => current + 1);

  const toggleFavorite = async (entry: BrowserEntry) => {
    try {
      const body = await togglePathFavorite(entry.path);
      const active = Boolean(body.active);
      setPayload((current) => current ? {
        ...current,
        directories: current.directories.map((item) => item.path === entry.path ? { ...item, favorite: active } : item),
        entries: current.entries.map((item) => item.path === entry.path ? { ...item, favorite: active } : item),
      } : current);
    } catch (reason) {
      toastError(reason, 'Favorite could not be updated.');
    }
  };

  const downloadSelected = () => {
    if (selection.selectedEntries.length === 0) return;
    const query = new URLSearchParams();
    selection.selectedEntries.forEach((entry) => query.append('paths', entry.path));
    window.location.assign('/files/recent/download.zip?' + query.toString());
  };

  const removeSelected = async () => {
    if (selection.selectedEntries.length === 0) return;
    try {
      const body = await postForm('/api/v1/recent/remove', {
        paths: selection.selectedEntries.map((entry) => entry.path),
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
    try {
      const body = await postForm('/api/v1/browser-preferences/reset', { target: 'recent', q: state.query });
      notify(body);
      navigate({ ...effectiveState(), page: 1, view: undefined, sort: undefined,
        direction: undefined, hidden: undefined, pageSize: undefined, scrollTop: 0 }, true);
    } catch (reason) {
      toastError(reason, 'View preferences could not be reset.');
    }
  };
  const preferences = payload?.preferences;
  const current = effectiveState();
  return (
    <>
      <section className="breadcrumb-panel" aria-label="Current path">
        <div className="breadcrumb-main">
          <p className="breadcrumb-label">Virtual location</p>
          <nav className="breadcrumbs"><Link className="current" to="/files/recent">Recent</Link></nav>
        </div>
      </section>

      <section className="toolbar" aria-label="Recent tools">
        <form className="search-form" onSubmit={submitSearch}>
          <label className="search-field">
            <span className="visually-hidden">Search in recent</span>
            {icon('fas fa-magnifying-glass')}
            <input value={searchText} onChange={(event) => setSearchText(event.target.value)}
              placeholder="Search in recent" autoComplete="off" />
          </label>
          <button className="icon-button" type="submit" title="Search in recent" aria-label="Search in recent">
            {icon('fas fa-magnifying-glass')}
          </button>
        </form>
        <div className="toolbar-cluster" aria-label="Recent browser controls">
          <div className="toolbar-actions file-actions" aria-label="Recent actions">
            <button className="icon-button" type="button" disabled={selection.selectedEntries.length === 0}
              title="Download selected" aria-label="Download selected" onClick={downloadSelected}>
              {icon('fas fa-file-zipper')}
            </button>
            <button className="icon-button danger" type="button" disabled={selection.selectedEntries.length === 0}
              title="Remove selected from recent" aria-label="Remove selected from recent"
              onClick={() => void removeSelected()}>
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
            <details className="settings-menu">
              <summary className="icon-button menu-summary" title="View options" aria-label="View options">
                {icon('fas fa-ellipsis-vertical')}
              </summary>
              <div className="settings-panel">
                <div className="settings-form sort-form">
                  <label>Sort
                    <select value={preferences?.sort || current.sort || 'recent'}
                      onChange={(event) => applyPreferences({ sort: event.target.value as RecentSort })}>
                      <option value="recent">Recent</option><option value="name">Name</option>
                      <option value="size">Size</option><option value="modified">Modified</option>
                      <option value="type">Type</option>
                    </select>
                  </label>
                  <label>Direction
                    <select value={preferences?.direction || current.direction || 'desc'}
                      onChange={(event) => applyPreferences({ direction: event.target.value as 'asc' | 'desc' })}>
                      <option value="desc">Descending</option><option value="asc">Ascending</option>
                    </select>
                  </label>
                  <label>Files/page
                    <select value={preferences?.pageSize || current.pageSize || 200}
                      onChange={(event) => applyPreferences({ pageSize: Number(event.target.value) })}>
                      {(preferences?.pageSizeOptions || [50, 100, 200, 500]).map((size) =>
                        <option value={size} key={size}>{size}</option>)}
                    </select>
                  </label>
                  <label>Visibility
                    <select value={preferences?.hidden || current.hidden || 'hide'}
                      onChange={(event) => applyPreferences({ hidden: event.target.value as 'show' | 'hide' })}>
                      <option value="hide">Visible only</option><option value="show">Show hidden</option>
                    </select>
                  </label>
                  <button className="ghost icon-text-button" type="button" onClick={() => void resetPreferences()}>
                    {icon('fas fa-rotate-left')}<span>Reset view options</span>
                  </button>
                </div>
              </div>
            </details>
          </div>
        </div>
      </section>

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {loading && !payload && <p className="empty browser-grid-empty">Loading recent items...</p>}
      {payload && (
        <>
          {payload.directories.length > 0 && (
            <section className="browser-section" aria-label="Recent directories">
              <header className="section-heading"><h2>Directories ({payload.directories.length})</h2></header>
              <EntryTable entries={payload.directories} showAccessed onBrowse={openDirectory}
                selected={selection.selected} onSelect={selection.selectEntry}
                onFavorite={toggleFavorite} itemInteractionProps={selection.itemInteractionProps} />
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
                  onFavorite={toggleFavorite} itemInteractionProps={selection.itemInteractionProps} />
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

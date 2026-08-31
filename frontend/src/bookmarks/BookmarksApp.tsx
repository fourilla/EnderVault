import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import { icon } from '../shared/browser/BrowserEntries';
import { useItemSelection } from '../shared/browser/useItemSelection';
import { loadBookmarks } from './bookmark-api';
import { BookmarkDialogs, type DialogKind } from './BookmarkDialogs';
import { BookmarkBreadcrumbs, BookmarkTable } from './BookmarkEntries';
import {
  defaultBookmarkState,
  initialBookmarkState,
  parseBookmarkState,
  rememberBookmarkState,
} from './bookmark-history';
import type { BookmarkEntry, BookmarkHistoryState, BookmarkPayload } from './types';
import { useBookmarkActions } from './useBookmarkActions';
import { useBookmarkContextMenu } from './useBookmarkContextMenu';
import './bookmarks-app.css';

const itemKey = (entry: BookmarkEntry) => entry.id;
const requestKey = (state: BookmarkHistoryState) => state.directoryId + '\u0000' + state.query;

export function BookmarksApp() {
  const [state, setState] = useState<BookmarkHistoryState>(() => initialBookmarkState());
  const [payload, setPayload] = useState<BookmarkPayload | null>(null);
  const [searchText, setSearchText] = useState(state.query);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const [dialog, setDialog] = useState<DialogKind>(null);
  const stateRef = useRef(state);
  const payloadRef = useRef(payload);
  const restoreScrollRef = useRef(state.scrollTop);
  const requestGenerationRef = useRef(0);
  stateRef.current = state;
  payloadRef.current = payload;

  const effectiveState = useCallback((): BookmarkHistoryState => {
    const current = stateRef.current;
    const currentPayload = payloadRef.current;
    if (!currentPayload) return current;
    return {
      ...current,
      directoryId: currentPayload.currentDirectoryId || '',
      query: currentPayload.search.query,
    };
  }, []);

  const persistCurrentScroll = useCallback(() => {
    const current = { ...effectiveState(), scrollTop: Math.max(0, Math.round(window.scrollY)) };
    rememberBookmarkState(current, true);
    stateRef.current = current;
    return current;
  }, [effectiveState]);

  const navigate = useCallback((next: BookmarkHistoryState, replace = false) => {
    persistCurrentScroll();
    const normalized = { ...next, surface: 'bookmarks' as const, version: 1 as const };
    const sameRequest = requestKey(stateRef.current) === requestKey(normalized);
    requestGenerationRef.current += 1;
    payloadRef.current = null;
    setPayload(null);
    setLoading(true);
    restoreScrollRef.current = normalized.scrollTop;
    rememberBookmarkState(normalized, replace || sameRequest);
    setState(normalized);
    if (sameRequest) setRefreshToken((value) => value + 1);
  }, [persistCurrentScroll]);

  const browse = useCallback((directoryId: string) => {
    setSearchText('');
    navigate({ ...effectiveState(), directoryId, query: '', scrollTop: 0 });
  }, [effectiveState, navigate]);

  const openItem = useCallback((entry: BookmarkEntry) => {
    if (entry.type === 'directory') {
      browse(entry.id);
    } else if (entry.primaryNewTab) {
      window.open(entry.primaryUrl, '_blank', 'noopener,noreferrer');
    } else {
      window.EnderVault?.navigate(entry.primaryUrl) || window.location.assign(entry.primaryUrl);
    }
  }, [browse]);

  const entries = payload ? [...payload.directories, ...payload.links] : [];
  const selection = useItemSelection({
    items: entries,
    enabled: true,
    locationKey: requestKey(state),
    itemKey,
    openItem,
  });

  const actions = useBookmarkActions({
    selectedEntries: selection.selectedItems,
    setSelected: selection.setSelected,
    setPayload,
    effectiveState,
    reload: () => setRefreshToken((value) => value + 1),
  });

  const openLinkDialog = useCallback(() => setDialog('link'), []);
  const openBulkDialog = useCallback(() => setDialog('bulk'), []);
  useBookmarkContextMenu({
    payloadRef,
    selectedRef: selection.selectedRef,
    setSelected: selection.setSelected,
    browse,
    actions,
    openLinkDialog,
    openBulkDialog,
  });

  useEffect(() => {
    const generation = ++requestGenerationRef.current;
    rememberBookmarkState(state, true);
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadBookmarks(state, controller.signal)
      .then((nextPayload) => {
        if (controller.signal.aborted || generation !== requestGenerationRef.current) return;
        setPayload(nextPayload);
        const resolved = {
          ...state,
          directoryId: nextPayload.currentDirectoryId || '',
          query: nextPayload.search.query,
        };
        stateRef.current = resolved;
        rememberBookmarkState(resolved, true);
        const currentLabel = nextPayload.breadcrumbs.at(-1)?.label || 'Bookmarks';
        const stickyContext = nextPayload.currentDirectoryId ? {
          targetType: 'BOOKMARK', targetKey: nextPayload.currentDirectoryId,
          surface: 'BROWSER', label: currentLabel,
        } : {
          targetType: 'PAGE', targetKey: 'bookmarks', surface: 'PAGE', label: 'Bookmarks',
        };
        void window.EnderVaultStickyNotes?.setContext(stickyContext);
        document.dispatchEvent(new CustomEvent('endervault:sticky-context-changed', { detail: stickyContext }));
        window.requestAnimationFrame(() => window.scrollTo({
          top: restoreScrollRef.current, behavior: 'auto',
        }));
      })
      .catch((reason: unknown) => {
        if (controller.signal.aborted || generation !== requestGenerationRef.current) return;
        setError(reason instanceof Error ? reason.message : 'Bookmarks could not be loaded.');
      })
      .finally(() => {
        if (!controller.signal.aborted && generation === requestGenerationRef.current) setLoading(false);
      });
    return () => controller.abort();
  }, [state.directoryId, state.query, refreshToken]);

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      const restored = parseBookmarkState(event.state) || defaultBookmarkState();
      requestGenerationRef.current += 1;
      payloadRef.current = null;
      setPayload(null);
      setLoading(true);
      restoreScrollRef.current = restored.scrollTop;
      setSearchText(restored.query);
      setState(restored);
    };
    const onPageHide = () => persistCurrentScroll();
    window.addEventListener('popstate', onPopState);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      window.removeEventListener('popstate', onPopState);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [persistCurrentScroll]);

  useEffect(() => {
    const refreshListing = async (url?: string) => {
      if (url) {
        const target = new URL(url, window.location.href);
        if (target.pathname === '/files/bookmarks') {
          const next = {
            ...effectiveState(),
            directoryId: target.searchParams.get('directory') || '',
            query: target.searchParams.get('q') || '',
            scrollTop: 0,
          };
          navigate(next);
          return;
        }
      }
      setRefreshToken((value) => value + 1);
    };
    window.EnderVaultFileBrowser = {
      refreshListing,
      requestListingRefresh: (url?: string) => void refreshListing(url),
      syncToolbarState: () => undefined,
    };
    document.dispatchEvent(new CustomEvent('endervault:files-ready'));
    return () => { delete window.EnderVaultFileBrowser; };
  }, [effectiveState, navigate]);

  const submitSearch = (event: FormEvent) => {
    event.preventDefault();
    navigate({ ...effectiveState(), query: searchText.trim(), scrollTop: 0 });
  };

  return (
    <>
      <BookmarkBreadcrumbs breadcrumbs={payload?.breadcrumbs || [{ id: null, label: 'Bookmarks' }]}
        browse={browse} />
      <section className="toolbar" aria-label="Bookmark tools">
        <form className="search-form" onSubmit={submitSearch}>
          <label className="search-field">
            <span className="visually-hidden">Search bookmarks</span>
            {icon('fas fa-magnifying-glass')}
            <input value={searchText} onChange={(event) => setSearchText(event.target.value)}
              placeholder="Search bookmarks" autoComplete="off" />
          </label>
          <button className="icon-button" type="submit" title="Search bookmarks" aria-label="Search bookmarks">
            {icon('fas fa-magnifying-glass')}
          </button>
        </form>
        <div className="toolbar-cluster" aria-label="Bookmark controls">
          <div className="toolbar-actions file-actions bookmark-actions" aria-label="Bookmark actions">
            <button className="icon-button" type="button" title="New directory" aria-label="New directory"
              onClick={() => void actions.createDirectory()}>{icon('fas fa-folder-plus')}</button>
            <button className="icon-button" type="button" title="Add link" aria-label="Add link"
              onClick={openLinkDialog}>{icon('fas fa-link')}</button>
            <button className="icon-button" type="button" title="Bulk add links" aria-label="Bulk add links"
              onClick={openBulkDialog}>{icon('fas fa-list-ul')}</button>
            <button className="icon-button danger" type="button" title="Delete selected"
              aria-label="Delete selected" disabled={selection.selectedItems.length === 0}
              onClick={() => void actions.deleteEntries()}>{icon('fas fa-trash-can')}</button>
          </div>
        </div>
      </section>

      <BookmarkDialogs kind={dialog} close={() => setDialog(null)}
        createLink={actions.createLink} bulkAdd={actions.bulkAdd} />

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {loading && !payload && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" />
          <span>{state.query ? 'Searching bookmarks...' : 'Loading bookmarks...'}</span>
        </section>
      )}
      {payload && (
        <>
          {payload.directories.length > 0 && (
            <BookmarkTable entries={payload.directories} heading="Directories" browse={browse}
              selected={selection.selected} select={selection.selectItem}
              itemInteractionProps={selection.itemInteractionProps}
              toggleFavorite={(entry) => void actions.toggleFavorite(entry)}
              refreshMetadata={(entry) => void actions.refreshMetadata(entry)} />
          )}
          {payload.links.length > 0 && (
            <BookmarkTable entries={payload.links} heading="Links" browse={browse}
              selected={selection.selected} select={selection.selectItem}
              itemInteractionProps={selection.itemInteractionProps}
              toggleFavorite={(entry) => void actions.toggleFavorite(entry)}
              refreshMetadata={(entry) => void actions.refreshMetadata(entry)} />
          )}
          {payload.totalItems === 0 && (
            <p className="empty browser-grid-empty">
              {payload.search.performed ? 'No bookmarks matched your search.' : 'No bookmarks found.'}
            </p>
          )}
        </>
      )}
    </>
  );
}

import { EntryGrid, EntryTable } from '../shared/browser/BrowserEntries';
import { BrowserPagination } from '../shared/browser/BrowserPagination';
import type { BrowserEntry, BrowserHistoryState, BrowserPayload } from './types';

export function BrowserListing({
  payload,
  currentState,
  loading,
  error,
  selected,
  browse,
  selectEntry,
  toggleFavorite,
  itemInteractionProps,
  effectiveState,
  navigate,
  jumpToPage,
}: {
  payload: BrowserPayload | null;
  currentState: BrowserHistoryState;
  loading: boolean;
  error: string;
  selected: Set<string>;
  browse: (path: string) => void;
  selectEntry: (entry: BrowserEntry, checked: boolean) => void;
  toggleFavorite: (entry: BrowserEntry) => Promise<void>;
  itemInteractionProps: (entry: BrowserEntry) => Record<string, unknown>;
  effectiveState: () => BrowserHistoryState;
  navigate: (state: BrowserHistoryState) => void;
  jumpToPage: () => Promise<void>;
}) {
  const searchMode = currentState.mode === 'search';
  const searchQuery = payload?.mode === 'search' ? payload.search.query : currentState.query;
  const totalResults = payload
    ? payload.directories.length + payload.page.totalItems
    : 0;
  return (
    <>
      {searchMode && (
        <section className="section-heading">
          <div>
            <h1>Search</h1>
            <p>
              {payload?.mode === 'search'
                ? <>{totalResults} results for <strong>{searchQuery}</strong></>
                : <>Searching for <strong>{searchQuery}</strong></>}
            </p>
          </div>
        </section>
      )}
      {error && <section className="dashboard-panel files-load-error" role="alert">{error}</section>}
      {loading && !payload && (searchMode ? (
        <section className="files-search-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" />
          <span>Searching...</span>
        </section>
      ) : <p className="empty browser-grid-empty">Loading files...</p>)}
      {payload && (
        <>
          {payload.directories.length > 0 && (
            <section className="browser-section" aria-label="Directories">
              <header className="section-heading"><h2>Directories ({payload.directories.length})</h2></header>
              <EntryTable entries={payload.directories} showLocation={searchMode}
                onBrowse={browse} selected={selected}
                onSelect={selectEntry} onFavorite={toggleFavorite} itemInteractionProps={itemInteractionProps} />
            </section>
          )}

          {payload.entries.length > 0 && (
            <section className="browser-section" aria-label="Files">
              <header className="section-heading">
                <h2>Files ({payload.page.totalItems})</h2>
                <p>Showing {payload.page.startItem}-{payload.page.endItem}</p>
              </header>
              {payload.preferences.view === 'grid' ? (
                <EntryGrid entries={payload.entries} showLocation={searchMode}
                  onBrowse={browse} selected={selected}
                  onSelect={selectEntry} itemInteractionProps={itemInteractionProps} />
              ) : (
                <EntryTable entries={payload.entries} showLocation={searchMode}
                  onBrowse={browse} selected={selected}
                  onSelect={selectEntry} onFavorite={toggleFavorite} itemInteractionProps={itemInteractionProps} />
              )}
            </section>
          )}

          {payload.directories.length === 0 && payload.entries.length === 0 && (
            <p className="empty browser-grid-empty">
              {searchMode ? 'No matching items.' : 'This directory is empty.'}
            </p>
          )}
          <BrowserPagination page={payload.page}
            onPageChange={(page) => navigate({ ...effectiveState(), page, scrollTop: 0 })}
            jumpToPage={jumpToPage} />
        </>
      )}
    </>
  );
}

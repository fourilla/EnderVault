import { EntryGrid, EntryTable } from './BrowserEntries';
import { BrowserPagination } from './BrowserPagination';
import type { BrowserEntry, BrowserHistoryState, BrowserPayload } from './types';

export function BrowserListing({
  payload,
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
  return (
    <>
      {payload?.mode === 'search' && (
        <section className="section-heading">
          <div>
            <h1>Search</h1>
            <p>{payload.page.totalItems} results for <strong>{payload.search.query}</strong></p>
          </div>
        </section>
      )}
      {error && <section className="dashboard-panel files-load-error" role="alert">{error}</section>}
      {loading && !payload && <p className="empty browser-grid-empty">Loading files...</p>}
      {payload && (
        <>
          {payload.mode === 'search' && (payload.entries.length > 0 ? (
            <section className="browser-section" aria-label="Search results">
              <EntryTable entries={payload.entries} showLocation selectable={false} onBrowse={browse} selected={selected}
                onSelect={selectEntry} onFavorite={toggleFavorite} itemInteractionProps={itemInteractionProps} />
            </section>
          ) : <p className="empty browser-grid-empty">No matching items.</p>)}

          {payload.mode === 'browse' && payload.directories.length > 0 && (
            <section className="browser-section" aria-label="Directories">
              <header className="section-heading"><h2>Directories ({payload.directories.length})</h2></header>
              <EntryTable entries={payload.directories} onBrowse={browse} selected={selected}
                onSelect={selectEntry} onFavorite={toggleFavorite} itemInteractionProps={itemInteractionProps} />
            </section>
          )}

          {payload.mode === 'browse' && payload.entries.length > 0 && (
            <section className="browser-section" aria-label="Files">
              <header className="section-heading">
                <h2>Files ({payload.page.totalItems})</h2>
                <p>Showing {payload.page.startItem}-{payload.page.endItem}</p>
              </header>
              {payload.preferences.view === 'grid' ? (
                <EntryGrid entries={payload.entries} onBrowse={browse} selected={selected}
                  onSelect={selectEntry} itemInteractionProps={itemInteractionProps} />
              ) : (
                <EntryTable entries={payload.entries} onBrowse={browse} selected={selected}
                  onSelect={selectEntry} onFavorite={toggleFavorite} itemInteractionProps={itemInteractionProps} />
              )}
            </section>
          )}

          {payload.mode === 'browse' && payload.directories.length === 0 && payload.entries.length === 0 && (
            <p className="empty browser-grid-empty">This directory is empty.</p>
          )}
          <BrowserPagination page={payload.page}
            onPageChange={(page) => navigate({ ...effectiveState(), page, scrollTop: 0 })}
            jumpToPage={jumpToPage} />
        </>
      )}
    </>
  );
}

import type { FormEvent } from 'react';
import { icon } from './BrowserEntries';
import type { BrowserEntry, BrowserHistoryState, BrowserPayload } from './types';
import type { FileBrowserActions } from './useFileActions';

export function BrowserToolbar({
  payload,
  currentState,
  searchText,
  setSearchText,
  submitSearch,
  applyPreferences,
  browse,
  selectedEntries,
  actions,
}: {
  payload: BrowserPayload | null;
  currentState: BrowserHistoryState;
  searchText: string;
  setSearchText: (value: string) => void;
  submitSearch: (event: FormEvent) => void;
  applyPreferences: (updates: Partial<BrowserHistoryState>) => void;
  browse: (path: string) => void;
  selectedEntries: BrowserEntry[];
  actions: FileBrowserActions;
}) {
  const preferences = payload?.preferences;
  return (
    <section className="toolbar files-react-toolbar" aria-label="File tools">
      <form className="search-form" onSubmit={submitSearch}>
        <label className="search-field">
          <span className="visually-hidden">Search keyword</span>
          {icon('fas fa-magnifying-glass')}
          <input
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            placeholder="Search current directory"
            autoComplete="off"
          />
        </label>
        <button className="icon-button" type="submit" title="Search" aria-label="Search">
          {icon('fas fa-magnifying-glass')}
        </button>
      </form>

      <div className="toolbar-cluster">
        <div
          className="toolbar-actions file-actions"
          aria-label="File management actions"
          hidden={payload?.mode === 'search'}
        >
          <form
            className="icon-form"
            id="uploadForm"
            data-max-concurrent-uploads={
              document.getElementById('files-root')?.dataset.maxConcurrentUploads || '1'
            }
            data-admission-url={
              '/api/v1/files/upload-sessions?path=' + encodeURIComponent(currentState.path)
            }
          >
            <input type="hidden" name="path" value={currentState.path} readOnly />
            <input
              className="visually-hidden"
              id="fileUploadInput"
              type="file"
              name="files"
              multiple
            />
            <button
              className="icon-button"
              id="uploadButton"
              type="button"
              title="Upload files"
              aria-label="Upload files"
            >
              {icon('fas fa-upload')}
            </button>
          </form>

          <details className="settings-menu file-new-menu">
            <summary className="icon-button menu-summary" title="Create new item" aria-label="Create new item">
              {icon('fas fa-plus')}
            </summary>
            <div className="settings-panel file-new-panel">
              <div className="file-new-actions">
                <button className="ghost icon-text-button" type="button" onClick={() => void actions.createItem(false)}>
                  {icon('fas fa-file-circle-plus')}
                  <span>New file</span>
                </button>
                <button className="ghost icon-text-button" type="button" onClick={() => void actions.createItem(true)}>
                  {icon('fas fa-folder-plus')}
                  <span>New directory</span>
                </button>
              </div>
            </div>
          </details>

          <button className="icon-button" type="button" disabled={selectedEntries.length === 0}
            title="Download selected" aria-label="Download selected"
            onClick={() => actions.downloadEntries()}>
            {icon('fas fa-download')}
          </button>
          <button className="icon-button" type="button" disabled={selectedEntries.length === 0}
            title="Compress selected to ZIP" aria-label="Compress selected to ZIP"
            onClick={() => void actions.compressEntries()}>
            {icon('fas fa-file-zipper')}
          </button>
          <button className="icon-button" type="button" disabled={selectedEntries.length === 0}
            title="Add selected to transfer buffer" aria-label="Add selected to transfer buffer"
            onClick={() => void actions.addEntriesToBuffer()}>
            {icon('fas fa-layer-group')}
          </button>
          <button className="icon-button danger" type="button" disabled={selectedEntries.length === 0}
            title="Delete selected" aria-label="Delete selected"
            onClick={() => void actions.moveEntriesToTrash()}>
            {icon('fas fa-trash-can')}
          </button>
        </div>
        {payload?.mode === 'search' && (
          <button className="ghost icon-text-button files-exit-search" type="button"
            onClick={() => browse(payload.path)}>
            {icon('fas fa-xmark')}
            <span>Exit search</span>
          </button>
        )}
        <div className="toolbar-actions browser-controls">
          <button
            className="ghost icon-button"
            type="button"
            title={preferences?.view === 'grid' ? 'Switch to table view' : 'Switch to grid view'}
            aria-label={preferences?.view === 'grid' ? 'Switch to table view' : 'Switch to grid view'}
            onClick={() => applyPreferences({ view: preferences?.view === 'grid' ? 'table' : 'grid' })}
          >
            {icon(preferences?.view === 'grid' ? 'fas fa-bars' : 'fas fa-border-all')}
          </button>
          <details className="settings-menu">
            <summary className="icon-button menu-summary" title="View options" aria-label="View options">
              {icon('fas fa-ellipsis-vertical')}
            </summary>
            <div className="settings-panel">
              <div className="settings-form sort-form">
                <label>
                  Sort
                  <select value={preferences?.sort || currentState.sort || 'name'}
                    onChange={(event) => applyPreferences({ sort: event.target.value as BrowserHistoryState['sort'] })}>
                    <option value="name">Name</option>
                    <option value="size">Size</option>
                    <option value="modified">Modified</option>
                    <option value="type">Type</option>
                  </select>
                </label>
                <label>
                  Direction
                  <select value={preferences?.direction || currentState.direction || 'asc'}
                    onChange={(event) => applyPreferences({ direction: event.target.value as BrowserHistoryState['direction'] })}>
                    <option value="asc">Ascending</option>
                    <option value="desc">Descending</option>
                  </select>
                </label>
                <label>
                  Files/page
                  <select value={preferences?.pageSize || currentState.pageSize || 200}
                    onChange={(event) => applyPreferences({ pageSize: Number(event.target.value) })}>
                    {(preferences?.pageSizeOptions || [50, 100, 200, 500]).map((size) => (
                      <option value={size} key={size}>{size}</option>
                    ))}
                  </select>
                </label>
                <label>
                  Visibility
                  <select value={preferences?.hidden || currentState.hidden || 'hide'}
                    onChange={(event) => applyPreferences({ hidden: event.target.value as BrowserHistoryState['hidden'] })}>
                    <option value="hide">Visible only</option>
                    <option value="show">Show hidden</option>
                  </select>
                </label>
                <button className="ghost icon-text-button files-reset-preferences" type="button"
                  onClick={() => void actions.resetPreferences()}>
                  {icon('fas fa-arrow-rotate-left')}
                  <span>Reset view options</span>
                </button>
              </div>
            </div>
          </details>
        </div>
      </div>
    </section>
  );
}

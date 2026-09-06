import { type FormEvent, useRef } from 'react';
import { useUploadManager } from '../app/uploads/UploadManagerContext';
import { collectFileList } from '../app/uploads/collect-uploads';
import { icon } from '../shared/browser/BrowserEntries';
import type { BrowserEntry, BrowserHistoryState, BrowserPayload, BrowserView } from './types';
import type { FileBrowserActions } from './useFileActions';

export function BrowserToolbar({
  payload,
  currentState,
  searchText,
  setSearchText,
  submitSearch,
  applyView,
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
  applyView: (view: BrowserView) => Promise<void>;
  applyPreferences: (updates: Partial<BrowserHistoryState>) => void;
  browse: (path: string) => void;
  selectedEntries: BrowserEntry[];
  actions: FileBrowserActions;
}) {
  const uploadManager = useUploadManager();
  const uploadInputRef = useRef<HTMLInputElement>(null);
  const directoryInputRef = useRef<HTMLInputElement>(null);
  const preferences = payload?.preferences;
  const searchMode = currentState.mode === 'search';
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
        >
            <input
              className="visually-hidden"
              id="fileUploadInput"
              type="file"
              name="files"
              multiple
              ref={uploadInputRef}
              onChange={(event) => {
                try {
                  uploadManager.startSelection(collectFileList([...(event.currentTarget.files ?? [])]), currentState.path);
                } catch (reason) {
                  window.EnderVault?.showToast('error', reason instanceof Error ? reason.message : 'Could not read files.');
                } finally { event.currentTarget.value = ''; }
              }}
            />

          <input className="visually-hidden" id="directoryUploadInput" type="file" multiple
            {...{ webkitdirectory: '' }} ref={directoryInputRef}
            onChange={(event) => {
              try {
                const selection = collectFileList([...(event.currentTarget.files ?? [])]);
                if (selection.files.length) throw new Error('This browser did not expose directory paths.');
                uploadManager.startSelection(selection, currentState.path);
                window.EnderVault?.showToast('info', 'Directory pickers cannot preserve empty directories.');
              } catch (reason) {
                window.EnderVault?.showToast('error', reason instanceof Error ? reason.message : 'Could not read directory.');
              } finally { event.currentTarget.value = ''; }
            }} />
          <details className="settings-menu file-new-menu" hidden={searchMode}>
            <summary className="icon-button menu-summary" title="Upload" aria-label="Upload">
              {icon('fas fa-upload')}
            </summary>
            <div className="settings-panel file-new-panel">
              <div className="file-new-actions">
                <button className="ghost icon-text-button" id="uploadButton" type="button"
                  onClick={(event) => {
                    event.currentTarget.closest('details')?.removeAttribute('open');
                    uploadInputRef.current?.click();
                  }}>
                  {icon('fas fa-file-arrow-up')}
                  <span>Upload files</span>
                </button>
                <button className="ghost icon-text-button" id="directoryUploadButton" type="button"
                  onClick={(event) => {
                    event.currentTarget.closest('details')?.removeAttribute('open');
                    directoryInputRef.current?.click();
                  }}>
                  {icon('fas fa-folder-open')}
                  <span>Upload directory</span>
                </button>
              </div>
            </div>
          </details>

          <details className="settings-menu file-new-menu" hidden={searchMode}>
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
        {searchMode && (
          <button className="ghost icon-text-button files-exit-search" type="button"
            onClick={() => browse(currentState.path)}>
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
            onClick={() => void applyView(preferences?.view === 'grid' ? 'table' : 'grid')}
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

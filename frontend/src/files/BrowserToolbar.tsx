import { type FormEvent, useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { AppDialog } from '../shared/dialogs/AppDialog';
import { ViewOptionsControl } from '../shared/browser/ViewOptionsControl';
import { CreateItemDialog } from './CreateItemDialog';
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
  const [dialog, setDialog] = useState<'upload' | 'create' | null>(null);
  useEffect(() => setDialog(null), [currentState.path, currentState.mode, currentState.query]);
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
          <button className="icon-button" hidden={searchMode} type="button" title="Upload" aria-label="Upload" onClick={() => setDialog('upload')}>
            {icon('fas fa-upload')}
          </button>
          <button className="icon-button" hidden={searchMode} type="button" title="Create new item" aria-label="Create new item" onClick={() => setDialog('create')}>
            {icon('fas fa-plus')}
          </button>

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
          <ViewOptionsControl<NonNullable<BrowserHistoryState['sort']>>
            key={[currentState.mode, currentState.path, currentState.query].join(':')}
            value={{ sort: preferences?.sort || currentState.sort || 'name',
              direction: preferences?.direction || currentState.direction || 'asc',
              hidden: preferences?.hidden || currentState.hidden || 'hide',
              pageSize: preferences?.pageSize || currentState.pageSize || 200 }}
            sorts={[{ value: 'name', label: 'Name' }, { value: 'size', label: 'Size' },
              { value: 'modified', label: 'Modified' }, { value: 'type', label: 'Type' }]}
            pageSizes={preferences?.pageSizeOptions || [50, 100, 200, 500]}
            apply={applyPreferences} reset={actions.resetPreferences} />
        </div>
      </div>
      <AppDialog open={dialog === 'upload'} onDismiss={() => setDialog(null)} labelledBy="uploadDialogTitle"
        className="text-input-dialog" dismissOnBackdrop>
        <div className="text-input-card">
          <header className="text-input-header"><h2 id="uploadDialogTitle">Upload</h2>
            <button className="ghost icon-button" type="button" title="Close" aria-label="Close" onClick={() => setDialog(null)}>{icon('fas fa-xmark')}</button>
          </header>
          <div className="dialog-upload-options">
            <button className="ghost icon-text-button" id="uploadButton" type="button" onClick={() => {
              flushSync(() => setDialog(null));
              uploadInputRef.current?.click();
            }}>{icon('fas fa-file-arrow-up')}<span>Upload files</span></button>
            <button className="ghost icon-text-button" id="directoryUploadButton" type="button" onClick={() => {
              flushSync(() => setDialog(null));
              directoryInputRef.current?.click();
            }}>{icon('fas fa-folder-open')}<span>Upload directory</span></button>
          </div>
        </div>
      </AppDialog>
      {dialog === 'create' && <CreateItemDialog close={() => setDialog(null)} create={actions.createNamedItem} />}
    </section>
  );
}

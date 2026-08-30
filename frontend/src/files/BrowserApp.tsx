import { useEffect } from 'react';
import { BrowserBreadcrumbs } from './BrowserBreadcrumbs';
import { icon } from '../shared/browser/BrowserEntries';
import { BrowserListing } from './BrowserListing';
import { BrowserToolbar } from './BrowserToolbar';
import { TransferBufferPanel } from './TransferBufferPanel';
import { useBrowserNavigation } from './useBrowserNavigation';
import { useEntrySelection } from '../shared/browser/useEntrySelection';
import { useFileActions } from './useFileActions';
import { useFileContextMenu } from './useFileContextMenu';
import { useAdminApp } from '../app/AdminAppContext';
import { useUploadManager } from '../app/uploads/UploadManagerContext';
import { useAdminUploadDropzone } from './useAdminUploadDropzone';
import './files-app.css';

export function BrowserApp() {
  const adminApp = useAdminApp();
  const uploadManager = useUploadManager();
  const navigation = useBrowserNavigation();
  const {
    state,
    stateRef,
    payload,
    payloadRef,
    setPayload,
    loading,
    error,
    searchText,
    setSearchText,
    effectiveState,
    navigate,
    browse,
    applyView,
    applyPreferences,
    submitSearch,
    reload,
  } = navigation;
  const selection = useEntrySelection(
    payload ? [...payload.directories, ...payload.entries] : [],
    Boolean(payload),
    browse,
    [state.mode, state.path, state.query, state.page].join('\u0000'),
  );
  const actions = useFileActions({
    selectedEntries: selection.selectedEntries,
    setSelected: selection.setSelected,
    setPayload,
    effectiveState,
    navigate,
    reload,
  });
  const currentState = effectiveState();

  useAdminUploadDropzone(uploadManager, currentState.path);

  useEffect(() => {
    void actions.loadTransferBuffer();
  }, [actions.loadTransferBuffer]);

  useFileContextMenu({
    payloadRef,
    stateRef,
    selectedRef: selection.selectedRef,
    transferBufferRef: actions.transferBufferRef,
    setSelected: selection.setSelected,
    browse,
    actions,
    fileRequestsEnabled: adminApp.bootstrap.capabilities.fileRequests,
  });

  return (
    <>
      <div className="drop-upload-overlay" id="dropUploadOverlay" aria-hidden="true">
        <div className="drop-upload-panel">
          {icon('fas fa-cloud-arrow-up')}
          <strong>Drop files to upload</strong>
          <span>Directories are not supported yet.</span>
        </div>
      </div>

      <BrowserBreadcrumbs payload={payload} currentState={currentState} browse={browse} />
      <BrowserToolbar
        payload={payload}
        currentState={currentState}
        searchText={searchText}
        setSearchText={setSearchText}
        submitSearch={submitSearch}
        applyView={applyView}
        applyPreferences={applyPreferences}
        browse={browse}
        selectedEntries={selection.selectedEntries}
        actions={actions}
      />
      <TransferBufferPanel
        transferBuffer={actions.transferBuffer}
        mode={currentState.mode}
        path={currentState.path}
        actions={actions}
      />
      <BrowserListing
        payload={payload}
        currentState={currentState}
        loading={loading}
        error={error}
        selected={selection.selected}
        browse={browse}
        selectEntry={selection.selectEntry}
        toggleFavorite={actions.toggleFavorite}
        itemInteractionProps={selection.itemInteractionProps}
        effectiveState={effectiveState}
        navigate={navigate}
      />
    </>
  );
}

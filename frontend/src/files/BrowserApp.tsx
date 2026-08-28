import { useEffect } from 'react';
import { BrowserBreadcrumbs } from './BrowserBreadcrumbs';
import { icon } from './BrowserEntries';
import { BrowserListing } from './BrowserListing';
import { BrowserToolbar } from './BrowserToolbar';
import { TransferBufferPanel } from './TransferBufferPanel';
import { useBrowserNavigation } from './useBrowserNavigation';
import { useEntrySelection } from './useEntrySelection';
import { useFileActions } from './useFileActions';
import { useFileContextMenu } from './useFileContextMenu';
import './files-app.css';

export function BrowserApp() {
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
    applyPreferences,
    submitSearch,
    reload,
  } = navigation;
  const selection = useEntrySelection(
    payload,
    payloadRef,
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
  });

  const jumpToPage = async () => {
    if (!payload || payload.page.totalPages <= 1) return;
    const requested = await window.EnderVault?.askTextInput({
      title: 'Go to page',
      message: `Enter a page from 1 to ${payload.page.totalPages}. Larger values open the last page.`,
      label: 'Page',
      initialValue: String(payload.page.number),
      confirmLabel: 'Go',
    });
    if (requested == null) return;
    const parsed = Number.parseInt(requested, 10) || payload.page.number;
    const page = Math.max(1, Math.min(payload.page.totalPages, parsed));
    navigate({ ...effectiveState(), page, scrollTop: 0 });
  };

  const currentState = effectiveState();
  return (
    <>
      <div className="drop-upload-overlay" id="dropUploadOverlay" aria-hidden="true">
        <div className="drop-upload-panel">
          {icon('fas fa-cloud-arrow-up')}
          <strong>Drop files to upload</strong>
          <span>Directories are not supported yet.</span>
        </div>
      </div>

      <BrowserBreadcrumbs payload={payload} browse={browse} />
      <BrowserToolbar
        payload={payload}
        currentState={currentState}
        searchText={searchText}
        setSearchText={setSearchText}
        submitSearch={submitSearch}
        applyPreferences={applyPreferences}
        browse={browse}
        selectedEntries={selection.selectedEntries}
        actions={actions}
      />
      <TransferBufferPanel
        transferBuffer={actions.transferBuffer}
        mode={payload?.mode}
        path={currentState.path}
        actions={actions}
      />
      <BrowserListing
        payload={payload}
        loading={loading}
        error={error}
        selected={selection.selected}
        browse={browse}
        selectEntry={selection.selectEntry}
        toggleFavorite={actions.toggleFavorite}
        itemInteractionProps={selection.itemInteractionProps}
        effectiveState={effectiveState}
        navigate={navigate}
        jumpToPage={jumpToPage}
      />
    </>
  );
}

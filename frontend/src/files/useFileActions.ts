import { useCallback, useRef, useState } from 'react';
import { notify, postForm, toastError } from '../shared/api/form-api';
import { createFileEntryActions } from '../shared/browser/file-entry-actions';
import type { BrowserEntry, BrowserHistoryState, BrowserPayload, TransferBufferPayload } from './types';

interface FileActionOptions {
  selectedEntries: BrowserEntry[];
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  setPayload: React.Dispatch<React.SetStateAction<BrowserPayload | null>>;
  effectiveState: () => BrowserHistoryState;
  navigate: (state: BrowserHistoryState, replace?: boolean) => void;
  reload: () => void;
}

export function useFileActions({
  selectedEntries, setSelected, setPayload, effectiveState, navigate, reload,
}: FileActionOptions) {
  const [transferBuffer, setTransferBuffer] = useState<TransferBufferPayload | null>(null);
  const transferBufferRef = useRef(transferBuffer);
  transferBufferRef.current = transferBuffer;
  const entryActions = createFileEntryActions({
    selectedEntries, setSelected, setPayload, reload,
    currentPath: () => effectiveState().path,
    onTransferBuffer: setTransferBuffer,
  });

  const loadTransferBuffer = useCallback(async () => {
    try {
      const body = await window.EnderVault?.requestJson('/api/v1/files/transfer-buffer');
      if (body?.transferBuffer) setTransferBuffer(body.transferBuffer);
    } catch (reason) {
      toastError(reason, 'Transfer buffer could not be loaded.');
    }
  }, []);

  const createNamedItem = async (directory: boolean, name: string) => {
    const body = await postForm(directory ? '/api/v1/files/directories' : '/api/v1/files',
      { path: effectiveState().path, name });
    notify(body);
    reload();
  };

  const createItem = async (directory: boolean) => {
    const name = await window.EnderVault?.askTextInput({
      title: directory ? 'New directory' : 'New file',
      label: directory ? 'Directory name' : 'File name',
      placeholder: directory ? 'New directory' : 'note.txt',
      confirmLabel: 'Create',
    });
    if (!name) return;
    try {
      await createNamedItem(directory, name);
    } catch (reason) {
      toastError(reason, directory ? 'Directory creation failed.' : 'File creation failed.');
    }
  };

  const updateTransferBuffer = async (action: 'clear' | 'remove' | 'paste', values: Record<string, string>) => {
    try {
      const body = await postForm('/api/v1/files/transfer-buffer/' + action, values, action === 'paste');
      notify(body);
      if (body.transferBuffer) setTransferBuffer(body.transferBuffer);
      if (body.task) {
        window.EnderVaultServerTasks?.track(body.task, {
          announceStart: true,
          refreshUrl: '/files?path=' + encodeURIComponent(effectiveState().path),
        });
      }
    } catch (reason) {
      toastError(reason, 'Transfer buffer action failed.');
    }
  };

  const compressEntries = async (entries = selectedEntries) => {
    if (entries.length === 0) return;
    const outputName = await window.EnderVault?.askTextInput({
      title: 'Compress to ZIP', message: 'Create a ZIP archive in the current directory.',
      label: 'Archive name', placeholder: 'Archive.zip', confirmLabel: 'Create ZIP',
    });
    if (!outputName) return;
    try {
      const body = await postForm('/api/v1/files/archives', {
        path: effectiveState().path, paths: entries.map((entry) => entry.path), outputName,
      });
      notify(body);
      setSelected(new Set());
      if (body.task) {
        window.EnderVaultServerTasks?.track(body.task, {
          announceStart: true,
          refreshUrl: '/files?path=' + encodeURIComponent(effectiveState().path),
        });
      }
    } catch (reason) {
      toastError(reason, 'ZIP creation failed.');
    }
  };

  const resetPreferences = async () => {
    const body = await postForm('/api/v1/browser-preferences/reset', { target: 'files' });
    notify(body);
    navigate({ ...effectiveState(), page: 1, view: undefined, sort: undefined,
      direction: undefined, hidden: undefined, pageSize: undefined, scrollTop: 0 }, true);
  };

  return {
    ...entryActions,
    transferBuffer, transferBufferRef, loadTransferBuffer, createItem, createNamedItem, updateTransferBuffer,
    compressEntries, resetPreferences,
    paste: (operation: 'move' | 'copy') => updateTransferBuffer('paste', {
      path: effectiveState().path, operation, conflictPolicy: 'ask',
    }),
  };
}

export type FileBrowserActions = ReturnType<typeof useFileActions>;

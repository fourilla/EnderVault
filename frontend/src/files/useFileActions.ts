import { useCallback, useRef, useState } from 'react';
import { notify, postForm, toastError } from './file-actions-api';
import type {
  BrowserEntry,
  BrowserHistoryState,
  BrowserPayload,
  TransferBufferPayload,
} from './types';

interface FileActionOptions {
  selectedEntries: BrowserEntry[];
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  setPayload: React.Dispatch<React.SetStateAction<BrowserPayload | null>>;
  effectiveState: () => BrowserHistoryState;
  navigate: (state: BrowserHistoryState, replace?: boolean) => void;
  reload: () => void;
}

export function useFileActions({
  selectedEntries,
  setSelected,
  setPayload,
  effectiveState,
  navigate,
  reload,
}: FileActionOptions) {
  const [transferBuffer, setTransferBuffer] = useState<TransferBufferPayload | null>(null);
  const transferBufferRef = useRef(transferBuffer);
  transferBufferRef.current = transferBuffer;

  const loadTransferBuffer = useCallback(async () => {
    try {
      const body = await window.EnderVault?.requestJson('/api/v1/files/transfer-buffer');
      if (body?.transferBuffer) setTransferBuffer(body.transferBuffer);
    } catch (reason) {
      toastError(reason, 'Transfer buffer could not be loaded.');
    }
  }, []);

  const createItem = async (directory: boolean) => {
    const name = await window.EnderVault?.askTextInput({
      title: directory ? 'New directory' : 'New file',
      label: directory ? 'Directory name' : 'File name',
      placeholder: directory ? 'New directory' : 'note.txt',
      confirmLabel: 'Create',
    });
    if (!name) return;
    try {
      const body = await postForm(
        directory ? '/api/v1/files/directories' : '/api/v1/files',
        { path: effectiveState().path, name },
      );
      notify(body);
      reload();
    } catch (reason) {
      toastError(reason, directory ? 'Directory creation failed.' : 'File creation failed.');
    }
  };

  const toggleFavorite = async (entry: BrowserEntry) => {
    try {
      const body = await postForm('/api/v1/favorites/toggle', { path: entry.path });
      notify(body);
      const active = Boolean(body.active);
      setPayload((current) => {
        if (!current) return current;
        const update = (item: BrowserEntry) =>
          item.path === entry.path ? { ...item, favorite: active } : item;
        return {
          ...current,
          directories: current.directories.map(update),
          entries: current.entries.map(update),
        };
      });
    } catch (reason) {
      toastError(reason, 'Favorite could not be updated.');
    }
  };

  const addEntriesToBuffer = async (entries = selectedEntries) => {
    if (entries.length === 0) return;
    try {
      const body = await postForm('/api/v1/files/transfer-buffer', {
        path: entries[0].parentPath,
        items: entries.map((entry) => entry.name),
      });
      notify(body);
      if (body.transferBuffer) setTransferBuffer(body.transferBuffer);
      setSelected(new Set());
    } catch (reason) {
      toastError(reason, 'Items could not be added to the transfer buffer.');
    }
  };

  const updateTransferBuffer = async (
    action: 'clear' | 'remove' | 'paste',
    values: Record<string, string>,
  ) => {
    try {
      const body = await postForm(
        '/api/v1/files/transfer-buffer/' + action,
        values,
        action === 'paste',
      );
      notify(body);
      if (body.transferBuffer) setTransferBuffer(body.transferBuffer);
      if (body.task) {
        window.EnderVaultServerTasks?.track(body.task, {
          refreshUrl: '/files?path=' + encodeURIComponent(effectiveState().path),
        });
      }
    } catch (reason) {
      toastError(reason, 'Transfer buffer action failed.');
    }
  };

  const moveEntriesToTrash = async (entries = selectedEntries) => {
    if (entries.length === 0) return;
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Move to trash',
      message: 'Move the selected items to trash?',
      confirmLabel: 'Move to trash',
      danger: true,
    });
    if (!confirmed) return;
    try {
      const body = await postForm('/api/v1/files/trash', {
        path: entries[0].parentPath,
        items: entries.map((entry) => entry.name),
      });
      notify(body);
      setSelected(new Set());
      if (body.task) {
        window.EnderVaultServerTasks?.track(body.task, {
          refreshUrl: '/files?path=' + encodeURIComponent(effectiveState().path),
        });
      } else {
        reload();
      }
    } catch (reason) {
      toastError(reason, 'Items could not be moved to trash.');
    }
  };

  const downloadEntries = (entries = selectedEntries) => {
    if (entries.length === 0) return;
    if (entries.length === 1 && entries[0].type === 'file') {
      window.location.assign(entries[0].downloadUrl || entries[0].detailUrl);
      return;
    }
    const query = new URLSearchParams();
    if (entries[0].parentPath) query.set('path', entries[0].parentPath);
    entries.forEach((entry) => query.append('items', entry.name));
    window.location.assign('/files/download.zip?' + query.toString());
  };

  const compressEntries = async (entries = selectedEntries) => {
    if (entries.length === 0) return;
    const outputName = await window.EnderVault?.askTextInput({
      title: 'Compress to ZIP',
      message: 'Create a ZIP archive in the current directory.',
      label: 'Archive name',
      placeholder: 'Archive.zip',
      confirmLabel: 'Create ZIP',
    });
    if (!outputName) return;
    try {
      const body = await postForm('/api/v1/files/archives', {
        path: entries[0].parentPath,
        items: entries.map((entry) => entry.name),
        outputName,
      });
      notify(body);
      setSelected(new Set());
      if (body.task) {
        window.EnderVaultServerTasks?.track(body.task, {
          refreshUrl: '/files?path=' + encodeURIComponent(effectiveState().path),
        });
      }
    } catch (reason) {
      toastError(reason, 'ZIP creation failed.');
    }
  };

  const renameEntry = async (entry: BrowserEntry) => {
    const newName = await window.EnderVault?.askTextInput({
      title: 'Rename',
      label: 'New name',
      initialValue: entry.name,
      confirmLabel: 'Rename',
    });
    if (!newName || newName.trim() === entry.name) return;
    const body = await postForm('/api/v1/files/rename', {
      path: entry.parentPath,
      item: entry.name,
      newName: newName.trim(),
      conflictPolicy: 'ask',
    }, true);
    notify(body);
    reload();
  };

  const shareAndCopy = async (entry: BrowserEntry) => {
    const body = await postForm('/api/v1/shares', {
      path: entry.parentPath,
      item: entry.name,
    });
    const url = body.shareLink?.url || body.notification?.actionValue || '';
    if (url && await window.EnderVault?.copyText(url)) {
      window.EnderVault?.showToast('success', 'Share link created and copied.');
      return;
    }
    notify(body);
  };

  const resetPreferences = async () => {
    try {
      const body = await postForm('/api/v1/browser-preferences/reset', { target: 'files' });
      notify(body);
      navigate({
        ...effectiveState(),
        page: 1,
        view: undefined,
        sort: undefined,
        direction: undefined,
        hidden: undefined,
        pageSize: undefined,
        scrollTop: 0,
      }, true);
    } catch (reason) {
      toastError(reason, 'View preferences could not be reset.');
    }
  };

  return {
    transferBuffer,
    transferBufferRef,
    loadTransferBuffer,
    createItem,
    toggleFavorite,
    addEntriesToBuffer,
    updateTransferBuffer,
    moveEntriesToTrash,
    downloadEntries,
    compressEntries,
    renameEntry,
    shareAndCopy,
    resetPreferences,
    paste: (operation: 'move' | 'copy') => updateTransferBuffer('paste', {
      path: effectiveState().path,
      operation,
      conflictPolicy: 'ask',
    }),
  };
}

export type FileBrowserActions = ReturnType<typeof useFileActions>;

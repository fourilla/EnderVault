import { togglePathFavorite } from '../api/favorite-api';
import { notify, postForm, toastError } from '../api/form-api';
import type { BrowserEntry, TransferBufferPayload } from './types';

interface EntryListing {
  directories: BrowserEntry[];
  entries: BrowserEntry[];
}

export function createFileEntryActions<T extends EntryListing>({
  selectedEntries, setSelected, setPayload, reload, currentPath = () => '',
  zipDownloadUrl = '/files/download.zip', onTransferBuffer,
}: {
  selectedEntries: BrowserEntry[];
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  setPayload: React.Dispatch<React.SetStateAction<T | null>>;
  reload: () => void;
  currentPath?: () => string;
  zipDownloadUrl?: string;
  onTransferBuffer?: (buffer: TransferBufferPayload) => void;
}) {
  const toggleFavorite = async (entry: BrowserEntry) => {
    try {
      const body = await togglePathFavorite(entry.path);
      setPayload((current) => {
        if (!current) return current;
        const update = (item: BrowserEntry) => item.path === entry.path
          ? { ...item, favorite: Boolean(body.active) } : item;
        return { ...current, directories: current.directories.map(update), entries: current.entries.map(update) };
      });
    } catch (reason) { toastError(reason, 'Favorite could not be updated.'); }
  };
  const addEntriesToBuffer = async (entries = selectedEntries) => {
    if (!entries.length) return;
    try {
      const body = await postForm('/api/v1/files/transfer-buffer', { paths: entries.map((entry) => entry.path) });
      notify(body);
      if (body.transferBuffer) onTransferBuffer?.(body.transferBuffer);
      setSelected(new Set());
    } catch (reason) { toastError(reason, 'Items could not be added to the transfer buffer.'); }
  };
  const moveEntriesToTrash = async (entries = selectedEntries) => {
    if (!entries.length) return;
    const path = currentPath();
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Move to trash', message: 'Move the selected items to trash?',
      confirmLabel: 'Move to trash', danger: true,
    });
    if (!confirmed) return;
    try {
      const body = await postForm('/api/v1/files/trash', { path, paths: entries.map((entry) => entry.path) });
      notify(body);
      setSelected(new Set());
      if (body.task) {
        window.EnderVaultServerTasks?.track(body.task, {
          announceStart: true,
          refreshUrl: '/files?path=' + encodeURIComponent(path),
        });
      } else { reload(); }
    } catch (reason) { toastError(reason, 'Items could not be moved to trash.'); }
  };
  const downloadEntries = (entries = selectedEntries) => {
    if (!entries.length) return;
    if (entries.length === 1 && entries[0].type === 'file') {
      window.location.assign(entries[0].downloadUrl || entries[0].detailUrl);
      return;
    }
    const query = new URLSearchParams();
    if (currentPath()) query.set('path', currentPath());
    entries.forEach((entry) => query.append('paths', entry.path));
    window.location.assign(zipDownloadUrl + '?' + query.toString());
  };
  const renameEntry = async (entry: BrowserEntry) => {
    const newName = await window.EnderVault?.askTextInput({
      title: 'Rename', label: 'New name', initialValue: entry.name, confirmLabel: 'Rename',
    });
    if (!newName || newName.trim() === entry.name) return;
    const body = await postForm('/api/v1/files/rename', {
      path: entry.parentPath, item: entry.name, newName: newName.trim(), conflictPolicy: 'ask',
    }, true);
    notify(body);
    reload();
  };
  const shareAndCopy = async (entry: BrowserEntry) => {
    const body = await postForm('/api/v1/shares', { path: entry.parentPath, item: entry.name });
    const url = body.shareLink?.url || body.notification?.actionValue || '';
    if (url && await window.EnderVault?.copyText(url)) {
      window.EnderVault?.showToast('success', 'Share link created and copied.');
      return;
    }
    notify(body);
  };
  return { toggleFavorite, addEntriesToBuffer, moveEntriesToTrash, downloadEntries, renameEntry, shareAndCopy };
}

export type FileEntryActions = ReturnType<typeof createFileEntryActions>;

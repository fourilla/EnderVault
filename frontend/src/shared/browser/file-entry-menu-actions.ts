import type { BrowserMenuAction } from './browser-menu-context';
import type { BrowserEntry } from './types';
import type { FileEntryActions } from './file-entry-actions';

export function fileEntryMenuActions({
  actions, browse, openFile, createFileRequest,
}: {
  actions: FileEntryActions;
  browse: (path: string) => void;
  openFile: (url: string) => void;
  createFileRequest?: (path: string) => void;
}): BrowserMenuAction<BrowserEntry>[] {
  return [
    { id: 'open', group: 'primary',
      label: ({ item }) => item?.type === 'directory' ? 'Open' : 'Details',
      icon: ({ item }) => item?.type === 'directory' ? 'fas fa-folder-open' : 'fas fa-circle-info',
      visible: ({ mode }) => mode === 'single',
      run: ({ item }) => item!.type === 'directory' ? browse(item!.path) : openFile(item!.detailUrl) },
    { id: 'preview', group: 'primary', label: 'Preview', icon: 'fas fa-eye',
      visible: ({ mode, item }) => mode === 'single' && Boolean(item?.previewUrl),
      run: ({ item }) => window.open(item!.previewUrl!, '_blank', 'noopener,noreferrer') },
    { id: 'download', group: 'transfer', icon: 'fas fa-download',
      label: ({ mode, items }) => mode === 'selection' ? `Download ${items.length} selected` : 'Download',
      visible: ({ mode, item }) => mode === 'selection' || (mode === 'single' && Boolean(item?.downloadUrl)),
      run: ({ items }) => actions.downloadEntries(items) },
    { id: 'favorite', group: 'organize', icon: 'fas fa-star',
      label: ({ item }) => item?.favorite ? 'Remove from favorites' : 'Add to favorites',
      visible: ({ mode }) => mode === 'single', run: ({ item }) => actions.toggleFavorite(item!) },
    { id: 'add-to-buffer', group: 'organize', icon: 'fas fa-layer-group',
      label: ({ mode, items }) => mode === 'selection'
        ? `Add ${items.length} selected to transfer buffer` : 'Add to transfer buffer',
      visible: ({ mode }) => mode !== 'background', run: ({ items }) => actions.addEntriesToBuffer(items) },
    { id: 'share-copy', group: 'organize', label: 'Create share link and copy', icon: 'fas fa-link',
      visible: ({ mode }) => mode === 'single', run: ({ item }) => actions.shareAndCopy(item!) },
    { id: 'create-file-request-for-directory', group: 'organize', label: 'Create file request here',
      icon: 'fas fa-inbox',
      visible: ({ mode, item }) => Boolean(createFileRequest) && mode === 'single' && item?.type === 'directory',
      run: ({ item }) => createFileRequest?.(item!.path) },
    { id: 'rename', group: 'mutate', label: 'Rename', icon: 'fas fa-pen-to-square',
      visible: ({ mode }) => mode === 'single', run: ({ item }) => actions.renameEntry(item!) },
    { id: 'move-to-trash', group: 'danger', icon: 'fas fa-trash-can', danger: true,
      label: ({ mode, items }) => mode === 'selection' ? `Move ${items.length} selected to trash` : 'Move to trash',
      visible: ({ mode }) => mode !== 'background', run: ({ items }) => actions.moveEntriesToTrash(items) },
  ];
}

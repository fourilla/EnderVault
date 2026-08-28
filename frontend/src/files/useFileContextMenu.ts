import { useEffect, useRef } from 'react';
import type { BrowserEntry, BrowserHistoryState, BrowserPayload, TransferBufferPayload } from './types';
import type { FileBrowserActions } from './useFileActions';

type MenuItem = BrowserEntry & {
  element: HTMLElement;
  directory: boolean;
  file: boolean;
  extension: string;
};

type MenuContext = {
  mode: 'single' | 'selection' | 'background';
  item: MenuItem | null;
  items: MenuItem[];
  event: globalThis.MouseEvent;
};

export function useFileContextMenu({
  payloadRef,
  stateRef,
  selectedRef,
  transferBufferRef,
  setSelected,
  browse,
  actions,
}: {
  payloadRef: React.RefObject<BrowserPayload | null>;
  stateRef: React.RefObject<BrowserHistoryState>;
  selectedRef: React.RefObject<Set<string>>;
  transferBufferRef: React.RefObject<TransferBufferPayload | null>;
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  browse: (path: string) => void;
  actions: FileBrowserActions;
}) {
  const actionsRef = useRef(actions);
  actionsRef.current = actions;

  useEffect(() => {
    const menus = window.EnderVaultContextMenus;
    const workspace = document.querySelector('.workspace');
    if (!menus || !workspace || !menus.claimPageScope('files-react')) return;

    const menuActions: Array<Record<string, any>> = [];
    const extensionActions: Array<Record<string, any>> = [];
    const registerAction = (action: Record<string, any>) => {
      menuActions.push(action);
      return action;
    };
    const registerExtensionAction = (action: Record<string, any>) => {
      extensionActions.push(action);
      registerAction({
        ...action,
        visible: (context: MenuContext) => context.mode === 'single'
          && context.item?.file
          && (action.extensions || []).includes(context.item.extension)
          && (!action.visible || action.visible(context)),
      });
    };
    const handlers = () => actionsRef.current;
    const open = (entry: BrowserEntry) => {
      if (entry.type === 'directory') browse(entry.path);
      else window.location.assign(entry.detailUrl);
    };

    registerAction({ id: 'open', group: 'primary', label: (context: MenuContext) =>
      context.item?.type === 'directory' ? 'Open' : 'Details',
    icon: (context: MenuContext) => context.item?.type === 'directory'
      ? 'fas fa-folder-open' : 'fas fa-circle-info',
    visible: (context: MenuContext) => context.mode === 'single',
    run: (context: MenuContext) => open(context.item!) });
    registerAction({ id: 'preview', group: 'primary', label: 'Preview', icon: 'fas fa-eye',
      visible: (context: MenuContext) => context.mode === 'single' && Boolean(context.item?.previewUrl),
      run: (context: MenuContext) => window.open(context.item!.previewUrl!, '_blank', 'noopener,noreferrer') });
    registerAction({ id: 'download', group: 'transfer', label: (context: MenuContext) =>
      context.mode === 'selection' ? `Download ${context.items.length} selected` : 'Download',
    icon: 'fas fa-download', visible: (context: MenuContext) => context.mode === 'selection'
      || (context.mode === 'single' && Boolean(context.item?.downloadUrl)),
    run: (context: MenuContext) => handlers().downloadEntries(context.items) });
    registerAction({ id: 'compress-to-zip', group: 'transfer', label: (context: MenuContext) =>
      context.mode === 'selection' ? `Compress ${context.items.length} selected to ZIP` : 'Compress to ZIP',
    icon: 'fas fa-file-zipper', visible: (context: MenuContext) => context.mode !== 'background',
    run: (context: MenuContext) => handlers().compressEntries(context.items) });
    registerAction({ id: 'favorite', group: 'organize', label: (context: MenuContext) =>
      context.item?.favorite ? 'Remove from favorites' : 'Add to favorites',
    icon: 'fas fa-star', visible: (context: MenuContext) => context.mode === 'single',
    run: (context: MenuContext) => handlers().toggleFavorite(context.item!) });
    registerAction({ id: 'add-to-buffer', group: 'organize', label: (context: MenuContext) =>
      context.mode === 'selection'
        ? `Add ${context.items.length} selected to transfer buffer` : 'Add to transfer buffer',
    icon: 'fas fa-layer-group', visible: (context: MenuContext) => context.mode !== 'background',
    run: (context: MenuContext) => handlers().addEntriesToBuffer(context.items) });
    registerAction({ id: 'share-copy', group: 'organize', label: 'Create share link and copy',
      icon: 'fas fa-link', visible: (context: MenuContext) => context.mode === 'single',
      run: (context: MenuContext) => handlers().shareAndCopy(context.item!) });
    registerAction({ id: 'create-file-request-for-directory', group: 'organize',
      label: 'Create file request here', icon: 'fas fa-inbox',
      visible: (context: MenuContext) => document.getElementById('files-root')?.dataset.fileRequestsEnabled === 'true'
        && context.mode === 'single' && context.item?.directory,
      run: (context: MenuContext) => window.location.assign('/admin/file-requests?'
        + new URLSearchParams({ destinationPath: context.item!.path }).toString()) });
    registerAction({ id: 'rename', group: 'mutate', label: 'Rename', icon: 'fas fa-pen-to-square',
      visible: (context: MenuContext) => context.mode === 'single',
      run: (context: MenuContext) => handlers().renameEntry(context.item!) });
    registerAction({ id: 'move-to-trash', group: 'danger', label: (context: MenuContext) =>
      context.mode === 'selection' ? `Move ${context.items.length} selected to trash` : 'Move to trash',
    icon: 'fas fa-trash-can', danger: true, visible: (context: MenuContext) => context.mode !== 'background',
    run: (context: MenuContext) => handlers().moveEntriesToTrash(context.items) });
    registerAction({ id: 'upload', group: 'background', label: 'Upload files', icon: 'fas fa-upload',
      visible: (context: MenuContext) => context.mode === 'background'
        && stateRef.current.mode === 'browse',
      run: () => document.getElementById('uploadButton')?.click() });
    registerAction({ id: 'new-file', group: 'background', label: 'New file', icon: 'fas fa-file-circle-plus',
      visible: (context: MenuContext) => context.mode === 'background'
        && stateRef.current.mode === 'browse', run: () => handlers().createItem(false) });
    registerAction({ id: 'new-directory', group: 'background', label: 'New directory', icon: 'fas fa-folder-plus',
      visible: (context: MenuContext) => context.mode === 'background'
        && stateRef.current.mode === 'browse', run: () => handlers().createItem(true) });
    registerAction({ id: 'create-file-request-here', group: 'background', label: 'Create file request here',
      icon: 'fas fa-inbox', visible: (context: MenuContext) => context.mode === 'background'
        && stateRef.current.mode === 'browse'
        && document.getElementById('files-root')?.dataset.fileRequestsEnabled === 'true',
      run: () => window.location.assign('/admin/file-requests?'
        + new URLSearchParams({ destinationPath: stateRef.current.path }).toString()) });
    registerAction({ id: 'move-here', group: 'background-transfer', label: 'Move here',
      icon: 'fas fa-file-import', visible: (context: MenuContext) => context.mode === 'background'
        && stateRef.current.mode === 'browse' && Boolean(transferBufferRef.current?.active),
      run: () => handlers().paste('move') });
    registerAction({ id: 'copy-here', group: 'background-transfer', label: 'Copy here',
      icon: 'fas fa-copy', visible: (context: MenuContext) => context.mode === 'background'
        && stateRef.current.mode === 'browse' && Boolean(transferBufferRef.current?.active),
      run: () => handlers().paste('copy') });

    const entries = () => {
      const current = payloadRef.current;
      return current ? [...current.directories, ...current.entries] : [];
    };
    const menuItem = (element: HTMLElement): MenuItem | null => {
      const entry = entries().find((candidate) => candidate.path === element.dataset.entryPath);
      if (!entry) return null;
      const dot = entry.name.lastIndexOf('.');
      return {
        ...entry,
        element,
        directory: entry.type === 'directory',
        file: entry.type === 'file',
        extension: dot > 0 ? entry.name.slice(dot + 1).toLowerCase() : '',
      };
    };
    const contextForEvent = (event: globalThis.MouseEvent): MenuContext | null => {
      const target = event.target as HTMLElement;
      const targetElement = target.closest<HTMLElement>('[data-context-item="true"]');
      if (targetElement) {
        const item = menuItem(targetElement);
        if (!item) return null;
        const selectedPaths = selectedRef.current;
        const targetSelected = selectedPaths.has(item.path);
        const selectedItems = entries()
          .filter((entry) => selectedPaths.has(entry.path))
          .map((entry) => menuItem(document.querySelector<HTMLElement>(
            `[data-context-item="true"][data-entry-path="${CSS.escape(entry.path)}"]`,
          )!))
          .filter((entry): entry is MenuItem => Boolean(entry));
        if (!targetSelected && selectedPaths.size > 0) setSelected(new Set());
        const useSelection = targetSelected && selectedItems.length > 1;
        return { mode: useSelection ? 'selection' : 'single', item,
          items: useSelection ? selectedItems : [item], event };
      }
      if (!workspace.contains(target)
          || target.closest('a, button, input, textarea, select, label, summary, dialog, .context-menu')) {
        return null;
      }
      return { mode: 'background', item: null, items: [], event };
    };
    const menu = menus.createActionMenu({
      menuId: 'fileContextMenu', actions: menuActions, contextForEvent,
      errorMessage: 'The file action failed.',
      extraCloseEvents: ['endervault:listing-refreshed'],
    });
    window.EnderVaultContextMenu = {
      registerAction,
      registerExtensionAction,
      extensionActions: () => [...extensionActions],
      close: () => menu?.close(),
    };
    return () => {
      menu?.close();
      delete window.EnderVaultContextMenu;
    };
  }, [browse, payloadRef, selectedRef, setSelected, stateRef, transferBufferRef]);
}

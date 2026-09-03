import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useBrowserContextMenu } from '../shared/browser/useBrowserContextMenu';
import { fileEntryMenuActions } from '../shared/browser/file-entry-menu-actions';
import type { BrowserMenuAction } from '../shared/browser/browser-menu-context';
import type { BrowserEntry, BrowserHistoryState, BrowserPayload, TransferBufferPayload } from './types';
import type { FileBrowserActions } from './useFileActions';

export function useFileContextMenu({
  payloadRef, stateRef, selectedRef, transferBufferRef, setSelected, browse, openFile, actions, fileRequestsEnabled,
}: {
  payloadRef: React.RefObject<BrowserPayload | null>;
  stateRef: React.RefObject<BrowserHistoryState>;
  selectedRef: React.RefObject<Set<string>>;
  transferBufferRef: React.RefObject<TransferBufferPayload | null>;
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  browse: (path: string) => void;
  openFile: (detailUrl: string) => void;
  actions: FileBrowserActions;
  fileRequestsEnabled: boolean;
}) {
  const navigate = useNavigate();
  const extraActions = useRef<BrowserMenuAction<BrowserEntry>[]>([]);
  const menuActions = fileEntryMenuActions({ actions, browse, openFile,
    createFileRequest: fileRequestsEnabled ? (path) => navigate('/admin/file-requests?'
      + new URLSearchParams({ destinationPath: path }).toString()) : undefined });
  menuActions.splice(3, 0, {
    id: 'compress-to-zip', group: 'transfer', icon: 'fas fa-file-zipper',
    label: ({ mode, items }) => mode === 'selection' ? `Compress ${items.length} selected to ZIP` : 'Compress to ZIP',
    visible: ({ mode }) => mode !== 'background', run: ({ items }) => actions.compressEntries(items),
  });
  const backgroundActions: BrowserMenuAction<BrowserEntry>[] = [
    { id: 'upload', group: 'background', label: 'Upload files', icon: 'fas fa-upload',
      run: () => document.getElementById('uploadButton')?.click() },
    { id: 'new-file', group: 'background', label: 'New file', icon: 'fas fa-file-circle-plus',
      run: () => actions.createItem(false) },
    { id: 'new-directory', group: 'background', label: 'New directory', icon: 'fas fa-folder-plus',
      run: () => actions.createItem(true) },
    { id: 'create-file-request-here', group: 'background', label: 'Create file request here', icon: 'fas fa-inbox',
      visible: () => fileRequestsEnabled,
      run: () => navigate('/admin/file-requests?'
        + new URLSearchParams({ destinationPath: stateRef.current.path }).toString()) },
    { id: 'move-here', group: 'background-transfer', label: 'Move here', icon: 'fas fa-file-import',
      visible: () => Boolean(transferBufferRef.current?.active), run: () => actions.paste('move') },
    { id: 'copy-here', group: 'background-transfer', label: 'Copy here', icon: 'fas fa-copy',
      visible: () => Boolean(transferBufferRef.current?.active), run: () => actions.paste('copy') },
  ];
  menuActions.push(...backgroundActions.map((action): BrowserMenuAction<BrowserEntry> => ({ ...action,
    visible: (context) => context.mode === 'background' && stateRef.current.mode === 'browse'
      && (!action.visible || action.visible(context)),
  })));

  const menu = useBrowserContextMenu({
    menuId: 'fileContextMenu', pageScope: 'files-react',
    entries: () => payloadRef.current ? [...payloadRef.current.directories, ...payloadRef.current.entries] : [],
    itemKey: (entry) => entry.path, keyAttribute: 'data-entry-path', selectedRef, setSelected,
    actions: () => [...menuActions, ...extraActions.current],
    contentKey: payloadRef.current, errorMessage: 'The file action failed.',
  });
  useEffect(() => {
    const extensions: Array<BrowserMenuAction<BrowserEntry> & { extensions: string[] }> = [];
    const bridge: NonNullable<Window['EnderVaultContextMenu']> = {
      registerAction: (action) => {
        extraActions.current.push(action);
        return action;
      },
      registerExtensionAction: (action) => {
        extensions.push(action);
        bridge.registerAction({ ...action, visible: (context) => context.mode === 'single'
          && context.item?.type === 'file'
          && context.item.name.lastIndexOf('.') > 0
          && action.extensions.includes(context.item.name.slice(context.item.name.lastIndexOf('.') + 1).toLowerCase())
          && (!action.visible || action.visible(context)) });
      },
      extensionActions: () => [...extensions],
      close: () => menu.current?.close(),
    };
    window.EnderVaultContextMenu = bridge;
    return () => {
      extraActions.current = [];
      if (window.EnderVaultContextMenu === bridge) delete window.EnderVaultContextMenu;
    };
  }, [menu]);
}

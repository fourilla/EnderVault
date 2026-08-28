import {
  FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { canonicalState, loadBrowserPayload } from './browser-api';
import {
  defaultBrowserState,
  initialBrowserState,
  parseBrowserState,
  rememberBrowserState,
} from './browser-history';
import { EntryGrid, EntryTable, icon } from './BrowserEntries';
import { notify, postForm, toastError } from './file-actions-api';
import type {
  BrowserEntry,
  BrowserHistoryState,
  BrowserPayload,
  TransferBufferPayload,
} from './types';
import './files-app.css';

export function BrowserApp() {
  const [state, setState] = useState<BrowserHistoryState>(() => initialBrowserState());
  const [payload, setPayload] = useState<BrowserPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [searchText, setSearchText] = useState(state.query);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [refreshToken, setRefreshToken] = useState(0);
  const [transferBuffer, setTransferBuffer] = useState<TransferBufferPayload | null>(null);
  const stateRef = useRef(state);
  const payloadRef = useRef(payload);
  const selectedRef = useRef(selected);
  const transferBufferRef = useRef(transferBuffer);
  const contextActionHandlersRef = useRef<Record<string, (...args: any[]) => any>>({});
  const restoreScrollRef = useRef(state.scrollTop);
  const longPressRef = useRef<{
    timer: number | null;
    pointerId: number | null;
    startX: number;
    startY: number;
    entry: BrowserEntry | null;
    suppressClick: boolean;
  }>({ timer: null, pointerId: null, startX: 0, startY: 0, entry: null, suppressClick: false });

  stateRef.current = state;
  payloadRef.current = payload;
  selectedRef.current = selected;
  transferBufferRef.current = transferBuffer;

  const effectiveState = useCallback(() => {
    const current = stateRef.current;
    const currentPayload = payloadRef.current;
    return currentPayload ? canonicalState(current, currentPayload) : current;
  }, []);

  const persistCurrentScroll = useCallback(() => {
    const current = { ...effectiveState(), scrollTop: Math.max(0, Math.round(window.scrollY)) };
    rememberBrowserState(current, true);
    stateRef.current = current;
    return current;
  }, [effectiveState]);

  const navigate = useCallback((next: BrowserHistoryState, replace = false) => {
    persistCurrentScroll();
    const normalized = { ...next, version: 1 as const, scrollTop: next.scrollTop || 0 };
    restoreScrollRef.current = normalized.scrollTop;
    rememberBrowserState(normalized, replace);
    setState(normalized);
  }, [persistCurrentScroll]);

  const browse = useCallback((path: string) => {
    const current = effectiveState();
    setSearchText('');
    navigate({
      ...current,
      mode: 'browse',
      path,
      query: '',
      page: 1,
      scrollTop: 0,
    });
  }, [effectiveState, navigate]);

  useEffect(() => {
    setSelected(new Set());
  }, [state.mode, state.path, state.query, state.page]);

  useEffect(() => {
    const refreshListing = async (url?: string) => {
      if (url) {
        const target = new URL(url, window.location.href);
        const targetPath = target.searchParams.get('path');
        if (targetPath != null && targetPath !== effectiveState().path) {
          navigate({
            ...effectiveState(),
            mode: 'browse',
            path: targetPath,
            query: '',
            page: 1,
            scrollTop: 0,
          });
          return;
        }
      }
      setRefreshToken((current) => current + 1);
    };
    window.EnderVaultFileBrowser = {
      refreshListing,
      requestListingRefresh: (url?: string) => void refreshListing(url),
      syncToolbarState: () => undefined,
    };
    document.dispatchEvent(new CustomEvent('endervault:files-ready'));
    return () => {
      delete window.EnderVaultFileBrowser;
    };
  }, [effectiveState, navigate]);

  const loadTransferBuffer = useCallback(async () => {
    try {
      const body = await window.EnderVault?.requestJson('/api/v1/files/transfer-buffer');
      if (body?.transferBuffer) setTransferBuffer(body.transferBuffer);
    } catch (reason) {
      toastError(reason, 'Transfer buffer could not be loaded.');
    }
  }, []);

  useEffect(() => {
    void loadTransferBuffer();
  }, [loadTransferBuffer]);

  useEffect(() => {
    rememberBrowserState(state, true);
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadBrowserPayload(state, controller.signal)
      .then((nextPayload) => {
        setPayload(nextPayload);
        const resolvedState = canonicalState(state, nextPayload);
        rememberBrowserState(resolvedState, true);
        stateRef.current = resolvedState;
        const stickyContext = {
          targetType: 'STORAGE',
          targetKey: nextPayload.path,
          surface: nextPayload.mode === 'search' ? 'SEARCH' : 'BROWSER',
          label: nextPayload.path || 'Files /',
        };
        void window.EnderVaultStickyNotes?.setContext(stickyContext);
        document.dispatchEvent(new CustomEvent('endervault:sticky-context-changed', {
          detail: stickyContext,
        }));
        const readOnlyLink = document.querySelector<HTMLAnchorElement>('[data-read-only-link]');
        if (readOnlyLink) {
          const query = nextPayload.path
            ? '?' + new URLSearchParams({ path: nextPayload.path }).toString()
            : '';
          readOnlyLink.href = '/files/read-only' + query;
        }
        window.requestAnimationFrame(() => {
          window.scrollTo({ top: restoreScrollRef.current, behavior: 'auto' });
        });
      })
      .catch((reason: unknown) => {
        if (controller.signal.aborted) return;
        setError(reason instanceof Error ? reason.message : 'The file list could not be loaded.');
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [state, refreshToken]);

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      const restored = parseBrowserState(event.state) || defaultBrowserState();
      restoreScrollRef.current = restored.scrollTop;
      setSearchText(restored.query);
      setState(restored);
    };
    const onPageHide = () => persistCurrentScroll();
    window.addEventListener('popstate', onPopState);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      window.removeEventListener('popstate', onPopState);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [persistCurrentScroll]);

  const applyPreferences = (updates: Partial<BrowserHistoryState>) => {
    navigate({ ...effectiveState(), ...updates, page: 1, scrollTop: 0 });
  };

  const submitSearch = (event: FormEvent) => {
    event.preventDefault();
    const query = searchText.trim();
    if (!query) {
      browse(effectiveState().path);
      return;
    }
    navigate({
      ...effectiveState(),
      mode: 'search',
      query,
      page: 1,
      scrollTop: 0,
    });
  };

  const selectableEntries = useMemo(() => {
    if (!payload || payload.mode === 'search') return [];
    return [...payload.directories, ...payload.entries];
  }, [payload]);

  const selectedEntries = useMemo(
    () => selectableEntries.filter((entry) => selected.has(entry.path)),
    [selectableEntries, selected],
  );

  const selectEntry = (entry: BrowserEntry, checked: boolean) => {
    setSelected((current) => {
      const next = new Set(current);
      if (checked) next.add(entry.path);
      else next.delete(entry.path);
      return next;
    });
  };

  const toggleEntrySelection = (entry: BrowserEntry) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(entry.path)) next.delete(entry.path);
      else next.add(entry.path);
      return next;
    });
  };

  const openEntry = (entry: BrowserEntry) => {
    if (entry.type === 'directory') browse(entry.path);
    else window.location.assign(entry.detailUrl);
  };

  const cancelLongPress = () => {
    if (longPressRef.current.timer != null) window.clearTimeout(longPressRef.current.timer);
    longPressRef.current.timer = null;
    longPressRef.current.pointerId = null;
    longPressRef.current.entry = null;
  };

  const itemInteractionProps = (entry: BrowserEntry) => ({
    onPointerDown: (event: React.PointerEvent<HTMLElement>) => {
      if (payloadRef.current?.mode !== 'browse'
          || event.button !== 0
          || (event.target as HTMLElement).closest('input, button, label, .table-actions, .action-icon')) {
        return;
      }
      cancelLongPress();
      longPressRef.current.pointerId = event.pointerId;
      longPressRef.current.startX = event.clientX;
      longPressRef.current.startY = event.clientY;
      longPressRef.current.entry = entry;
      longPressRef.current.timer = window.setTimeout(() => {
        longPressRef.current.suppressClick = true;
        toggleEntrySelection(entry);
        cancelLongPress();
      }, 520);
    },
    onPointerMove: (event: React.PointerEvent<HTMLElement>) => {
      const pending = longPressRef.current;
      if (pending.timer == null || pending.pointerId !== event.pointerId) return;
      if (Math.abs(event.clientX - pending.startX) > 10
          || Math.abs(event.clientY - pending.startY) > 10) {
        cancelLongPress();
      }
    },
    onPointerUp: cancelLongPress,
    onPointerCancel: cancelLongPress,
    onContextMenu: (event: React.MouseEvent<HTMLElement>) => {
      if (longPressRef.current.suppressClick) {
        event.preventDefault();
        event.stopPropagation();
      }
    },
    onClickCapture: (event: React.MouseEvent<HTMLElement>) => {
      if (longPressRef.current.suppressClick) {
        event.preventDefault();
        event.stopPropagation();
        longPressRef.current.suppressClick = false;
        return;
      }
      const target = event.target as HTMLElement;
      if (target.closest('input, button, label, select, textarea, summary, .table-actions, .action-icon')) {
        return;
      }
      const selectionClick = payloadRef.current?.mode === 'browse'
        && (selectedRef.current.size > 0 || event.ctrlKey || event.metaKey);
      if (selectionClick) {
        event.preventDefault();
        event.stopPropagation();
        toggleEntrySelection(entry);
        return;
      }
      if (target.closest('a[href]')) return;
      openEntry(entry);
    },
  });

  useEffect(() => {
    const clearSelectionFromBackground = (event: globalThis.MouseEvent) => {
      if (selectedRef.current.size === 0) return;
      const target = event.target as HTMLElement;
      if (target.closest('[data-context-item="true"], .toolbar, .transfer-buffer-panel, .upload-activity, .toast-region, .context-menu')) {
        return;
      }
      setSelected(new Set());
    };
    document.addEventListener('click', clearSelectionFromBackground);
    return () => document.removeEventListener('click', clearSelectionFromBackground);
  }, []);

  const reload = () => setRefreshToken((current) => current + 1);

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

  contextActionHandlersRef.current = {
    browse,
    toggleFavorite,
    addEntriesToBuffer,
    moveEntriesToTrash,
    downloadEntries,
    compressEntries,
    renameEntry,
    shareAndCopy,
    createItem,
    paste: (operation: 'move' | 'copy') => updateTransferBuffer('paste', {
      path: effectiveState().path,
      operation,
      conflictPolicy: 'ask',
    }),
  };

  useEffect(() => {
    const menus = window.EnderVaultContextMenus;
    const workspace = document.querySelector('.workspace');
    if (!menus || !workspace || !menus.claimPageScope('files-react')) return;

    type MenuItem = BrowserEntry & { element: HTMLElement; directory: boolean; file: boolean; extension: string };
    type MenuContext = {
      mode: 'single' | 'selection' | 'background';
      item: MenuItem | null;
      items: MenuItem[];
      event: globalThis.MouseEvent;
    };
    const actions: Array<Record<string, any>> = [];
    const extensionActions: Array<Record<string, any>> = [];
    const registerAction = (action: Record<string, any>) => {
      actions.push(action);
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
    const handlers = () => contextActionHandlersRef.current;
    const open = (entry: BrowserEntry) => {
      if (entry.type === 'directory') handlers().browse(entry.path);
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
    run: (context: MenuContext) => handlers().toggleFavorite(context.item) });
    registerAction({ id: 'add-to-buffer', group: 'organize', label: (context: MenuContext) =>
      context.mode === 'selection'
        ? `Add ${context.items.length} selected to transfer buffer` : 'Add to transfer buffer',
    icon: 'fas fa-layer-group', visible: (context: MenuContext) => context.mode !== 'background',
    run: (context: MenuContext) => handlers().addEntriesToBuffer(context.items) });
    registerAction({ id: 'share-copy', group: 'organize', label: 'Create share link and copy',
      icon: 'fas fa-link', visible: (context: MenuContext) => context.mode === 'single',
      run: (context: MenuContext) => handlers().shareAndCopy(context.item) });
    registerAction({ id: 'create-file-request-for-directory', group: 'organize',
      label: 'Create file request here', icon: 'fas fa-inbox',
      visible: (context: MenuContext) => document.getElementById('files-root')?.dataset.fileRequestsEnabled === 'true'
        && context.mode === 'single' && context.item?.directory,
      run: (context: MenuContext) => window.location.assign('/admin/file-requests?'
        + new URLSearchParams({ destinationPath: context.item!.path }).toString()) });
    registerAction({ id: 'rename', group: 'mutate', label: 'Rename', icon: 'fas fa-pen-to-square',
      visible: (context: MenuContext) => context.mode === 'single',
      run: (context: MenuContext) => handlers().renameEntry(context.item) });
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
      menuId: 'fileContextMenu', actions, contextForEvent,
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
  }, []);

  const currentPreferences = payload?.preferences;
  const currentState = effectiveState();

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

  return (
    <>
      <div className="drop-upload-overlay" id="dropUploadOverlay" aria-hidden="true">
        <div className="drop-upload-panel">
          {icon('fas fa-cloud-arrow-up')}
          <strong>Drop files to upload</strong>
          <span>Directories are not supported yet.</span>
        </div>
      </div>

      <section className="breadcrumb-bar files-breadcrumb" aria-label="Current location">
        <span className="breadcrumb-label">
          {payload?.mode === 'search' ? 'Search in' : 'Location'}
        </span>
        <div className="breadcrumb-list">
          {(payload?.breadcrumbs || [{ label: 'Root', path: '' }]).map((breadcrumb) => (
            <button
              className="breadcrumb-link"
              type="button"
              key={breadcrumb.path + ':' + breadcrumb.label}
              onClick={() => browse(breadcrumb.path)}
            >
              {breadcrumb.label}
            </button>
          ))}
        </div>
      </section>

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
            hidden={payload?.mode === 'search'}
          >
              <form
                className="icon-form"
                id="uploadForm"
                data-max-concurrent-uploads={
                  document.getElementById('files-root')?.dataset.maxConcurrentUploads || '1'
                }
                data-admission-url={
                  '/api/v1/files/upload-sessions?path=' + encodeURIComponent(effectiveState().path)
                }
              >
                <input type="hidden" name="path" value={effectiveState().path} readOnly />
                <input
                  className="visually-hidden"
                  id="fileUploadInput"
                  type="file"
                  name="files"
                  multiple
                />
                <button
                  className="icon-button"
                  id="uploadButton"
                  type="button"
                  title="Upload files"
                  aria-label="Upload files"
                >
                  {icon('fas fa-upload')}
                </button>
              </form>

              <details className="settings-menu file-new-menu">
                <summary className="icon-button menu-summary" title="Create new item" aria-label="Create new item">
                  {icon('fas fa-plus')}
                </summary>
                <div className="settings-panel file-new-panel">
                  <div className="file-new-actions">
                    <button
                      className="ghost icon-text-button"
                      type="button"
                      onClick={() => void createItem(false)}
                    >
                      {icon('fas fa-file-circle-plus')}
                      <span>New file</span>
                    </button>
                    <button
                      className="ghost icon-text-button"
                      type="button"
                      onClick={() => void createItem(true)}
                    >
                      {icon('fas fa-folder-plus')}
                      <span>New directory</span>
                    </button>
                  </div>
                </div>
              </details>

              <button
                className="icon-button"
                type="button"
                disabled={selectedEntries.length === 0}
                title="Download selected"
                aria-label="Download selected"
                onClick={() => downloadEntries()}
              >
                {icon('fas fa-download')}
              </button>
              <button
                className="icon-button"
                type="button"
                disabled={selectedEntries.length === 0}
                title="Compress selected to ZIP"
                aria-label="Compress selected to ZIP"
                onClick={() => void compressEntries()}
              >
                {icon('fas fa-file-zipper')}
              </button>
              <button
                className="icon-button"
                type="button"
                disabled={selectedEntries.length === 0}
                title="Add selected to transfer buffer"
                aria-label="Add selected to transfer buffer"
                onClick={() => void addEntriesToBuffer()}
              >
                {icon('fas fa-layer-group')}
              </button>
              <button
                className="icon-button danger"
                type="button"
                disabled={selectedEntries.length === 0}
                title="Delete selected"
                aria-label="Delete selected"
                onClick={() => void moveEntriesToTrash()}
              >
                {icon('fas fa-trash-can')}
              </button>
          </div>
          {payload?.mode === 'search' && (
            <button
              className="ghost icon-text-button files-exit-search"
              type="button"
              onClick={() => browse(payload.path)}
            >
              {icon('fas fa-xmark')}
              <span>Exit search</span>
            </button>
          )}
          <div className="toolbar-actions browser-controls">
            <button
              className="ghost icon-button"
              type="button"
              title={currentPreferences?.view === 'grid'
                ? 'Switch to table view'
                : 'Switch to grid view'}
              aria-label={currentPreferences?.view === 'grid'
                ? 'Switch to table view'
                : 'Switch to grid view'}
              onClick={() => applyPreferences({
                view: currentPreferences?.view === 'grid' ? 'table' : 'grid',
              })}
            >
              {icon(currentPreferences?.view === 'grid' ? 'fas fa-bars' : 'fas fa-border-all')}
            </button>
            <details className="settings-menu">
              <summary className="icon-button menu-summary" title="View options" aria-label="View options">
                {icon('fas fa-ellipsis-vertical')}
              </summary>
              <div className="settings-panel">
                <div className="settings-form sort-form">
                  <label>
                    Sort
                    <select
                      value={currentPreferences?.sort || currentState.sort || 'name'}
                      onChange={(event) => applyPreferences({
                        sort: event.target.value as BrowserHistoryState['sort'],
                      })}
                    >
                      <option value="name">Name</option>
                      <option value="size">Size</option>
                      <option value="modified">Modified</option>
                      <option value="type">Type</option>
                    </select>
                  </label>
                  <label>
                    Direction
                    <select
                      value={currentPreferences?.direction || currentState.direction || 'asc'}
                      onChange={(event) => applyPreferences({
                        direction: event.target.value as BrowserHistoryState['direction'],
                      })}
                    >
                      <option value="asc">Ascending</option>
                      <option value="desc">Descending</option>
                    </select>
                  </label>
                  <label>
                    Files/page
                    <select
                      value={currentPreferences?.pageSize || currentState.pageSize || 200}
                      onChange={(event) => applyPreferences({ pageSize: Number(event.target.value) })}
                    >
                      {(currentPreferences?.pageSizeOptions || [50, 100, 200, 500]).map((size) => (
                        <option value={size} key={size}>{size}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Visibility
                    <select
                      value={currentPreferences?.hidden || currentState.hidden || 'hide'}
                      onChange={(event) => applyPreferences({
                        hidden: event.target.value as BrowserHistoryState['hidden'],
                      })}
                    >
                      <option value="hide">Visible only</option>
                      <option value="show">Show hidden</option>
                    </select>
                  </label>
                  <button
                    className="ghost icon-text-button files-reset-preferences"
                    type="button"
                    onClick={() => void resetPreferences()}
                  >
                    {icon('fas fa-arrow-rotate-left')}
                    <span>Reset view options</span>
                  </button>
                </div>
              </div>
            </details>
          </div>
        </div>
      </section>

      {transferBuffer?.active && (
        <section className="transfer-buffer-panel files-react-transfer-buffer" aria-label="Transfer buffer">
          <div className="transfer-buffer-header">
            <div className="transfer-buffer-summary">
              {icon('fas fa-layer-group')}
              <div>
                <strong>Transfer buffer</strong>
                <p>{transferBuffer.count} item(s) ready. Open a directory and choose what to do here.</p>
              </div>
            </div>
            <button
              className="ghost transfer-buffer-clear"
              type="button"
              onClick={() => void updateTransferBuffer('clear', {})}
            >
              Clear buffer
            </button>
          </div>
          <ul className="transfer-buffer-list">
            {transferBuffer.items.map((item) => (
              <li key={item.path}>
                {icon(item.iconClass)}
                <span title={item.path}>{item.name}</span>
                <button
                  className="transfer-buffer-remove"
                  type="button"
                  title={'Remove ' + item.name + ' from transfer buffer'}
                  aria-label={'Remove ' + item.name + ' from transfer buffer'}
                  onClick={() => void updateTransferBuffer('remove', { itemPath: item.path })}
                >
                  {icon('fas fa-xmark')}
                </button>
              </li>
            ))}
          </ul>
          {payload?.mode === 'browse' && (
            <div className="transfer-buffer-actions">
              <button
                className="ghost"
                type="button"
                onClick={() => void updateTransferBuffer('paste', {
                  path: effectiveState().path,
                  operation: 'move',
                  conflictPolicy: 'ask',
                })}
              >
                {icon('fas fa-file-import')}
                <span>Move here</span>
              </button>
              <button
                className="ghost"
                type="button"
                onClick={() => void updateTransferBuffer('paste', {
                  path: effectiveState().path,
                  operation: 'copy',
                  conflictPolicy: 'ask',
                })}
              >
                {icon('fas fa-copy')}
                <span>Copy here</span>
              </button>
            </div>
          )}
        </section>
      )}

      <section className="section-heading files-react-heading">
        <div>
          <h1>{payload?.mode === 'search' ? 'Search' : (payload?.path || 'Files')}</h1>
          {payload?.mode === 'search' ? (
            <p>{payload.page.totalItems} results for <strong>{payload.search.query}</strong></p>
          ) : (
            <p>
              {payload
                ? payload.directories.length + ' directories, ' + payload.page.totalItems + ' files'
                : 'Loading files...'}
            </p>
          )}
        </div>
      </section>

      {error && <section className="dashboard-panel files-load-error" role="alert">{error}</section>}
      {loading && !payload && <p className="empty browser-grid-empty">Loading files...</p>}

      {payload && (
        <>
          {payload.mode === 'search' && (
            payload.entries.length > 0 ? (
              <section className="browser-section" aria-label="Search results">
                <EntryTable
                  entries={payload.entries}
                  search
                  onBrowse={browse}
                  selected={selected}
                  onSelect={selectEntry}
                  onFavorite={toggleFavorite}
                  itemInteractionProps={itemInteractionProps}
                />
              </section>
            ) : (
              <p className="empty browser-grid-empty">No matching items.</p>
            )
          )}

          {payload.mode === 'browse' && payload.directories.length > 0 && (
            <section className="browser-section" aria-label="Directories">
              <header className="section-heading">
                <h2>Directories ({payload.directories.length})</h2>
              </header>
              <EntryTable
                entries={payload.directories}
                search={false}
                onBrowse={browse}
                selected={selected}
                onSelect={selectEntry}
                onFavorite={toggleFavorite}
                itemInteractionProps={itemInteractionProps}
              />
            </section>
          )}

          {payload.mode === 'browse' && payload.entries.length > 0 && (
            <section className="browser-section" aria-label="Files">
              <header className="section-heading">
                <h2>Files ({payload.page.totalItems})</h2>
                <p>Showing {payload.page.startItem}-{payload.page.endItem}</p>
              </header>
              {payload.preferences.view === 'grid' ? (
                <EntryGrid
                  entries={payload.entries}
                  onBrowse={browse}
                  selected={selected}
                  onSelect={selectEntry}
                  itemInteractionProps={itemInteractionProps}
                />
              ) : (
                <EntryTable
                  entries={payload.entries}
                  search={false}
                  onBrowse={browse}
                  selected={selected}
                  onSelect={selectEntry}
                  onFavorite={toggleFavorite}
                  itemInteractionProps={itemInteractionProps}
                />
              )}
            </section>
          )}

          {payload.mode === 'browse'
            && payload.directories.length === 0
            && payload.entries.length === 0 && (
            <p className="empty browser-grid-empty">
              This directory is empty.
            </p>
          )}

          {payload.page.totalPages > 1 && (
            <nav className="pagination" aria-label="File pages">
              <button
                className="ghost pagination-link"
                type="button"
                disabled={!payload.page.hasPrevious}
                onClick={() => navigate({ ...effectiveState(), page: 1, scrollTop: 0 })}
              >
                First
              </button>
              <button
                className="ghost pagination-link"
                type="button"
                disabled={!payload.page.hasPrevious}
                onClick={() => navigate({
                  ...effectiveState(),
                  page: payload.page.number - 1,
                  scrollTop: 0,
                })}
              >
                Previous
              </button>
              <button
                className="page-status files-page-status"
                type="button"
                onClick={() => void jumpToPage()}
              >
                Page {payload.page.number} of {payload.page.totalPages}
              </button>
              <button
                className="ghost pagination-link"
                type="button"
                disabled={!payload.page.hasNext}
                onClick={() => navigate({
                  ...effectiveState(),
                  page: payload.page.number + 1,
                  scrollTop: 0,
                })}
              >
                Next
              </button>
              <button
                className="ghost pagination-link"
                type="button"
                disabled={!payload.page.hasNext}
                onClick={() => navigate({
                  ...effectiveState(),
                  page: payload.page.totalPages,
                  scrollTop: 0,
                })}
              >
                Last
              </button>
            </nav>
          )}
        </>
      )}
    </>
  );
}

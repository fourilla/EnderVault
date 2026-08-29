import { useEffect, useRef } from 'react';
import type { BookmarkActions } from './useBookmarkActions';
import type { BookmarkEntry, BookmarkPayload } from './types';

type MenuItem = BookmarkEntry & { element: HTMLElement };
type MenuContext = {
  mode: 'single' | 'selection' | 'background';
  item: MenuItem | null;
  items: MenuItem[];
  event: globalThis.MouseEvent;
};

export function useBookmarkContextMenu({
  payloadRef,
  selectedRef,
  setSelected,
  browse,
  actions,
  openLinkDialog,
  openBulkDialog,
}: {
  payloadRef: React.RefObject<BookmarkPayload | null>;
  selectedRef: React.RefObject<Set<string>>;
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  browse: (id: string) => void;
  actions: BookmarkActions;
  openLinkDialog: () => void;
  openBulkDialog: () => void;
}) {
  const actionsRef = useRef(actions);
  actionsRef.current = actions;

  useEffect(() => {
    const menus = window.EnderVaultContextMenus;
    const workspace = document.querySelector('.workspace');
    if (!menus || !workspace || !menus.claimPageScope('bookmarks-react')) return;

    const menuActions: Array<Record<string, any>> = [];
    const register = (action: Record<string, any>) => menuActions.push(action);
    const handlers = () => actionsRef.current;
    const open = (item: BookmarkEntry) => {
      if (item.type === 'directory') browse(item.id);
      else window.open(item.openUrl, '_blank', 'noopener,noreferrer');
    };

    register({ id: 'open', group: 'primary',
      label: (context: MenuContext) => context.item?.type === 'directory' ? 'Open directory' : 'Open in new tab',
      icon: (context: MenuContext) => context.item?.type === 'directory'
        ? 'fas fa-folder-open' : 'fas fa-arrow-up-right-from-square',
      visible: (context: MenuContext) => context.mode === 'single',
      run: (context: MenuContext) => open(context.item!),
    });
    register({ id: 'details', group: 'primary', label: 'Details', icon: 'fas fa-circle-info',
      visible: (context: MenuContext) => context.mode === 'single',
      run: (context: MenuContext) => window.location.assign(context.item!.detailUrl),
    });
    register({ id: 'copy-url', group: 'organize', label: 'Copy URL', icon: 'fas fa-copy',
      visible: (context: MenuContext) => context.mode === 'single'
        && context.item?.type === 'link' && Boolean(context.item.url),
      run: (context: MenuContext) => handlers().copyUrl(context.item!),
    });
    register({ id: 'favorite', group: 'organize',
      label: (context: MenuContext) => context.item?.favorite ? 'Remove from favorites' : 'Add to favorites',
      icon: 'fas fa-star', visible: (context: MenuContext) => context.mode === 'single',
      run: (context: MenuContext) => handlers().toggleFavorite(context.item!),
    });
    register({ id: 'refresh-metadata', group: 'organize', label: 'Refresh metadata',
      icon: 'fas fa-wand-magic-sparkles',
      visible: (context: MenuContext) => context.mode === 'single'
        && Boolean(context.item?.metadataRefreshable),
      run: (context: MenuContext) => handlers().refreshMetadata(context.item!),
    });
    register({ id: 'delete', group: 'danger', label: 'Delete', icon: 'fas fa-trash-can', danger: true,
      visible: (context: MenuContext) => context.mode === 'single',
      run: (context: MenuContext) => handlers().deleteEntries([context.item!]),
    });
    register({ id: 'delete-selected', group: 'danger', danger: true, icon: 'fas fa-trash-can',
      label: (context: MenuContext) => `Delete ${context.items.length} selected`,
      visible: (context: MenuContext) => context.mode === 'selection',
      run: (context: MenuContext) => handlers().deleteEntries(context.items),
    });
    register({ id: 'create-link', group: 'background', label: 'Add link', icon: 'fas fa-link',
      visible: (context: MenuContext) => context.mode === 'background', run: openLinkDialog });
    register({ id: 'create-directory', group: 'background', label: 'New directory', icon: 'fas fa-folder-plus',
      visible: (context: MenuContext) => context.mode === 'background', run: handlers().createDirectory });
    register({ id: 'bulk-add', group: 'background', label: 'Bulk add links', icon: 'fas fa-list-ul',
      visible: (context: MenuContext) => context.mode === 'background', run: openBulkDialog });

    const entries = () => {
      const payload = payloadRef.current;
      return payload ? [...payload.directories, ...payload.links] : [];
    };
    const itemForElement = (element: HTMLElement): MenuItem | null => {
      const entry = entries().find((candidate) => candidate.id === element.dataset.bookmarkId);
      return entry ? { ...entry, element } : null;
    };
    const contextForEvent = (event: globalThis.MouseEvent): MenuContext | null => {
      const target = event.target as HTMLElement;
      const element = target.closest<HTMLElement>('[data-context-item="true"]');
      if (element) {
        const item = itemForElement(element);
        if (!item) return null;
        const selectedIds = selectedRef.current;
        const targetSelected = selectedIds.has(item.id);
        const selectedItems = entries().filter((entry) => selectedIds.has(entry.id)).map((entry) => ({
          ...entry,
          element: document.querySelector<HTMLElement>(
            `[data-context-item="true"][data-bookmark-id="${CSS.escape(entry.id)}"]`,
          ) || element,
        }));
        if (!targetSelected && selectedIds.size > 0) setSelected(new Set());
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
      menuId: 'bookmarkContextMenu',
      actions: menuActions,
      contextForEvent,
      errorMessage: 'Bookmark action failed.',
      extraCloseEvents: ['endervault:listing-refreshed'],
    });
    return () => menu?.close();
  }, [browse, openBulkDialog, openLinkDialog, payloadRef, selectedRef, setSelected]);
}

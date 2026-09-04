import { useBrowserContextMenu } from '../shared/browser/useBrowserContextMenu';
import type { BrowserMenuAction, BrowserMenuContext } from '../shared/browser/browser-menu-context';
import type { BookmarkActions } from './useBookmarkActions';
import type { BookmarkEntry, BookmarkPayload } from './types';

type MenuContext = BrowserMenuContext<BookmarkEntry>;

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
  const menuActions: BrowserMenuAction<BookmarkEntry>[] = [];
  const register = (action: BrowserMenuAction<BookmarkEntry>) => menuActions.push(action);
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
    run: (context: MenuContext) => window.EnderVault?.navigate(context.item!.detailUrl)
      || window.location.assign(context.item!.detailUrl),
  });
  register({ id: 'copy-url', group: 'organize', label: 'Copy URL', icon: 'fas fa-copy',
    visible: (context: MenuContext) => context.mode === 'single'
      && context.item?.type === 'link' && Boolean(context.item.url),
    run: (context: MenuContext) => actions.copyUrl(context.item!),
  });
  register({ id: 'favorite', group: 'organize',
    label: (context: MenuContext) => context.item?.favorite ? 'Remove from favorites' : 'Add to favorites',
    icon: 'fas fa-star', visible: (context: MenuContext) => context.mode === 'single',
    run: (context: MenuContext) => actions.toggleFavorite(context.item!),
  });
  register({ id: 'refresh-metadata', group: 'organize', label: 'Refresh metadata',
    icon: 'fas fa-wand-magic-sparkles',
    visible: (context: MenuContext) => context.mode === 'single'
      && Boolean(context.item?.metadataRefreshable),
    run: (context: MenuContext) => actions.refreshMetadata(context.item!),
  });
  register({ id: 'delete', group: 'danger', label: 'Delete', icon: 'fas fa-trash-can', danger: true,
    visible: (context: MenuContext) => context.mode === 'single',
    run: (context: MenuContext) => actions.deleteEntries([context.item!]),
  });
  register({ id: 'delete-selected', group: 'danger', danger: true, icon: 'fas fa-trash-can',
    label: (context: MenuContext) => `Delete ${context.items.length} selected`,
    visible: (context: MenuContext) => context.mode === 'selection',
    run: (context: MenuContext) => actions.deleteEntries(context.items),
  });
  register({ id: 'create-link', group: 'background', label: 'Add link', icon: 'fas fa-link',
    visible: (context: MenuContext) => context.mode === 'background', run: openLinkDialog });
  register({ id: 'create-directory', group: 'background', label: 'New directory', icon: 'fas fa-folder-plus',
    visible: (context: MenuContext) => context.mode === 'background', run: actions.createDirectory });
  register({ id: 'bulk-add', group: 'background', label: 'Bulk add links', icon: 'fas fa-list-ul',
    visible: (context: MenuContext) => context.mode === 'background', run: openBulkDialog });

  const entries = () => {
    const payload = payloadRef.current;
    return payload ? [...payload.directories, ...payload.links] : [];
  };
  useBrowserContextMenu({
    menuId: 'bookmarkContextMenu',
    pageScope: 'bookmarks-react',
    actions: () => menuActions,
    entries,
    itemKey: (entry) => entry.id,
    keyAttribute: 'data-bookmark-id',
    selectedRef,
    setSelected,
    contentKey: payloadRef.current,
    errorMessage: 'Bookmark action failed.',
  });
}

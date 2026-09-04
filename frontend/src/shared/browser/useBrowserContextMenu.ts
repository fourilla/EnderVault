import { useEffect, useRef } from 'react';
import { browserMenuContext } from './browser-menu-context';
import type { BrowserMenuAction } from './browser-menu-context';

export function useBrowserContextMenu<T>(options: {
  menuId: string;
  pageScope: string;
  entries: () => T[];
  itemKey: (entry: T) => string;
  keyAttribute: string;
  selectedRef: React.RefObject<Set<string>>;
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  actions: () => BrowserMenuAction<T>[];
  contentKey: unknown;
  errorMessage: string;
}) {
  const current = useRef(options);
  current.current = options;
  const menuRef = useRef<ReturnType<NonNullable<Window['EnderVaultContextMenus']>['createActionMenu']>>(null);
  useEffect(() => {
    const workspace = document.querySelector('.workspace');
    if (!workspace) return;
    const menu = window.EnderVaultContextMenus?.createActionMenu({
      menuId: options.menuId,
      pageScope: options.pageScope,
      actions: () => current.current.actions(),
      contextForEvent: (event: MouseEvent) => browserMenuContext({
        ...current.current,
        event,
        workspace,
        entries: current.current.entries(),
        selected: current.current.selectedRef.current,
        clearSelection: () => {
          current.current.selectedRef.current = new Set();
          current.current.setSelected(new Set());
        },
      }),
      errorMessage: options.errorMessage,
      extraCloseEvents: ['endervault:listing-refreshed'],
    }) || null;
    menuRef.current = menu;
    return () => {
      menu?.dispose();
      if (menuRef.current === menu) menuRef.current = null;
    };
  }, [options.menuId, options.pageScope, options.errorMessage]);

  // Never leave a menu targeting entries from a previous snapshot or directory.
  useEffect(() => { menuRef.current?.close(); }, [options.contentKey]);
  return menuRef;
}

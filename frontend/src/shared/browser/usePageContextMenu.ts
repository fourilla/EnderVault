import { useEffect, useRef } from 'react';

export interface PageMenuAction {
  id: string;
  group: string;
  label: string;
  icon: string;
  danger?: boolean;
  run: () => unknown;
}

export function usePageContextMenu(pageScope: string, actions: PageMenuAction[], contentKey: unknown) {
  const current = useRef(actions);
  current.current = actions;
  const menu = useRef<ReturnType<NonNullable<Window['EnderVaultContextMenus']>['createActionMenu']>>(null);
  useEffect(() => {
    const menus = window.EnderVaultContextMenus;
    menu.current = menus?.createActionMenu({
      menuId: `${pageScope}ContextMenu`, pageScope,
      actions: () => current.current,
      contextForEvent: (event: MouseEvent) => menus.pageContextForEvent(event),
    }) || null;
    return () => { menu.current?.dispose(); menu.current = null; };
  }, [pageScope]);
  useEffect(() => { menu.current?.close(); }, [contentKey]);
}

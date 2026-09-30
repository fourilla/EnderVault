import { AppDialog } from '../shared/dialogs/AppDialog';
import type { PropsWithChildren } from 'react';

export function SidebarDrawer({ open, close, children }: PropsWithChildren<{ open: boolean; close: () => void }>) {
  return <AppDialog open={open} onDismiss={close} dismissOnBackdrop
    labelledBy="sidebar-drawer-title" className="sidebar-drawer">
    <span id="sidebar-drawer-title" className="sr-only">Navigation</span>
    <div className="sidebar-drawer-content" onClick={(event) => {
      if (event.target instanceof Element && event.target.closest('a[href]')) close();
    }}>{children}</div>
  </AppDialog>;
}

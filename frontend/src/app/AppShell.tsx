import { useEffect, useState } from 'react';
import { Outlet, ScrollRestoration, useLocation } from 'react-router-dom';
import { AdminSidebar } from './AdminSidebar';
import { AdminTopbar } from './AdminTopbar';
import { SidebarDrawer } from './SidebarDrawer';
import { useSidebarLayout } from './useSidebarLayout';
import { navigationEntryForPathname } from './navigation';
import { SpaNavigationBridge } from './SpaNavigationBridge';
import { TopbarPopoverProvider } from './TopbarPopoverContext';
import { RemoteDownloadTasksProvider } from './remote-downloads/RemoteDownloadTasksContext';
import { ShellStatusProvider } from './ShellStatusContext';
import { DialogHost } from '../shared/dialogs/DialogHost';
import { DecisionDialogProvider } from '../pending-decisions/DecisionDialogContext';
import './app-shell.css';

export function AppShell() {
  const location = useLocation();
  const sidebar = useSidebarLayout(location.key);
  const [favoritesOpen, setFavoritesOpen] = useState(true);
  const sidebarContent = <AdminSidebar favoritesOpen={favoritesOpen}
    onToggleFavorites={() => setFavoritesOpen((open) => !open)} />;

  useEffect(() => {
    const entry = navigationEntryForPathname(location.pathname);
    document.title = entry ? `EnderVault ${entry.label}` : 'EnderVault';
    const frame = window.requestAnimationFrame(() => {
      document.dispatchEvent(new CustomEvent('endervault:spa-shell-ready'));
      if (entry?.surface === 'spa'
          && entry.id !== 'files'
          && entry.id !== 'file-detail'
          && entry.id !== 'bookmarks'
          && entry.id !== 'bookmark-detail'
          && entry.id !== 'file-request-detail') {
        document.dispatchEvent(new CustomEvent('endervault:sticky-context-changed', {
          detail: {
            targetType: 'PAGE',
            targetKey: entry.stickyNotePageKey || entry.id,
            surface: 'PAGE',
            label: entry.label,
          },
        }));
      }
    });
    return () => window.cancelAnimationFrame(frame);
  }, [location.pathname]);

  return (
    <RemoteDownloadTasksProvider>
      <ShellStatusProvider>
        <div className={`app-shell admin-react-shell${sidebar.collapsed ? ' sidebar-collapsed' : ''}`}>
          <SpaNavigationBridge />
          <DialogHost routeKey={location.key} />
          <TopbarPopoverProvider>
            <DecisionDialogProvider>
            <AdminTopbar sidebarCollapsed={sidebar.mobile ? !sidebar.drawerOpen : sidebar.collapsed}
              onToggleSidebar={sidebar.toggle} />
            {sidebar.mobile ? <SidebarDrawer open={sidebar.drawerOpen} close={sidebar.closeDrawer}>
              {sidebarContent}
            </SidebarDrawer> : sidebarContent}
            <div className="app-main" data-file-dropzone>
              <main className="workspace">
                <Outlet />
              </main>
              <ScrollRestoration />
            </div>
            </DecisionDialogProvider>
          </TopbarPopoverProvider>
        </div>
      </ShellStatusProvider>
    </RemoteDownloadTasksProvider>
  );
}

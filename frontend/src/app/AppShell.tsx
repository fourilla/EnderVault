import { useEffect } from 'react';
import { Outlet, ScrollRestoration, useLocation } from 'react-router-dom';
import { AdminSidebar } from './AdminSidebar';
import { AdminTopbar } from './AdminTopbar';
import { navigationEntryForPathname } from './navigation';
import { SpaNavigationBridge } from './SpaNavigationBridge';
import { TopbarPopoverProvider } from './TopbarPopoverContext';
import { RemoteDownloadTasksProvider } from './remote-downloads/RemoteDownloadTasksContext';
import { ShellStatusProvider } from './ShellStatusContext';
import { DialogHost } from '../shared/dialogs/DialogHost';
import './app-shell.css';

export function AppShell() {
  const location = useLocation();

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
        <div className="app-shell admin-react-shell">
          <SpaNavigationBridge />
          <DialogHost routeKey={location.key} />
          <AdminSidebar />
          <div className="app-main" data-file-dropzone>
            <TopbarPopoverProvider>
              <AdminTopbar />
            </TopbarPopoverProvider>
            <main className="workspace">
              <Outlet />
            </main>
            <ScrollRestoration />
          </div>
        </div>
      </ShellStatusProvider>
    </RemoteDownloadTasksProvider>
  );
}

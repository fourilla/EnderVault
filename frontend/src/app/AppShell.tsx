import { useEffect } from 'react';
import { Outlet, ScrollRestoration, useLocation } from 'react-router-dom';
import { AdminSidebar } from './AdminSidebar';
import { AdminTopbar } from './AdminTopbar';
import { navigationEntries } from './navigation';
import './app-shell.css';

export function AppShell() {
  const location = useLocation();

  useEffect(() => {
    const entry = navigationEntries.find((candidate) => candidate.path === location.pathname);
    document.title = entry ? `EnderVault ${entry.label}` : 'EnderVault';
    const frame = window.requestAnimationFrame(() => {
      document.dispatchEvent(new CustomEvent('endervault:spa-shell-ready'));
      if (entry?.surface === 'spa'
          && entry.id !== 'files'
          && entry.id !== 'file-detail'
          && entry.id !== 'bookmarks') {
        document.dispatchEvent(new CustomEvent('endervault:sticky-context-changed', {
          detail: {
            targetType: 'PAGE',
            targetKey: entry.id,
            surface: 'PAGE',
            label: entry.label,
          },
        }));
      }
    });
    return () => window.cancelAnimationFrame(frame);
  }, [location.pathname]);

  return (
    <div className="app-shell admin-react-shell">
      <AdminSidebar />
      <div className="app-main" data-file-dropzone>
        <AdminTopbar />
        <main className="workspace">
          <Outlet />
        </main>
        <ScrollRestoration />
      </div>
    </div>
  );
}

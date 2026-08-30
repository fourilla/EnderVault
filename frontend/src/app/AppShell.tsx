import { useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { AdminSidebar } from './AdminSidebar';
import { AdminTopbar } from './AdminTopbar';
import { navigationEntries } from './navigation';
import './app-shell.css';

export function AppShell() {
  const location = useLocation();

  useEffect(() => {
    const entry = navigationEntries.find((candidate) => candidate.path === location.pathname);
    document.title = entry ? `EnderVault ${entry.label}` : 'EnderVault';
  }, [location.pathname]);

  return (
    <div className="app-shell admin-react-shell">
      <AdminSidebar />
      <div className="app-main" data-file-dropzone>
        <AdminTopbar />
        <div className="toast-region" id="toastRegion" aria-live="polite" aria-atomic="false" />
        <main className="workspace">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

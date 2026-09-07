import { AdminAppProvider } from './AdminAppContext';
import { AdminAppRouter } from './AdminAppRouter';
import { RouteSearchProvider } from './RouteSearch';
import { UploadManagerProvider } from './uploads/UploadManagerContext';

export function AdminApp() {
  return (
    <AdminAppProvider>
      <UploadManagerProvider>
        <RouteSearchProvider>
          <AdminAppRouter />
        </RouteSearchProvider>
      </UploadManagerProvider>
    </AdminAppProvider>
  );
}

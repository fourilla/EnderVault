import { AdminAppProvider } from './AdminAppContext';
import { AdminAppRouter } from './AdminAppRouter';
import { RouteActionsProvider } from './RouteActions';
import { UploadManagerProvider } from './uploads/UploadManagerContext';

export function AdminApp() {
  return (
    <AdminAppProvider>
      <UploadManagerProvider>
        <RouteActionsProvider>
          <AdminAppRouter />
        </RouteActionsProvider>
      </UploadManagerProvider>
    </AdminAppProvider>
  );
}

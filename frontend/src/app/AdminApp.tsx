import { AdminAppProvider } from './AdminAppContext';
import { AdminAppRouter } from './AdminAppRouter';
import { RouteActionsProvider } from './RouteActions';

export function AdminApp() {
  return (
    <AdminAppProvider>
      <RouteActionsProvider>
        <AdminAppRouter />
      </RouteActionsProvider>
    </AdminAppProvider>
  );
}

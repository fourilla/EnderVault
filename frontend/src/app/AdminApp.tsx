import { AdminAppProvider } from './AdminAppContext';
import { AdminAppRouter } from './AdminAppRouter';

export function AdminApp() {
  return (
    <AdminAppProvider>
      <AdminAppRouter />
    </AdminAppProvider>
  );
}

import { Suspense } from 'react';
import {
  createBrowserRouter,
  isRouteErrorResponse,
  RouterProvider,
  useNavigate,
  useRouteError,
  type RouteObject,
} from 'react-router-dom';
import { AppShell } from './AppShell';
import { spaRoutes } from './navigation';
import { createListingHistory } from '../shared/browser/listing-history';
import { ListingHistoryProvider } from '../shared/browser/ListingHistoryContext';

function RouteLoading() {
  return (
    <main className="browser-load-state" aria-live="polite">
      <i className="fas fa-spinner fa-spin" aria-hidden="true" />
      <span>Loading page...</span>
    </main>
  );
}

function RouteError() {
  const error = useRouteError();
  const navigate = useNavigate();
  const message = isRouteErrorResponse(error)
    ? (typeof error.data === 'string' ? error.data : `${error.status} ${error.statusText}`)
    : error instanceof Error ? error.message : 'This page could not be loaded.';
  return (
    <main className="browser-load-state browser-load-error" role="alert">
      <strong>Page unavailable</strong>
      <span>{message}</span>
      <div className="browser-load-actions">
        <button className="ghost" type="button" onClick={() => window.location.reload()}>Reload</button>
        <button type="button" onClick={() => navigate('/files', { replace: true })}>Open Files</button>
      </div>
    </main>
  );
}

function RouteNotFound() {
  const navigate = useNavigate();
  return (
    <main className="browser-load-state browser-load-error" role="alert">
      <strong>Page not found</strong>
      <span>This address is not registered in the EnderVault application shell.</span>
      <button type="button" onClick={() => navigate('/files', { replace: true })}>Open Files</button>
    </main>
  );
}

const routeObjects: RouteObject[] = spaRoutes().map((entry) => {
  const Component = entry.component;
  return {
    path: entry.path,
    element: (
      <Suspense fallback={<RouteLoading />}>
        <Component />
      </Suspense>
    ),
    errorElement: <RouteError />,
  };
});

const router = createBrowserRouter([{
  element: <AppShell />,
  errorElement: <RouteError />,
  children: [...routeObjects, { path: '*', element: <RouteNotFound /> }],
}]);
const listingHistory = createListingHistory(router, () => window.scrollY, () => window.sessionStorage);

export function AdminAppRouter() {
  return <ListingHistoryProvider history={listingHistory}><RouterProvider router={router} /></ListingHistoryProvider>;
}

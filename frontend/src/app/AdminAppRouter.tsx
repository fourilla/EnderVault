import { Suspense } from 'react';
import {
  createBrowserRouter,
  RouterProvider,
  useRouteError,
  type RouteObject,
} from 'react-router-dom';
import { spaRoutes } from './navigation';

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
  const message = error instanceof Error ? error.message : 'This page could not be loaded.';
  return (
    <main className="browser-load-state browser-load-error" role="alert">
      <strong>Page unavailable</strong>
      <span>{message}</span>
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

const router = createBrowserRouter(routeObjects);

export function AdminAppRouter() {
  return <RouterProvider router={router} />;
}

import '../../../src/main/resources/static/js/endervault-core.js';
import '../../../src/main/resources/static/js/context-menu.js';
import './public-share.css';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { ListingHistoryProvider } from '../shared/browser/ListingHistoryContext';
import { createListingHistory } from '../shared/browser/listing-history';
import { PublicShareApp } from './PublicShareApp';
import type { SharedBootstrap } from './types';

const root = document.getElementById('public-share-root');
const bootstrapElement = document.getElementById('public-share-bootstrap');
if (root && bootstrapElement?.textContent) {
  const bootstrap: SharedBootstrap = JSON.parse(bootstrapElement.textContent);
  const app = <PublicShareApp bootstrap={bootstrap} />;
  const router = createBrowserRouter([
    { path: '/s/:token', element: app },
    { path: '/s/:token/file', element: app },
  ]);
  const history = createListingHistory(router, () => window.scrollY, () => window.sessionStorage,
    'endervault.public-share.entries.v1');
  createRoot(root).render(<ListingHistoryProvider history={history}>
    <RouterProvider router={router} />
  </ListingHistoryProvider>);
}

import '../../../src/main/resources/static/js/endervault-core.js';
import '../../../src/main/resources/static/js/context-menu.js';
import './public-share.css';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { ListingHistoryProvider } from '../shared/browser/ListingHistoryContext';
import { createListingHistory } from '../shared/browser/listing-history';
import { PublicShareApp, PublicShareLoading } from './PublicShareApp';
import { createPublicShareRoutes, initializeSharedHistoryKey } from './public-share-router';
import type { SharedBootstrap } from './types';

const root = document.getElementById('public-share-root');
const bootstrapElement = document.getElementById('public-share-bootstrap');
if (root && bootstrapElement?.textContent) {
  const bootstrap: SharedBootstrap = JSON.parse(bootstrapElement.textContent);
  // Avoid a default-key replace navigation interrupting the initial loader.
  initializeSharedHistoryKey(window.history);
  window.history.scrollRestoration = 'manual';
  const router = createBrowserRouter(createPublicShareRoutes(bootstrap,
    <PublicShareApp bootstrap={bootstrap} />, <PublicShareLoading bootstrap={bootstrap} />));
  const history = createListingHistory(router, () => window.scrollY, () => window.sessionStorage,
    'endervault.public-share.entries.v1');
  createRoot(root).render(<ListingHistoryProvider history={history}>
    <RouterProvider router={router} />
  </ListingHistoryProvider>);
}

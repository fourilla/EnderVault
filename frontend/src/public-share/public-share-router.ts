import type { ReactNode } from 'react';
import { matchRoutes, type LoaderFunctionArgs, type RouteObject } from 'react-router-dom';
import { PublicShareError, requestSharedView, sharedViewUrl } from './public-share-request';
import type { SharedBootstrap, SharedView } from './types';

export type SharedViewSnapshot = { data: SharedView; error?: never } | { error: string; data?: never };

const routePatterns = [{ path: '/s/:token' }, { path: '/s/:token/file' }];

export function sharedRouteToken(pathname: string): string | undefined {
  return matchRoutes(routePatterns, pathname)?.at(-1)?.params.token;
}

export function createPublicShareRoutes(bootstrap: SharedBootstrap, element: ReactNode,
  initialFallback: ReactNode, request = requestSharedView): RouteObject[] {
  const loader = async ({ params, request: navigationRequest }: LoaderFunctionArgs): Promise<SharedViewSnapshot> => {
    const { signal } = navigationRequest;
    signal.throwIfAborted();
    if (params.token !== bootstrap.token) return { error: 'This shared item is unavailable.' };
    const url = new URL(navigationRequest.url);
    const route = matchRoutes(routePatterns, url.pathname)?.at(-1)?.route;
    // Router accepts trailing slashes, encoded tokens and case variants of the same route.
    const pathname = route?.path === routePatterns[1].path ? `${bootstrap.rootUrl}/file` : bootstrap.rootUrl;
    try {
      const data = await request(sharedViewUrl(bootstrap, pathname, url.search), signal);
      // A request adapter may finish after cancellation; never convert that result to a view.
      signal.throwIfAborted();
      return { data };
    } catch (error: unknown) {
      signal.throwIfAborted();
      return { error: error instanceof PublicShareError
        ? error.message : 'The shared item could not be loaded. Try again.' };
    }
  };
  return routePatterns.map(route => ({
    ...route, element, loader, shouldRevalidate: () => true, hydrateFallbackElement: initialFallback,
  }));
}

// Assign the first visit before Router starts its loader. A Router replace would load it twice.
export function initializeSharedHistoryKey(browserHistory: Pick<History, 'state' | 'replaceState'>) {
  const state = browserHistory.state;
  if (typeof state?.key === 'string' && state.key.trim() && state.key !== 'default') return;
  browserHistory.replaceState({ ...(state && typeof state === 'object' ? state : {}),
    key: Math.random().toString(36).slice(2, 10) }, '');
}

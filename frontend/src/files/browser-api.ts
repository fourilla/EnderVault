import type { BrowserHistoryState, BrowserPayload } from './types';

const endpointFor = (state: BrowserHistoryState) =>
  state.mode === 'search' ? '/api/v1/fs/search' : '/api/v1/fs/listing';

export const loadBrowserPayload = async (
  state: BrowserHistoryState,
  signal: AbortSignal,
): Promise<BrowserPayload> => {
  const query = new URLSearchParams();
  if (state.path) query.set('path', state.path);
  if (state.mode === 'search' && state.query) query.set('q', state.query);
  if (state.page > 1) query.set('page', String(state.page));
  if (state.view) query.set('view', state.view);
  if (state.sort) query.set('sort', state.sort);
  if (state.direction) query.set('dir', state.direction);
  if (state.hidden) query.set('hidden', state.hidden);
  if (state.pageSize) query.set('size', String(state.pageSize));

  const response = await fetch(`${endpointFor(state)}?${query.toString()}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = (await response.json()) as BrowserPayload & {
    notification?: { message?: string };
    message?: string;
  };
  if (!response.ok) {
    throw new Error(body.notification?.message || body.message || 'The file list could not be loaded.');
  }
  return body;
};

export const canonicalState = (
  state: BrowserHistoryState,
  payload: BrowserPayload,
): BrowserHistoryState => ({
  ...state,
  mode: payload.mode,
  path: payload.path,
  query: payload.search.query,
  page: payload.page.number,
  view: payload.preferences.view,
  sort: payload.preferences.sort,
  direction: payload.preferences.direction,
  hidden: payload.preferences.hidden,
  pageSize: payload.preferences.pageSize,
});

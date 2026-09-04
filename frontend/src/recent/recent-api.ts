import type { RecentHistoryState, RecentPayload } from './types';

export const loadRecentPayload = async (state: RecentHistoryState, signal: AbortSignal) => {
  const query = new URLSearchParams();
  if (state.query) query.set('q', state.query);
  if (state.page > 1) query.set('page', String(state.page));
  if (state.view) query.set('view', state.view);
  if (state.sort) query.set('sort', state.sort);
  if (state.direction) query.set('dir', state.direction);
  if (state.hidden) query.set('hidden', state.hidden);
  if (state.pageSize) query.set('size', String(state.pageSize));
  const response = await fetch('/api/v1/recent?' + query.toString(), {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as RecentPayload & { message?: string };
  if (!response.ok) throw new Error(body.message || 'Recent items could not be loaded.');
  return body;
};

export const canonicalRecentState = (
  state: RecentHistoryState,
  payload: RecentPayload,
): RecentHistoryState => ({
  ...state,
  query: payload.search.query,
  page: payload.page.number,
  view: payload.preferences.view,
  sort: payload.preferences.sort,
  direction: payload.preferences.direction,
  hidden: payload.preferences.hidden,
  pageSize: payload.preferences.pageSize,
});

import type { RecentHistoryState, RecentSort } from './types';

const STORAGE_KEY = 'endervault.recent.history-state.v1';
const CANONICAL_URL = '/files/recent';

const finiteInteger = (value: unknown, fallback: number, minimum = 0) => {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value || ''), 10);
  return Number.isFinite(parsed) ? Math.max(minimum, Math.trunc(parsed)) : fallback;
};
const recentSort = (value: unknown): RecentSort | undefined =>
  value === 'recent' || value === 'name' || value === 'size' || value === 'modified' || value === 'type'
    ? value : undefined;
const view = (value: unknown) => value === 'grid' || value === 'table' ? value : undefined;
const direction = (value: unknown) => value === 'asc' || value === 'desc' ? value : undefined;
const hidden = (value: unknown) => value === 'show' || value === 'hide' ? value : undefined;

export const defaultRecentState = (): RecentHistoryState => ({
  surface: 'recent', version: 1, query: '', page: 1, scrollTop: 0,
});

export const parseRecentState = (candidate: unknown): RecentHistoryState | null => {
  if (!candidate || typeof candidate !== 'object') return null;
  const raw = candidate as Partial<RecentHistoryState>;
  if (raw.surface !== 'recent' || raw.version !== 1) return null;
  return {
    surface: 'recent',
    version: 1,
    query: typeof raw.query === 'string' ? raw.query : '',
    page: finiteInteger(raw.page, 1, 1),
    view: view(raw.view),
    sort: recentSort(raw.sort),
    direction: direction(raw.direction),
    hidden: hidden(raw.hidden),
    pageSize: raw.pageSize == null ? undefined : finiteInteger(raw.pageSize, 200, 1),
    scrollTop: finiteInteger(raw.scrollTop, 0),
  };
};

const stateFromUrl = (): RecentHistoryState | null => {
  const url = new URL(window.location.href);
  if (url.searchParams.size === 0) return null;
  return {
    surface: 'recent',
    version: 1,
    query: url.searchParams.get('q') || '',
    page: finiteInteger(url.searchParams.get('page'), 1, 1),
    view: view(url.searchParams.get('view')),
    sort: recentSort(url.searchParams.get('sort')),
    direction: direction(url.searchParams.get('dir')),
    hidden: hidden(url.searchParams.get('hidden')),
    pageSize: url.searchParams.has('size') ? finiteInteger(url.searchParams.get('size'), 200, 1) : undefined,
    scrollTop: 0,
  };
};

const reloadState = (): RecentHistoryState | null => {
  const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
  if (navigation?.type !== 'reload') return null;
  try {
    return parseRecentState(JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null'));
  } catch {
    return null;
  }
};

export const initialRecentState = () => parseRecentState(window.history.state)
  || stateFromUrl()
  || reloadState()
  || defaultRecentState();

export const rememberRecentState = (state: RecentHistoryState, replace = false) => {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Browser history remains available when session storage is blocked.
  }
  window.history[replace ? 'replaceState' : 'pushState'](state, '', CANONICAL_URL);
};

import type {
  BrowserHistoryState,
  BrowserMode,
  BrowserSort,
  BrowserView,
  HiddenMode,
  SortDirection,
} from './types';

const STORAGE_KEY = 'endervault.files.history-state.v1';
const CANONICAL_URL = '/files';

const mode = (value: unknown): BrowserMode => (value === 'search' ? 'search' : 'browse');
const view = (value: unknown): BrowserView | undefined =>
  value === 'grid' || value === 'table' ? value : undefined;
const sort = (value: unknown): BrowserSort | undefined =>
  value === 'name' || value === 'size' || value === 'modified' || value === 'type'
    ? value
    : undefined;
const direction = (value: unknown): SortDirection | undefined =>
  value === 'asc' || value === 'desc' ? value : undefined;
const hidden = (value: unknown): HiddenMode | undefined =>
  value === 'hide' || value === 'show' ? value : undefined;
const finiteInteger = (value: unknown, fallback: number, minimum = 0) => {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value || ''), 10);
  return Number.isFinite(parsed) ? Math.max(minimum, Math.trunc(parsed)) : fallback;
};

export const defaultBrowserState = (): BrowserHistoryState => ({
  version: 1,
  mode: 'browse',
  path: '',
  query: '',
  page: 1,
  scrollTop: 0,
});

export const parseBrowserState = (candidate: unknown): BrowserHistoryState | null => {
  if (!candidate || typeof candidate !== 'object') return null;
  const raw = candidate as Partial<BrowserHistoryState>;
  if (raw.version !== 1) return null;
  return {
    version: 1,
    mode: mode(raw.mode),
    path: typeof raw.path === 'string' ? raw.path : '',
    query: typeof raw.query === 'string' ? raw.query : '',
    page: finiteInteger(raw.page, 1, 1),
    view: view(raw.view),
    sort: sort(raw.sort),
    direction: direction(raw.direction),
    hidden: hidden(raw.hidden),
    pageSize: raw.pageSize == null ? undefined : finiteInteger(raw.pageSize, 200, 1),
    scrollTop: finiteInteger(raw.scrollTop, 0),
  };
};

const stateFromLegacyUrl = (): BrowserHistoryState | null => {
  const url = new URL(window.location.href);
  const hasState = Array.from(url.searchParams.keys()).length > 0;
  if (!hasState) return null;
  return {
    version: 1,
    mode: 'browse',
    path: url.searchParams.get('path') || '',
    query: '',
    page: finiteInteger(url.searchParams.get('page'), 1, 1),
    view: view(url.searchParams.get('view')),
    sort: sort(url.searchParams.get('sort')),
    direction: direction(url.searchParams.get('dir')),
    hidden: hidden(url.searchParams.get('hidden')),
    pageSize: url.searchParams.has('size')
      ? finiteInteger(url.searchParams.get('size'), 200, 1)
      : undefined,
    scrollTop: 0,
  };
};

const reloadedPage = () => {
  const navigation = performance.getEntriesByType('navigation')[0] as
    | PerformanceNavigationTiming
    | undefined;
  return navigation?.type === 'reload';
};

const sessionState = (): BrowserHistoryState | null => {
  if (!reloadedPage()) return null;
  try {
    return parseBrowserState(JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null'));
  } catch {
    return null;
  }
};

export const initialBrowserState = (): BrowserHistoryState =>
  parseBrowserState(window.history.state)
  || stateFromLegacyUrl()
  || sessionState()
  || defaultBrowserState();

export const rememberBrowserState = (
  state: BrowserHistoryState,
  replace = false,
) => {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // History remains functional when session storage is unavailable.
  }
  window.history[replace ? 'replaceState' : 'pushState'](state, '', CANONICAL_URL);
};

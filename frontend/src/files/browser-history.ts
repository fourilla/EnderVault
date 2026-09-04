import type {
  BrowserHistoryState,
  BrowserMode,
  BrowserSort,
  BrowserView,
  HiddenMode,
  SortDirection,
} from './types';

import type { ListingHistoryConfig } from '../shared/browser/listing-history';

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
  surface: 'files',
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
  if (raw.surface !== 'files' || raw.version !== 1) return null;
  return {
    surface: 'files',
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

const stateFromSearch = (search: string): BrowserHistoryState => {
  const params = new URLSearchParams(search);
  return {
    surface: 'files',
    version: 1,
    mode: 'browse',
    path: params.get('path') || '',
    query: '',
    page: finiteInteger(params.get('page'), 1, 1),
    view: view(params.get('view')),
    sort: sort(params.get('sort')),
    direction: direction(params.get('dir')),
    hidden: hidden(params.get('hidden')),
    pageSize: params.has('size')
      ? finiteInteger(params.get('size'), 200, 1)
      : undefined,
    scrollTop: 0,
  };
};

export const browserHistory: ListingHistoryConfig<BrowserHistoryState> = {
  pathname: '/files', parse: parseBrowserState, fromSearch: stateFromSearch,
};

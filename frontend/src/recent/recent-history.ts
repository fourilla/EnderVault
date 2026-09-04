import type { RecentHistoryState, RecentSort } from './types';

import type { ListingHistoryConfig } from '../shared/browser/listing-history';

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

const stateFromSearch = (search: string): RecentHistoryState => {
  const params = new URLSearchParams(search);
  return {
    surface: 'recent',
    version: 1,
    query: params.get('q') || '',
    page: finiteInteger(params.get('page'), 1, 1),
    view: view(params.get('view')),
    sort: recentSort(params.get('sort')),
    direction: direction(params.get('dir')),
    hidden: hidden(params.get('hidden')),
    pageSize: params.has('size') ? finiteInteger(params.get('size'), 200, 1) : undefined,
    scrollTop: 0,
  };
};

export const recentHistory: ListingHistoryConfig<RecentHistoryState> = {
  pathname: '/files/recent', parse: parseRecentState, fromSearch: stateFromSearch,
};

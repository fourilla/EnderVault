import type { BookmarkHistoryState } from './types';

import type { ListingHistoryConfig } from '../shared/browser/listing-history';

const scrollTop = (value: unknown) => {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value || ''), 10);
  return Number.isFinite(parsed) ? Math.max(0, Math.trunc(parsed)) : 0;
};

export const defaultBookmarkState = (): BookmarkHistoryState => ({
  surface: 'bookmarks', version: 1, directoryId: '', query: '', scrollTop: 0,
});

export const parseBookmarkState = (candidate: unknown): BookmarkHistoryState | null => {
  if (!candidate || typeof candidate !== 'object') return null;
  const raw = candidate as Partial<BookmarkHistoryState>;
  if (raw.surface !== 'bookmarks' || raw.version !== 1) return null;
  return {
    surface: 'bookmarks',
    version: 1,
    directoryId: typeof raw.directoryId === 'string' ? raw.directoryId : '',
    query: typeof raw.query === 'string' ? raw.query : '',
    scrollTop: scrollTop(raw.scrollTop),
  };
};

const stateFromSearch = (search: string): BookmarkHistoryState => {
  const params = new URLSearchParams(search);
  return {
    surface: 'bookmarks',
    version: 1,
    directoryId: params.get('directory') || '',
    query: params.get('q') || '',
    scrollTop: 0,
  };
};

export const bookmarkHistory: ListingHistoryConfig<BookmarkHistoryState> = {
  pathname: '/files/bookmarks', parse: parseBookmarkState, fromSearch: stateFromSearch,
};

import type { BookmarkHistoryState } from './types';

const STORAGE_KEY = 'endervault.bookmarks.history-state.v1';
const CANONICAL_URL = '/files/bookmarks';

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

const stateFromUrl = (): BookmarkHistoryState | null => {
  const url = new URL(window.location.href);
  if (!url.searchParams.has('directory') && !url.searchParams.has('q')) return null;
  return {
    surface: 'bookmarks',
    version: 1,
    directoryId: url.searchParams.get('directory') || '',
    query: url.searchParams.get('q') || '',
    scrollTop: 0,
  };
};

const reloadedState = (): BookmarkHistoryState | null => {
  const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
  if (navigation?.type !== 'reload') return null;
  try {
    return parseBookmarkState(JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null'));
  } catch {
    return null;
  }
};

export const initialBookmarkState = () => parseBookmarkState(window.history.state)
  || stateFromUrl()
  || reloadedState()
  || defaultBookmarkState();

export const rememberBookmarkState = (state: BookmarkHistoryState, replace = false) => {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Browser history remains available when session storage is blocked.
  }
  window.history[replace ? 'replaceState' : 'pushState'](state, '', CANONICAL_URL);
};

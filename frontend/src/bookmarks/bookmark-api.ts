import type { BookmarkHistoryState, BookmarkPayload } from './types';

export const loadBookmarks = async (state: BookmarkHistoryState, signal: AbortSignal) => {
  const query = new URLSearchParams();
  if (state.directoryId) query.set('directory', state.directoryId);
  if (state.query) query.set('q', state.query);
  const response = await fetch('/api/v1/bookmarks?' + query.toString(), {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as BookmarkPayload & { message?: string };
  if (!response.ok) throw new Error(body.message || 'Bookmarks could not be loaded.');
  return body;
};

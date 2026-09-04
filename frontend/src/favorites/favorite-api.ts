import type { FavoritesPayload } from './types';

export const loadFavorites = async (signal: AbortSignal) => {
  const response = await fetch('/api/v1/favorites', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as FavoritesPayload & { message?: string };
  if (!response.ok) throw new Error(body.message || 'Favorites could not be loaded.');
  return body;
};

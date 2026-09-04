import { notify, postForm } from './form-api';

export interface FavoriteToggleResult {
  active?: boolean;
}

type FavoriteDirection = 'up' | 'down';

const favoritesChanged = () =>
  document.dispatchEvent(new CustomEvent('endervault:favorites-changed'));

export async function togglePathFavorite(path: string): Promise<FavoriteToggleResult> {
  const body = await postForm('/api/v1/favorites/toggle', { path });
  notify(body);
  favoritesChanged();
  return body;
}

export async function toggleBookmarkFavorite(id: string): Promise<FavoriteToggleResult> {
  const body = await postForm('/api/v1/favorites/toggle-bookmark', { id });
  notify(body);
  favoritesChanged();
  return body;
}

export async function removeFavorite(path: string): Promise<unknown> {
  const body = await postForm('/api/v1/favorites/remove', { path });
  notify(body);
  favoritesChanged();
  return body;
}

export async function moveFavorite(path: string, direction: FavoriteDirection): Promise<unknown> {
  const body = await postForm('/api/v1/favorites/move', { path, direction });
  notify(body);
  favoritesChanged();
  return body;
}

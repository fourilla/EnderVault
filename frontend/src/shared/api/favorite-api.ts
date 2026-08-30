import { notify, postForm } from './form-api';

export interface FavoriteToggleResult {
  active?: boolean;
}

type FavoriteDirection = 'up' | 'down';

const favoritesChanged = () =>
  document.dispatchEvent(new CustomEvent('endervault:favorites-changed'));

const useLegacySidebarBridge = () =>
  !document.getElementById('admin-app-root') && Boolean(window.EnderVaultFavorites);

export async function togglePathFavorite(path: string): Promise<FavoriteToggleResult> {
  if (useLegacySidebarBridge()) {
    return window.EnderVaultFavorites!.togglePath(path);
  }
  const body = await postForm('/api/v1/favorites/toggle', { path });
  notify(body);
  favoritesChanged();
  return body;
}

export async function toggleBookmarkFavorite(id: string): Promise<FavoriteToggleResult> {
  if (useLegacySidebarBridge()) {
    return window.EnderVaultFavorites!.toggleBookmark(id);
  }
  const body = await postForm('/api/v1/favorites/toggle-bookmark', { id });
  notify(body);
  favoritesChanged();
  return body;
}

export async function removeFavorite(path: string): Promise<unknown> {
  if (useLegacySidebarBridge()) return window.EnderVaultFavorites!.remove(path);
  const body = await postForm('/api/v1/favorites/remove', { path });
  notify(body);
  favoritesChanged();
  return body;
}

export async function moveFavorite(path: string, direction: FavoriteDirection): Promise<unknown> {
  if (useLegacySidebarBridge()) return window.EnderVaultFavorites!.move(path, direction);
  const body = await postForm('/api/v1/favorites/move', { path, direction });
  notify(body);
  favoritesChanged();
  return body;
}

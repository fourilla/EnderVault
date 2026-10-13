import { notify, postForm } from './form-api';
import { resolveSelectedItems } from './selected-item-api';

export interface FavoriteToggleResult {
  active?: boolean;
}

type FavoriteDirection = 'up' | 'down';

export const favoritesChanged = () =>
  document.dispatchEvent(new CustomEvent('endervault:favorites-changed'));

export function observeFavoriteFileTasks() {
  const refresh = (event: Event) => {
    const task = (event as CustomEvent<{ type: string; active: boolean }>).detail;
    if (task?.active !== false || !['FILE_MOVE', 'FILE_TRASH', 'DIRECTORY_MERGE'].includes(task.type)) return;
    // Partial, canceled and failed batches can already have changed paths or removed favorites.
    favoritesChanged();
  };
  document.addEventListener('endervault:task-terminal', refresh);
  return () => document.removeEventListener('endervault:task-terminal', refresh);
}

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

export async function removeSelectedFavorites(ids: readonly string[]) {
  const result = await resolveSelectedItems('/api/v1/favorites/selected/resolve', ids, 'REMOVE');
  if (result.succeededCount) favoritesChanged();
  return result;
}

import { moveFavorite, removeFavorite, removeSelectedFavorites } from '../shared/api/favorite-api';
import { openListTarget, type ListItemAction } from '../shared/browser/list-item-actions';
import type { FavoriteEntry } from './types';

export const favoriteItemKey = (item: FavoriteEntry) => item.path;
export const favoriteItemIdentity = (item: FavoriteEntry) => JSON.stringify([item.path, item.openUrl, item.detailUrl, item.openInNewTab]);
export const favoriteListActions = (items: readonly FavoriteEntry[]): readonly ListItemAction<FavoriteEntry>[] => [
  { id: 'favorite-open', label: 'Open', icon: 'fas fa-folder-open', group: 'open', inRow: false,
    supports: item => Boolean(item.openUrl), href: item => item.openUrl, newTab: item => item.openInNewTab,
    execute: item => openListTarget(item.openUrl, item.openInNewTab) },
  { id: 'favorite-details', label: 'Details', icon: 'fas fa-circle-info', group: 'open', inRow: false,
    supports: item => Boolean(item.detailUrl) && item.detailUrl !== item.openUrl,
    execute: item => openListTarget(item.detailUrl) },
  { id: 'favorite-up', label: 'Move up', icon: 'fas fa-arrow-up', group: 'order', supports: () => true,
    disabled: item => items.findIndex(candidate => candidate.path === item.path) <= 0,
    changesList: true, execute: item => moveFavorite(item.path, 'up') },
  { id: 'favorite-down', label: 'Move down', icon: 'fas fa-arrow-down', group: 'order', supports: () => true,
    disabled: item => { const index = items.findIndex(candidate => candidate.path === item.path);
      return index < 0 || index === items.length - 1; },
    changesList: true, execute: item => moveFavorite(item.path, 'down') },
  { id: 'favorite-remove', label: 'Remove from favorites', icon: 'fas fa-star-half-stroke', group: 'organize',
    supports: () => true, changesList: true, execute: item => removeFavorite(item.path),
    bulk: { confirmation: count => ({ title: 'Remove selected favorites',
      message: `Remove ${count} selected favorite(s)? The files and bookmarks themselves will not be deleted.`,
      confirmLabel: 'Remove favorites', danger: false }), execute: removeSelectedFavorites } },
];

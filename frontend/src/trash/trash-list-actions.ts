import type { ListItemAction } from '../shared/browser/list-item-actions';
import { deleteSelectedTrashItems, deleteTrashItem, restoreTrashItem } from './trash-api';
import type { TrashItem } from './types';

export const trashItemKey = (item: TrashItem) => item.id;
export const trashItemIdentity = (item: TrashItem) => JSON.stringify([item.id, item.originalPath, item.directory]);
export const trashListActions: readonly ListItemAction<TrashItem>[] = [
  { id: 'trash-restore', label: 'Restore', icon: 'fas fa-rotate-left', group: 'restore', supports: () => true,
    changesList: true, execute: item => restoreTrashItem(item.id) },
  { id: 'trash-delete', label: 'Permanently delete', icon: 'fas fa-trash-can', group: 'mutate', danger: true,
    supports: () => true, changesList: true,
    confirmation: item => ({ title: 'Permanently delete item', message: `Permanently delete ${item.originalName}? This cannot be undone.`,
      confirmLabel: 'Delete permanently', danger: true }), execute: item => deleteTrashItem(item.id),
    bulk: { confirmation: count => ({ title: 'Permanently delete selected items',
      message: `Permanently delete ${count} selected trash item(s)? This cannot be undone.`,
      confirmLabel: 'Delete permanently', danger: true }), execute: deleteSelectedTrashItems } },
];

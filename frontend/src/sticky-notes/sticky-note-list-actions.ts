import { openListTarget, type ListItemAction } from '../shared/browser/list-item-actions';
import { deleteSelectedStickyNotes, deleteStickyNote } from './sticky-note-catalog-api';
import type { StickyNoteCatalogItem } from './types';

export const stickyNoteItemKey = (item: StickyNoteCatalogItem) => item.id;
export const stickyNoteItemIdentity = (item: StickyNoteCatalogItem) =>
  JSON.stringify([item.id, item.targetType, item.contextLabel, item.openUrl, item.content]);
export const stickyNoteListActions: readonly ListItemAction<StickyNoteCatalogItem>[] = [
  { id: 'note-open', label: 'Open target', icon: 'fas fa-arrow-up-right-from-square', group: 'open',
    supports: item => Boolean(item.openUrl), href: item => item.openUrl!, execute: item => openListTarget(item.openUrl!) },
  { id: 'note-delete', label: 'Delete sticky note', icon: 'fas fa-trash-can', group: 'mutate', danger: true,
    supports: () => true, changesList: true,
    confirmation: { title: 'Delete sticky note', message: 'Delete this sticky note? This cannot be undone.',
      confirmLabel: 'Delete', danger: true }, execute: item => deleteStickyNote(item.id),
    bulk: { confirmation: count => ({ title: 'Delete selected sticky notes',
      message: `Delete ${count} selected sticky note(s)? This cannot be undone.`, confirmLabel: 'Delete', danger: true }),
      execute: deleteSelectedStickyNotes } },
];

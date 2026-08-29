import { notify, postForm, toastError } from '../shared/api/form-api';
import type { BookmarkEntry, BookmarkHistoryState, BookmarkPayload } from './types';

export function useBookmarkActions({
  selectedEntries,
  setSelected,
  setPayload,
  effectiveState,
  reload,
}: {
  selectedEntries: BookmarkEntry[];
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  setPayload: React.Dispatch<React.SetStateAction<BookmarkPayload | null>>;
  effectiveState: () => BookmarkHistoryState;
  reload: () => void;
}) {
  const createDirectory = async () => {
    const title = await window.EnderVault?.askTextInput({
      title: 'New directory',
      label: 'Directory name',
      placeholder: 'New directory',
      confirmLabel: 'Create',
    });
    if (!title?.trim()) return;
    try {
      const body = await postForm('/api/v1/bookmarks/directories', {
        parentId: effectiveState().directoryId,
        title: title.trim(),
      });
      notify(body);
      reload();
    } catch (reason) {
      toastError(reason, 'Bookmark directory creation failed.');
    }
  };

  const createLink = async (title: string, url: string) => {
    const body = await postForm('/api/v1/bookmarks/links', {
      parentId: effectiveState().directoryId,
      title,
      url,
    });
    notify(body);
    reload();
  };

  const bulkAdd = async (bulkText: string) => {
    const state = effectiveState();
    const body = await postForm('/api/v1/bookmarks/bulk', {
      parentId: state.directoryId,
      bulkText,
    });
    notify(body);
    if (body.task) {
      const params = new URLSearchParams();
      if (state.directoryId) params.set('directory', state.directoryId);
      if (state.query) params.set('q', state.query);
      const query = params.toString();
      window.EnderVaultServerTasks?.track(body.task, {
        refreshUrl: query ? `/files/bookmarks?${query}` : '/files/bookmarks',
      });
    }
  };

  const deleteEntries = async (entries = selectedEntries) => {
    if (entries.length === 0) return;
    const confirmed = await window.EnderVault?.askConfirmation({
      title: entries.length === 1 ? 'Delete bookmark' : 'Delete bookmarks',
      message: entries.length === 1
        ? `Delete "${entries[0].title}"?`
        : `Delete ${entries.length} selected bookmark items?`,
      confirmLabel: 'Delete',
      danger: true,
    });
    if (!confirmed) return;
    try {
      const state = effectiveState();
      const body = entries.length === 1
        ? await postForm('/api/v1/bookmarks/delete', {
          id: entries[0].id, parentId: state.directoryId, q: state.query,
        })
        : await postForm('/api/v1/bookmarks/delete-selected', {
          bookmarkIds: entries.map((entry) => entry.id),
          parentId: state.directoryId,
          q: state.query,
        });
      notify(body);
      setSelected(new Set());
      reload();
    } catch (reason) {
      toastError(reason, 'Bookmark deletion failed.');
    }
  };

  const toggleFavorite = async (entry: BookmarkEntry) => {
    try {
      const body = window.EnderVaultFavorites
        ? await window.EnderVaultFavorites.toggleBookmark(entry.id)
        : await postForm('/api/v1/favorites/toggle-bookmark', { id: entry.id });
      if (!window.EnderVaultFavorites) notify(body);
      const active = Boolean(body.active);
      setPayload((current) => {
        if (!current) return current;
        const update = (item: BookmarkEntry) => item.id === entry.id ? { ...item, favorite: active } : item;
        return {
          ...current,
          directories: current.directories.map(update),
          links: current.links.map(update),
        };
      });
    } catch (reason) {
      toastError(reason, 'Favorite could not be updated.');
    }
  };

  const refreshMetadata = async (entry: BookmarkEntry) => {
    try {
      const state = effectiveState();
      const body = await postForm('/api/v1/bookmarks/metadata', {
        id: entry.id,
        parentId: state.directoryId,
        q: state.query,
      });
      notify(body);
      reload();
    } catch (reason) {
      toastError(reason, 'Bookmark metadata refresh failed.');
    }
  };

  const copyUrl = async (entry: BookmarkEntry) => {
    const copied = Boolean(entry.url) && await window.EnderVault?.copyText(entry.url!);
    window.EnderVault?.showToast(
      copied ? 'success' : 'warning',
      copied ? 'Bookmark URL copied.' : 'Clipboard is not available.',
    );
  };

  return {
    createDirectory,
    createLink,
    bulkAdd,
    deleteEntries,
    toggleFavorite,
    refreshMetadata,
    copyUrl,
  };
}

export type BookmarkActions = ReturnType<typeof useBookmarkActions>;

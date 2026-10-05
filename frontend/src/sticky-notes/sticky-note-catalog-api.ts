import { notify } from '../shared/api/form-api';
import { resolveSelectedItems } from '../shared/api/selected-item-api';
import type { StickyNoteCatalogPayload, StickyNoteDeletePayload } from './types';

export async function loadStickyNoteCatalog(query: string, signal: AbortSignal): Promise<StickyNoteCatalogPayload> {
  const parameters = new URLSearchParams();
  if (query) parameters.set('q', query);
  const suffix = parameters.size ? `?${parameters}` : '';
  const response = await fetch(`/api/v1/sticky-notes/catalog${suffix}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as StickyNoteCatalogPayload & { message?: string; notification?: { message?: string } };
  if (!response.ok || !Array.isArray(body.notes)) {
    throw new Error(body.notification?.message || body.message || 'Sticky notes could not be loaded.');
  }
  return body;
}

export async function deleteStickyNote(id: string): Promise<StickyNoteDeletePayload> {
  const csrf = window.EnderVault?.csrfPair();
  const body = await window.EnderVault!.requestJson(`/api/v1/sticky-notes/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: csrf ? { 'X-CSRF-TOKEN': csrf.value } : {},
  }) as StickyNoteDeletePayload;
  notify(body);
  stickyNoteDeleted(body.deletedId);
  return body;
}

function stickyNoteDeleted(id: string) {
  document.dispatchEvent(new CustomEvent('endervault:sticky-note-deleted', { detail: { id } }));
}

export async function deleteSelectedStickyNotes(ids: readonly string[]) {
  const result = await resolveSelectedItems('/api/v1/sticky-notes/selected/resolve', ids, 'DELETE');
  result.results.filter(item => item.status === 'APPLIED').forEach(item => stickyNoteDeleted(item.id));
  return result;
}

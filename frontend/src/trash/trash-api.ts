import { notify, postForm } from '../shared/api/form-api';
import type { TrashPayload } from './types';

export const loadTrash = async (signal: AbortSignal) => {
  const response = await fetch('/api/v1/trash', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as TrashPayload & { message?: string };
  if (!response.ok) throw new Error(body.message || 'Trash items could not be loaded.');
  return body;
};

export const restoreTrashItem = async (id: string) => {
  const body = await postForm('/api/v1/trash/restore', { id });
  notify(body);
  return body;
};

export const deleteTrashItem = async (id: string) => {
  const body = await postForm('/api/v1/trash/delete', { id });
  notify(body);
  return body;
};

export const emptyTrash = async () => {
  const body = await postForm('/api/v1/trash/empty', {});
  notify(body);
  return body;
};

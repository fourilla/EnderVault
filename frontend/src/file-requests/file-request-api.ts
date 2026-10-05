import { notify, postEncodedForm, postForm } from '../shared/api/form-api';
import { validateListBulkResult } from '../shared/browser/list-item-actions';
import type {
  FileRequestCreateValues,
  FileRequestDetailPayload,
  FileRequestListPayload,
} from './types';

async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  return window.EnderVault!.requestJson(url, { signal });
}

export const loadFileRequests = (search: string, signal: AbortSignal) =>
  getJson<FileRequestListPayload>(`/api/v1/file-requests${search}`, signal);

export const loadFileRequest = (id: string, signal: AbortSignal) =>
  getJson<FileRequestDetailPayload>(`/api/v1/file-requests/${encodeURIComponent(id)}`, signal);

async function mutate(url: string, values: Record<string, string | number | undefined> = {}) {
  const body = await postForm(url, values);
  notify(body);
  return body;
}

export const createFileRequest = (values: FileRequestCreateValues) =>
  mutate('/api/v1/file-requests', { ...values });
export const revokeFileRequest = (id: string) =>
  mutate(`/api/v1/file-requests/${encodeURIComponent(id)}/revoke`);
export const deleteFileRequest = (id: string) =>
  mutate(`/api/v1/file-requests/${encodeURIComponent(id)}/delete`);
export const resolveFileRequestSelection = async (ids: readonly string[], action: 'REVOKE' | 'DELETE') => {
  const body = await postEncodedForm('/api/v1/file-requests/selected/resolve', { ids: [...ids], action, confirmed: true });
  const result = validateListBulkResult(body, ids);
  notify(body);
  return result;
};
export const cancelFileRequestUploads = (id: string) =>
  mutate(`/api/v1/file-requests/${encodeURIComponent(id)}/active-uploads/cancel`);
export const deleteExpiredFileRequests = () => mutate('/api/v1/file-requests/expired/delete');

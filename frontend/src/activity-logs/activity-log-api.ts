import { notify, postForm } from '../shared/api/form-api';
import type { ActivityLogPayload } from './types';

export const loadActivityLogs = async (search: string, signal: AbortSignal) => {
  const response = await fetch(`/api/v1/activity-logs${search}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as ActivityLogPayload & { message?: string };
  if (!response.ok) throw new Error(body.message || 'Activity logs could not be loaded.');
  return body;
};

export const deleteActivityLog = async (file: string) => {
  const body = await postForm('/api/v1/activity-logs/delete', { file });
  notify(body);
  return body;
};

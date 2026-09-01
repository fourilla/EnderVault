import { notify, postForm } from '../shared/api/form-api';
import type { ActiveSessionListPayload, SessionRevokePayload } from './types';

export async function loadActiveSessions(signal: AbortSignal): Promise<ActiveSessionListPayload> {
  const response = await fetch('/api/v1/sessions', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as ActiveSessionListPayload & { message?: string };
  if (!response.ok || !Array.isArray(body.sessions)) {
    throw new Error(body.message || 'Active sessions could not be loaded.');
  }
  return body;
}

export async function revokeActiveSession(managementId: string): Promise<SessionRevokePayload> {
  const body = await postForm('/api/v1/sessions/revoke', { managementId }) as SessionRevokePayload;
  if (!body.redirectUrl) notify(body);
  return body;
}

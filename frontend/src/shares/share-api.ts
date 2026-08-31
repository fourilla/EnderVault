import { notify, postForm } from '../shared/api/form-api';
import type { ShareLink } from './types';

export async function loadShares(signal: AbortSignal): Promise<ShareLink[]> {
  const response = await fetch('/api/v1/shares', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as ShareLink[] | { message?: string };
  if (!response.ok || !Array.isArray(body)) {
    throw new Error(!Array.isArray(body) && body.message ? body.message : 'Shared links could not be loaded.');
  }
  return body;
}

async function mutate(url: string, values: Record<string, string> = {}) {
  const body = await postForm(url, values);
  notify(body);
  return body;
}

export const revokeShare = (token: string) => mutate('/api/v1/shares/revoke', { token });
export const deleteShare = (token: string) => mutate('/api/v1/shares/delete', { token });
export const deleteExpiredShares = () => mutate('/api/v1/shares/expired/delete');

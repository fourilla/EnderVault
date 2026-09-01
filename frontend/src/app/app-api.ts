import type { AdminAppBootstrap } from './types';

export class AdminAppSessionExpiredError extends Error {
  constructor() {
    super('Your session has expired. Sign in again to continue.');
    this.name = 'AdminAppSessionExpiredError';
  }
}

export async function fetchAdminAppBootstrap(signal?: AbortSignal): Promise<AdminAppBootstrap> {
  const response = await fetch('/api/v1/app/bootstrap', {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
    signal,
  });

  const responseUrl = new URL(response.url, window.location.origin);
  if (response.redirected && responseUrl.pathname === '/login') {
    throw new AdminAppSessionExpiredError();
  }
  if (!response.ok) {
    throw new Error(`Unable to initialize EnderVault (${response.status}).`);
  }
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new Error('Unable to initialize EnderVault: the server returned an unexpected response.');
  }
  return response.json() as Promise<AdminAppBootstrap>;
}

import type { SharedBootstrap, SharedView } from './types';

export class PublicShareError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'PublicShareError';
    this.status = status;
  }
}

export function sharedViewUrl(bootstrap: SharedBootstrap, pathname: string, search: string) {
  const detail = bootstrap.targetType === 'FILE' || pathname.endsWith('/file');
  const params = new URLSearchParams(search);
  const query = new URLSearchParams();
  if (bootstrap.targetType === 'DIRECTORY') {
    if (params.has('path')) query.set('path', params.get('path')!);
    if (detail && params.has('item')) query.set('item', params.get('item')!);
  }
  return `${bootstrap.rootUrl}/${detail ? 'detail' : 'listing'}.json${query.size ? `?${query}` : ''}`;
}

const unavailable = (status: number) => status === 400
  ? 'The shared request is invalid.'
  : status === 403 || status === 404
    ? 'This shared item is unavailable.'
    : 'The shared item could not be loaded. Try again.';

export async function requestSharedView(url: string, signal: AbortSignal,
  request: typeof fetch = fetch): Promise<SharedView> {
  const response = await request(url, {
    method: 'GET', credentials: 'omit', mode: 'same-origin', cache: 'no-store',
    headers: { Accept: 'application/json' }, signal,
  });
  if (!response.ok) throw new PublicShareError(unavailable(response.status), response.status);
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new PublicShareError(unavailable(0), 0);
  }
  const payload: unknown = await response.json();
  if (!payload || typeof payload !== 'object') throw new PublicShareError(unavailable(0), 0);
  const view = payload as Partial<SharedView>;
  if ((view.targetType !== 'DIRECTORY' && view.targetType !== 'FILE')
    || typeof view.path !== 'string' || typeof view.rootUrl !== 'string'
    || (view.view === 'listing' ? !('entries' in view) || !Array.isArray(view.entries)
      : view.view !== 'detail' || !('name' in view) || typeof view.name !== 'string')) {
    throw new PublicShareError(unavailable(0), 0);
  }
  return view as SharedView;
}

import type { FileDetailPayload } from './types';

export function detailQueryKey(query: URLSearchParams): string {
  return JSON.stringify([query.get('path') || '', query.get('comicPage') || '']);
}

export async function loadFileDetail(query: URLSearchParams, signal?: AbortSignal): Promise<FileDetailPayload> {
  const params = new URLSearchParams({ path: query.get('path') || '' });
  if (query.get('comicPage')) params.set('comicPage', query.get('comicPage')!);
  return window.EnderVault!.requestJson('/api/v1/fs/detail?' + params, { signal });
}

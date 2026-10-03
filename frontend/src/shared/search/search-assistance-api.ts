import type { PathEntry, SearchSchema } from './types';

type RequestJson = (url: string, options: RequestInit) => Promise<any>;
interface CacheEntry<T> { value: T; expires: number }

export function createSearchAssistanceClient(request: RequestJson = (url, options) => {
  if (!window.EnderVault) throw new Error('Search assistance is unavailable.');
  return window.EnderVault.requestJson(url, options);
}, now = Date.now) {
  const schemas = new Map<string, CacheEntry<SearchSchema>>();
  const paths = new Map<string, CacheEntry<PathEntry[]>>();
  const read = <T>(cache: Map<string, CacheEntry<T>>, key: string) => {
    const item = cache.get(key);
    if (!item || item.expires <= now()) { cache.delete(key); return undefined; }
    cache.delete(key);
    cache.set(key, item);
    return item.value;
  };
  const put = <T>(cache: Map<string, CacheEntry<T>>, key: string, value: T, ttl: number) => {
    cache.set(key, { value, expires: now() + ttl });
    while (cache.size > 8) cache.delete(cache.keys().next().value!);
  };
  const assertActive = (signal: AbortSignal) => {
    if (signal.aborted) throw new DOMException('Search assistance canceled.', 'AbortError');
  };
  return {
    async schema(scope: string, signal: AbortSignal): Promise<SearchSchema> {
      assertActive(signal);
      const cached = read(schemas, scope);
      if (cached) return cached;
      const payload = await request(`/api/v1/search/schemas/${encodeURIComponent(scope)}`, { signal });
      assertActive(signal);
      if (payload.scope !== scope || !Array.isArray(payload.fields) || !payload.limits) throw new Error('Invalid search schema.');
      put(schemas, scope, payload, 300_000);
      return payload;
    },
    async directories(parent: string, hidden: string, signal: AbortSignal): Promise<PathEntry[]> {
      assertActive(signal);
      const key = JSON.stringify([parent, hidden]);
      const cached = read(paths, key);
      if (cached) return cached;
      const query = new URLSearchParams({ path: parent, types: 'directory', hidden });
      const payload = await request(`/api/v1/fs/entries?${query}`, { signal });
      assertActive(signal);
      if (!Array.isArray(payload.entries)) throw new Error('Invalid directory suggestions.');
      const entries: PathEntry[] = payload.entries.map(({ name, path, type }: PathEntry) => ({ name, path, type }));
      // Do not retain huge parent listings or unbounded metadata in the shell.
      if (entries.length <= 2000) {
        put(paths, key, entries, 30_000);
        while ([...paths.values()].reduce((count, item) => count + item.value.length, 0) > 2000) {
          paths.delete(paths.keys().next().value!);
        }
      }
      return entries;
    },
  };
}

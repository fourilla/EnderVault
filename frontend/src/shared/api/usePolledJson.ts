import { useCallback, useEffect, useRef, useState } from 'react';

const noEvents: readonly string[] = [];

export function usePolledJson<T>(url: string, intervalMs: number, initialData?: T,
  events: readonly string[] = noEvents) {
  const [data, setData] = useState<T | undefined>(initialData);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const revision = useRef(0);
  const run = useRef<() => Promise<void>>(async () => {});
  const refresh = useCallback(() => run.current(), []);
  const accept = useCallback((next: T) => {
    revision.current++;
    setData(next);
    setError('');
  }, []);

  useEffect(() => {
    let disposed = false;
    let busy = false;
    let again = false;
    let timer: number | undefined;
    let controller: AbortController | undefined;
    const read = async () => {
      if (disposed || document.visibilityState !== 'visible') return;
      if (busy) { again = true; return; }
      busy = true;
      window.clearTimeout(timer);
      setLoading(true);
      do {
        again = false;
        controller = new AbortController();
        const requestRevision = revision.current;
        try {
          const next = await window.EnderVault!.requestJson(url, { signal: controller.signal });
          if (!disposed && !controller.signal.aborted && requestRevision === revision.current) {
            setData(next as T); setError('');
          }
        } catch (reason) {
          if (!disposed && !controller.signal.aborted && requestRevision === revision.current) {
            setError(reason instanceof Error ? reason.message : 'Status could not be refreshed.');
          }
        }
      } while (again && !disposed && document.visibilityState === 'visible');
      busy = false;
      if (disposed) return;
      setLoading(false);
      if (document.visibilityState === 'visible') {
        timer = window.setTimeout(() => void read(), intervalMs);
      }
    };
    const onRefresh = () => void read();
    const onVisibility = () => {
      if (document.visibilityState === 'visible') void read();
      else { window.clearTimeout(timer); controller?.abort(); }
    };
    run.current = read;
    void read();
    window.addEventListener('focus', onRefresh);
    document.addEventListener('visibilitychange', onVisibility);
    events.forEach((event) => window.addEventListener(event, onRefresh));
    return () => {
      disposed = true;
      controller?.abort();
      window.clearTimeout(timer);
      run.current = async () => {};
      window.removeEventListener('focus', onRefresh);
      document.removeEventListener('visibilitychange', onVisibility);
      events.forEach((event) => window.removeEventListener(event, onRefresh));
    };
  }, [url, intervalMs, events]);

  return { data, error, loading, refresh, accept };
}

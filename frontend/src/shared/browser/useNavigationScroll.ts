import { useLayoutEffect, useRef } from 'react';
import { createScrollRestoration } from './scroll-restoration';

export function useNavigationScroll(snapshot: unknown, loading: boolean, initialTop: number,
  visitKey?: string, onReady?: () => void) {
  const restoration = useRef(createScrollRestoration(initialTop));
  const visit = useRef(visitKey);
  useLayoutEffect(() => {
    if (visit.current !== visitKey) {
      visit.current = visitKey;
      restoration.current.request(initialTop);
    }
    if (snapshot == null || loading) return;
    restoration.current.restore((top) => window.scrollTo({ top, behavior: 'instant' }));
    onReady?.();
  }, [snapshot, loading, initialTop, visitKey, onReady]);
}

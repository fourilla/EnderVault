import { useCallback, useLayoutEffect, useRef } from 'react';
import { createScrollRestoration } from './scroll-restoration';

export function useNavigationScroll(snapshot: unknown, loading: boolean, initialTop: number) {
  const restoration = useRef(createScrollRestoration(initialTop));
  useLayoutEffect(() => {
    if (snapshot == null || loading) return;
    restoration.current.restore((top) => window.scrollTo({ top, behavior: 'instant' }));
  }, [snapshot, loading]);
  return useCallback((top: number) => restoration.current.request(top), []);
}

import { useLayoutEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { createHashRestoration } from './scroll-restoration';

export function useHashTarget(snapshot: unknown, prefix: string, focus = false) {
  const location = useLocation();
  const restoration = useRef(createHashRestoration());
  useLayoutEffect(() => {
    const navigation = JSON.stringify([location.key, location.pathname, location.search, location.hash]);
    restoration.current(navigation, () => {
      if (!location.hash.startsWith(prefix)) return true;
      if (snapshot == null) return false;
      const target = document.getElementById(location.hash.slice(1));
      if (!target) return false;
      target.scrollIntoView({ block: 'center', behavior: 'instant' });
      if (focus) target.focus({ preventScroll: true });
      return true;
    });
  }, [snapshot, prefix, focus, location.key, location.pathname, location.search, location.hash]);
}

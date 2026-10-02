import { useCallback, useEffect, useState } from 'react';

const mobileQuery = '(max-width: 720px)';

export function useSidebarLayout(routeKey: string) {
  const [mobile, setMobile] = useState(() => window.matchMedia(mobileQuery).matches);
  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem('endervault.sidebar.collapsed') === 'true'; }
    catch { return false; }
  });
  const [drawerOpen, setDrawerOpen] = useState(false);
  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  useEffect(() => {
    const query = window.matchMedia(mobileQuery);
    const update = () => { setMobile(query.matches); setDrawerOpen(false); };
    update();
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  useEffect(closeDrawer, [routeKey, closeDrawer]);
  useEffect(() => {
    try { localStorage.setItem('endervault.sidebar.collapsed', String(collapsed)); }
    catch { /* Navigation remains usable when browser storage is unavailable. */ }
  }, [collapsed]);

  return { mobile, collapsed, drawerOpen, closeDrawer,
    toggle: () => mobile ? setDrawerOpen((open) => !open) : setCollapsed((value) => !value) };
}

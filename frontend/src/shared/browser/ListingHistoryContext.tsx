import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState,
  type Dispatch, type SetStateAction, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { ListingHistory, ListingHistoryConfig, ListingState } from './listing-history';

const Context = createContext<ListingHistory | null>(null);

export function ListingHistoryProvider({ history, children }: { history: ListingHistory; children: ReactNode }) {
  useEffect(() => {
    window.addEventListener('pagehide', history.capture);
    return () => window.removeEventListener('pagehide', history.capture);
  }, [history]);
  return <Context.Provider value={history}>{children}</Context.Provider>;
}

export function useListingHistory<T extends ListingState>(config: ListingHistoryConfig<T>) {
  const history = useContext(Context);
  if (!history) throw new Error('ListingHistoryProvider is required.');
  const location = useLocation();
  const routeNavigate = useNavigate();
  const state = useMemo(() => history.read(config, location), [history, config, location]);
  useLayoutEffect(() => {
    if (location.pathname !== config.pathname) return;
    if (location.search) {
      void routeNavigate({ pathname: config.pathname, hash: location.hash },
        { replace: true, state: { listing: state }, preventScrollReset: true });
      return;
    }
    history.activate(location, state);
    return () => history.deactivate(location);
  }, [history, config.pathname, location, state, routeNavigate]);
  const remember = useCallback((next: T) => history.remember(location, next), [history, location]);
  const isCurrent = useCallback(() => location.pathname === config.pathname && history.isCurrent(location),
    [history, config.pathname, location]);
  const ready = useCallback(() => history.ready(location), [history, location]);
  const navigate = useCallback((next: T, replace = false) => {
    if (!isCurrent()) return;
    void routeNavigate(config.pathname, { replace, state: { listing: next }, preventScrollReset: true });
  }, [isCurrent, routeNavigate, config.pathname]);
  return { state, key: location.key, remember, navigate, isCurrent, ready };
}

// Never render the previous visit's rows while the next visit is loading.
export function useListingSnapshot<T>(key: string): [T | null, Dispatch<SetStateAction<T | null>>] {
  const [snapshot, setSnapshot] = useState<{ key: string; value: T | null } | null>(null);
  const currentKey = useRef(key);
  currentKey.current = key;
  const setValue = useCallback<Dispatch<SetStateAction<T | null>>>((update) => {
    setSnapshot((previous) => currentKey.current !== key ? previous : ({ key, value: typeof update === 'function'
      ? (update as (current: T | null) => T | null)(previous?.key === key ? previous.value : null)
      : update }));
  }, [key]);
  return [snapshot?.key === key ? snapshot.value : null, setValue];
}

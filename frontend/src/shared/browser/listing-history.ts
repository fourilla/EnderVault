import type { Location, NavigateOptions, To } from 'react-router-dom';

export interface ListingState {
  surface: string;
  version: 1;
  scrollTop: number;
}

export interface ListingHistoryConfig<T extends ListingState> {
  pathname: string;
  parse: (candidate: unknown) => T | null;
  fromSearch: (search: string) => T;
}

interface HistoryRouter {
  state: { location: Location };
  subscribe: (listener: (state: { location: Location }) => void) => () => void;
  navigate: (to: To, options: Pick<NavigateOptions, 'replace' | 'state' | 'preventScrollReset'>) => void | Promise<void>;
}

const STORAGE_KEY = 'endervault.listing-entries.v1';
const MAX_ENTRIES = 100;
const identity = (location: Location) => JSON.stringify([
  location.key, location.pathname, location.search, location.hash,
]);

// These are compact per-visit UI snapshots, never file listings or a second URL history.
export function createListingHistory(
  router: HistoryRouter,
  scrollY: () => number,
  storage: () => Pick<Storage, 'getItem' | 'setItem'>,
) {
  // "default" is shared by unrelated document entries; give the first visit a real Router key.
  if (router.state.location.key === 'default') {
    const { pathname, search, hash, state } = router.state.location;
    void router.navigate({ pathname, search, hash }, { replace: true, state, preventScrollReset: true });
  }
  const snapshots = new Map<string, unknown>();
  try {
    const saved: unknown = JSON.parse(storage().getItem(STORAGE_KEY) || 'null');
    if (Array.isArray(saved)) {
      for (const pair of saved.slice(-MAX_ENTRIES)) {
        if (Array.isArray(pair) && typeof pair[0] === 'string') snapshots.set(pair[0], pair[1]);
      }
    }
  } catch { /* In-memory navigation still works when storage is unavailable. */ }

  let currentId = identity(router.state.location);
  let active: { id: string; state: ListingState; ready: boolean } | null = null;
  const save = (id: string, state: ListingState) => {
    snapshots.delete(id);
    snapshots.set(id, state);
    while (snapshots.size > MAX_ENTRIES) snapshots.delete(snapshots.keys().next().value!);
    try { storage().setItem(STORAGE_KEY, JSON.stringify([...snapshots])); } catch { /* Optional persistence. */ }
  };
  const capture = () => {
    if (!active) return;
    // A pending load has no full-height DOM yet; keep its requested restoration position.
    const state = active.ready
      ? { ...active.state, scrollTop: Math.max(0, Math.round(scrollY())) }
      : active.state;
    save(active.id, state);
  };
  const unsubscribe = router.subscribe(({ location }) => {
    const nextId = identity(location);
    if (nextId === currentId) return;
    // Router notifies before React replaces the outgoing DOM or resets its scroll.
    capture();
    active = null;
    currentId = nextId;
  });
  const isCurrent = (location: Location) => identity(location) === identity(router.state.location);

  return {
    read<T extends ListingState>(config: ListingHistoryConfig<T>, location: Location): T {
      if (location.pathname !== config.pathname) return config.fromSearch('');
      const routeState = location.state as { listing?: unknown } | null;
      return config.parse(snapshots.get(identity(location)))
        || config.parse(routeState?.listing)
        || config.fromSearch(location.search);
    },
    activate(location: Location, state: ListingState) {
      if (!isCurrent(location)) return;
      active = { id: identity(location), state, ready: false };
      save(active.id, state);
    },
    deactivate(location: Location) {
      if (active?.id === identity(location)) active = null;
    },
    remember(location: Location, state: ListingState) {
      if (!isCurrent(location) || active?.id !== identity(location)) return;
      active.state = state;
      save(active.id, state);
    },
    ready(location: Location) {
      if (isCurrent(location) && active?.id === identity(location)) active.ready = true;
    },
    isCurrent,
    capture,
    dispose: unsubscribe,
  };
}

export type ListingHistory = ReturnType<typeof createListingHistory>;

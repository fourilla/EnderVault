import {
  useCallback,
  createContext,
  type PropsWithChildren,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { fetchAdminAppBootstrap } from './app-api';
import type { AdminAppBootstrap } from './types';

interface AdminAppContextValue {
  bootstrap: AdminAppBootstrap;
  refreshBootstrap: () => Promise<void>;
}

const AdminAppContext = createContext<AdminAppContextValue | null>(null);

export function AdminAppProvider({ children }: PropsWithChildren) {
  const [bootstrap, setBootstrap] = useState<AdminAppBootstrap | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const next = await fetchAdminAppBootstrap(signal);
      setBootstrap(next);
      setError(null);
    } catch (reason) {
      if (reason instanceof DOMException && reason.name === 'AbortError') return;
      setError(reason instanceof Error ? reason.message : 'Unable to initialize EnderVault.');
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    const refreshFavorites = () => void load();
    document.addEventListener('endervault:favorites-changed', refreshFavorites);
    return () => document.removeEventListener('endervault:favorites-changed', refreshFavorites);
  }, [load]);

  const value = useMemo<AdminAppContextValue | null>(() => bootstrap ? ({
    bootstrap,
    refreshBootstrap: () => load(),
  }) : null, [bootstrap, load]);

  if (error) {
    return (
      <main className="browser-load-state browser-load-error" role="alert">
        <strong>EnderVault could not be initialized.</strong>
        <span>{error}</span>
        <button className="ghost" type="button" onClick={() => void load()}>Retry</button>
      </main>
    );
  }
  if (!value) {
    return (
      <main className="browser-load-state" aria-live="polite">
        <i className="fas fa-spinner fa-spin" aria-hidden="true" />
        <span>Loading EnderVault...</span>
      </main>
    );
  }
  return <AdminAppContext.Provider value={value}>{children}</AdminAppContext.Provider>;
}

export function useAdminApp(): AdminAppContextValue {
  const value = useContext(AdminAppContext);
  if (!value) throw new Error('useAdminApp must be used inside AdminAppProvider.');
  return value;
}

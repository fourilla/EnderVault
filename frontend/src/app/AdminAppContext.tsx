import { LoadingState } from '../shared/layout/LoadingState';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import {
  useCallback,
  createContext,
  type PropsWithChildren,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useRef,
} from 'react';
import { AdminAppAccessDeniedError, AdminAppSessionExpiredError, fetchAdminAppBootstrap } from './app-api';
import { bootstrapReducer, initialBootstrapState } from './bootstrap-state';
import type { AdminAppBootstrap } from './types';
import { applyAppearance } from '../shared/appearance/presets';

interface AdminAppContextValue {
  bootstrap: AdminAppBootstrap;
  refreshBootstrap: () => Promise<void>;
}

const AdminAppContext = createContext<AdminAppContextValue | null>(null);

export function AdminAppProvider({ children }: PropsWithChildren) {
  const [{ bootstrap, failure, loading }, dispatch] = useReducer(bootstrapReducer, initialBootstrapState);
  const requestId = useRef(0);
  const activeRequest = useRef<AbortController | null>(null);
  const warnedAboutRefresh = useRef(false);

  const load = useCallback(async () => {
    const id = ++requestId.current;
    activeRequest.current?.abort();
    const controller = new AbortController();
    activeRequest.current = controller;
    dispatch({ type: 'start', requestId: id });
    try {
      const next = await fetchAdminAppBootstrap(controller.signal);
      if (!controller.signal.aborted) {
        applyAppearance(next.appearance);
        dispatch({ type: 'success', requestId: id, bootstrap: next });
      }
    } catch (reason) {
      if (controller.signal.aborted || (reason instanceof DOMException && reason.name === 'AbortError')) return;
      dispatch({ type: 'failure', requestId: id, failure: {
        kind: reason instanceof AdminAppSessionExpiredError ? 'session-expired'
          : reason instanceof AdminAppAccessDeniedError ? 'forbidden' : 'unavailable',
        message: reason instanceof Error ? reason.message : 'Unable to initialize EnderVault.',
      } });
    } finally {
      if (activeRequest.current === controller) activeRequest.current = null;
    }
  }, []);

  useEffect(() => {
    void load();
    return () => activeRequest.current?.abort();
  }, [load]);

  useEffect(() => {
    if (!failure) {
      warnedAboutRefresh.current = false;
      return;
    }
    if (!bootstrap || failure.kind !== 'unavailable') return;
    if (!warnedAboutRefresh.current) {
      window.EnderVault?.showToast('warning', 'App status could not be refreshed. Showing last known values.');
      warnedAboutRefresh.current = true;
    }
    const retry = () => void load();
    window.addEventListener('online', retry);
    window.addEventListener('focus', retry);
    return () => {
      window.removeEventListener('online', retry);
      window.removeEventListener('focus', retry);
    };
  }, [bootstrap, failure, load]);

  useEffect(() => {
    const refreshFavorites = () => void load();
    document.addEventListener('endervault:favorites-changed', refreshFavorites);
    return () => document.removeEventListener('endervault:favorites-changed', refreshFavorites);
  }, [load]);

  const value = useMemo<AdminAppContextValue | null>(() => bootstrap ? ({
    bootstrap,
    refreshBootstrap: load,
  }) : null, [bootstrap, load]);

  if (!value && failure) {
    return (
      <main className="page-error-standalone"><PageErrorPanel title={failure.kind === 'session-expired' ? 'Session expired'
          : failure.kind === 'forbidden' ? 'Access denied' : 'EnderVault could not be initialized.'}
        message={failure.message} actions={<>
          <button className="icon-text-button" type="button" disabled={loading} onClick={() => void load()}>
            <i className="fas fa-arrows-rotate" aria-hidden="true" /><span>Retry</span>
          </button>
          {failure.kind !== 'unavailable' && <a className="button-link" href="/login">Sign in</a>}
        </>} /></main>
    );
  }
  if (!value) {
    return (
      <LoadingState label="Loading EnderVault..." />
    );
  }
  return <AdminAppContext.Provider value={value}>{children}</AdminAppContext.Provider>;
}

export function useAdminApp(): AdminAppContextValue {
  const value = useContext(AdminAppContext);
  if (!value) throw new Error('useAdminApp must be used inside AdminAppProvider.');
  return value;
}

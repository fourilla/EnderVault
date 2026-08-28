import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import { canonicalState, loadBrowserPayload } from './browser-api';
import {
  defaultBrowserState,
  initialBrowserState,
  parseBrowserState,
  rememberBrowserState,
} from './browser-history';
import type { BrowserHistoryState, BrowserPayload } from './types';

export function useBrowserNavigation() {
  const [state, setState] = useState<BrowserHistoryState>(() => initialBrowserState());
  const [payload, setPayload] = useState<BrowserPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [searchText, setSearchText] = useState(state.query);
  const [refreshToken, setRefreshToken] = useState(0);
  const stateRef = useRef(state);
  const payloadRef = useRef(payload);
  const restoreScrollRef = useRef(state.scrollTop);

  stateRef.current = state;
  payloadRef.current = payload;

  const effectiveState = useCallback(() => {
    const current = stateRef.current;
    const currentPayload = payloadRef.current;
    return currentPayload ? canonicalState(current, currentPayload) : current;
  }, []);

  const persistCurrentScroll = useCallback(() => {
    const current = { ...effectiveState(), scrollTop: Math.max(0, Math.round(window.scrollY)) };
    rememberBrowserState(current, true);
    stateRef.current = current;
    return current;
  }, [effectiveState]);

  const navigate = useCallback((next: BrowserHistoryState, replace = false) => {
    persistCurrentScroll();
    const normalized = { ...next, version: 1 as const, scrollTop: next.scrollTop || 0 };
    restoreScrollRef.current = normalized.scrollTop;
    rememberBrowserState(normalized, replace);
    setState(normalized);
  }, [persistCurrentScroll]);

  const browse = useCallback((path: string) => {
    const current = effectiveState();
    setSearchText('');
    navigate({
      ...current,
      mode: 'browse',
      path,
      query: '',
      page: 1,
      scrollTop: 0,
    });
  }, [effectiveState, navigate]);

  useEffect(() => {
    const refreshListing = async (url?: string) => {
      if (url) {
        const target = new URL(url, window.location.href);
        const targetPath = target.searchParams.get('path');
        if (targetPath != null && targetPath !== effectiveState().path) {
          navigate({
            ...effectiveState(),
            mode: 'browse',
            path: targetPath,
            query: '',
            page: 1,
            scrollTop: 0,
          });
          return;
        }
      }
      setRefreshToken((current) => current + 1);
    };
    window.EnderVaultFileBrowser = {
      refreshListing,
      requestListingRefresh: (url?: string) => void refreshListing(url),
      syncToolbarState: () => undefined,
    };
    document.dispatchEvent(new CustomEvent('endervault:files-ready'));
    return () => {
      delete window.EnderVaultFileBrowser;
    };
  }, [effectiveState, navigate]);

  useEffect(() => {
    rememberBrowserState(state, true);
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadBrowserPayload(state, controller.signal)
      .then((nextPayload) => {
        setPayload(nextPayload);
        const resolvedState = canonicalState(state, nextPayload);
        rememberBrowserState(resolvedState, true);
        stateRef.current = resolvedState;
        const stickyContext = {
          targetType: 'STORAGE',
          targetKey: nextPayload.path,
          surface: nextPayload.mode === 'search' ? 'SEARCH' : 'BROWSER',
          label: nextPayload.path || 'Files /',
        };
        void window.EnderVaultStickyNotes?.setContext(stickyContext);
        document.dispatchEvent(new CustomEvent('endervault:sticky-context-changed', {
          detail: stickyContext,
        }));
        const readOnlyLink = document.querySelector<HTMLAnchorElement>('[data-read-only-link]');
        if (readOnlyLink) {
          const query = nextPayload.path
            ? '?' + new URLSearchParams({ path: nextPayload.path }).toString()
            : '';
          readOnlyLink.href = '/files/read-only' + query;
        }
        window.requestAnimationFrame(() => {
          window.scrollTo({ top: restoreScrollRef.current, behavior: 'auto' });
        });
      })
      .catch((reason: unknown) => {
        if (controller.signal.aborted) return;
        setError(reason instanceof Error ? reason.message : 'The file list could not be loaded.');
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [state, refreshToken]);

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      const restored = parseBrowserState(event.state) || defaultBrowserState();
      restoreScrollRef.current = restored.scrollTop;
      setSearchText(restored.query);
      setState(restored);
    };
    const onPageHide = () => persistCurrentScroll();
    window.addEventListener('popstate', onPopState);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      window.removeEventListener('popstate', onPopState);
      window.removeEventListener('pagehide', onPageHide);
    };
  }, [persistCurrentScroll]);

  const applyPreferences = (updates: Partial<BrowserHistoryState>) => {
    navigate({ ...effectiveState(), ...updates, page: 1, scrollTop: 0 });
  };

  const submitSearch = (event: FormEvent) => {
    event.preventDefault();
    const query = searchText.trim();
    if (!query) {
      browse(effectiveState().path);
      return;
    }
    navigate({
      ...effectiveState(),
      mode: 'search',
      query,
      page: 1,
      scrollTop: 0,
    });
  };

  return {
    state,
    stateRef,
    payload,
    payloadRef,
    setPayload,
    loading,
    error,
    searchText,
    setSearchText,
    effectiveState,
    navigate,
    browse,
    applyPreferences,
    submitSearch,
    reload: () => setRefreshToken((current) => current + 1),
  };
}

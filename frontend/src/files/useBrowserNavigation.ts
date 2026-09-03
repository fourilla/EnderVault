import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import { canonicalState, loadBrowserPayload } from './browser-api';
import {
  defaultBrowserState,
  initialBrowserState,
  parseBrowserState,
  rememberBrowserState,
} from './browser-history';
import { postForm, toastError } from '../shared/api/form-api';
import type { BrowserHistoryState, BrowserPayload, BrowserView } from './types';
import { useNavigationScroll } from '../shared/browser/useNavigationScroll';
import { useListingRefresh } from '../shared/browser/useListingRefresh';

const listingRequestKeyFor = (state: BrowserHistoryState) => [
  state.mode,
  state.path,
  state.query,
  state.page,
  state.sort || '',
  state.direction || '',
  state.hidden || '',
  state.pageSize || '',
].join('\u0000');

export function useBrowserNavigation() {
  const [state, setState] = useState<BrowserHistoryState>(() => initialBrowserState());
  const [payload, setPayload] = useState<BrowserPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [searchText, setSearchText] = useState(state.query);
  const [refreshToken, setRefreshToken] = useState(0);
  const stateRef = useRef(state);
  const payloadRef = useRef(payload);
  const requestScroll = useNavigationScroll(payload, loading, state.scrollTop);
  const requestGenerationRef = useRef(0);

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
    const sameRequest = listingRequestKeyFor(stateRef.current) === listingRequestKeyFor(normalized);
    requestGenerationRef.current += 1;
    payloadRef.current = null;
    setPayload(null);
    setLoading(true);
    requestScroll(normalized.scrollTop);
    rememberBrowserState(normalized, replace || sameRequest);
    setState(normalized);
    if (sameRequest) {
      setRefreshToken((current) => current + 1);
    }
  }, [persistCurrentScroll, requestScroll]);

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

  const reload = useCallback(() => setRefreshToken((current) => current + 1), []);
  useListingRefresh(reload);

  const listingRequestKey = listingRequestKeyFor(state);

  useEffect(() => {
    const requestGeneration = ++requestGenerationRef.current;
    rememberBrowserState(state, true);
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadBrowserPayload(state, controller.signal)
      .then((nextPayload) => {
        if (controller.signal.aborted || requestGeneration !== requestGenerationRef.current) return;
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
      })
      .catch((reason: unknown) => {
        if (controller.signal.aborted || requestGeneration !== requestGenerationRef.current) return;
        setError(reason instanceof Error ? reason.message : 'The file list could not be loaded.');
      })
      .finally(() => {
        if (!controller.signal.aborted && requestGeneration === requestGenerationRef.current) {
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [listingRequestKey, refreshToken]);

  useEffect(() => {
    const onPopState = (event: PopStateEvent) => {
      const restored = parseBrowserState(event.state) || defaultBrowserState();
      requestGenerationRef.current += 1;
      payloadRef.current = null;
      setPayload(null);
      setLoading(true);
      requestScroll(restored.scrollTop);
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
  }, [persistCurrentScroll, requestScroll]);

  const applyPreferences = (updates: Partial<BrowserHistoryState>) => {
    navigate({ ...effectiveState(), ...updates, page: 1, scrollTop: 0 });
  };

  const applyView = async (view: BrowserView) => {
    const previousView = payloadRef.current?.preferences.view
      || stateRef.current.view
      || 'table';
    if (previousView === view) return;

    const updateLocalView = (nextView: BrowserView) => {
      const nextState = { ...stateRef.current, view: nextView };
      stateRef.current = nextState;
      setState(nextState);
      rememberBrowserState(nextState, true);
      setPayload((current) => {
        if (!current) return current;
        const updated = {
          ...current,
          preferences: { ...current.preferences, view: nextView },
        };
        payloadRef.current = updated;
        return updated;
      });
    };

    updateLocalView(view);
    try {
      const body = await postForm('/api/v1/browser-preferences/files/view', { view });
      const savedView = body?.view === 'grid' ? 'grid' : 'table';
      if (stateRef.current.view === view && savedView !== view) {
        updateLocalView(savedView);
      }
    } catch (reason) {
      if (stateRef.current.view === view) updateLocalView(previousView);
      toastError(reason, 'View preference could not be saved.');
    }
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
    applyView,
    applyPreferences,
    submitSearch,
    reload,
  };
}

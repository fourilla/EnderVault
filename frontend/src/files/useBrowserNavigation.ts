import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import { canonicalState, loadBrowserPayload } from './browser-api';
import { browserHistory } from './browser-history';
import { useListingHistory, useListingSnapshot } from '../shared/browser/ListingHistoryContext';
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
  const { state, key: historyKey, remember, navigate: navigateHistory, isCurrent, ready } = useListingHistory(browserHistory);
  const [payload, setPayload] = useListingSnapshot<BrowserPayload>(historyKey);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [searchText, setSearchText] = useState(state.query);
  const [refreshToken, setRefreshToken] = useState(0);
  const stateRef = useRef(state);
  const payloadRef = useRef(payload);
  useNavigationScroll(payload, loading, state.scrollTop, historyKey, ready);
  const requestGenerationRef = useRef(0);

  stateRef.current = state;
  payloadRef.current = payload;

  const effectiveState = useCallback(() => {
    const current = stateRef.current;
    const currentPayload = payloadRef.current;
    return currentPayload ? canonicalState(current, currentPayload) : current;
  }, []);

  const navigate = useCallback((next: BrowserHistoryState, replace = false) => {
    if (!isCurrent()) return;
    const normalized = { ...next, version: 1 as const, scrollTop: next.scrollTop || 0 };
    const sameRequest = listingRequestKeyFor(effectiveState()) === listingRequestKeyFor(normalized);
    remember(effectiveState());
    navigateHistory(normalized, replace || sameRequest);
    requestGenerationRef.current += 1;
    payloadRef.current = null;
    setPayload(null);
    setLoading(true);
  }, [effectiveState, remember, navigateHistory, isCurrent]);

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
    if (!isCurrent()) return;
    const requestGeneration = ++requestGenerationRef.current;
    const requestState = effectiveState();
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadBrowserPayload(requestState, controller.signal)
      .then((nextPayload) => {
        if (!isCurrent() || controller.signal.aborted || requestGeneration !== requestGenerationRef.current) return;
        setPayload(nextPayload);
        const resolvedState = canonicalState(requestState, nextPayload);
        remember(resolvedState);
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
        if (!isCurrent() || controller.signal.aborted || requestGeneration !== requestGenerationRef.current) return;
        setError(reason instanceof Error ? reason.message : 'The file list could not be loaded.');
      })
      .finally(() => {
        if (isCurrent() && !controller.signal.aborted && requestGeneration === requestGenerationRef.current) {
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [historyKey, listingRequestKey, refreshToken, remember, isCurrent, effectiveState]);

  useEffect(() => {
    setSearchText(state.query);
  }, [historyKey, state.query]);

  const applyPreferences = (updates: Partial<BrowserHistoryState>) => {
    navigate({ ...effectiveState(), ...updates, page: 1, scrollTop: 0 });
  };

  const applyView = async (view: BrowserView) => {
    const previousView = payloadRef.current?.preferences.view
      || stateRef.current.view
      || 'table';
    if (previousView === view) return;

    const updateLocalView = (nextView: BrowserView) => {
      if (!isCurrent()) return;
      const nextState = { ...effectiveState(), view: nextView };
      stateRef.current = nextState;
      remember(nextState);
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
      if (isCurrent() && payloadRef.current?.preferences.view === view && savedView !== view) {
        updateLocalView(savedView);
      }
    } catch (reason) {
      if (isCurrent() && payloadRef.current?.preferences.view === view) updateLocalView(previousView);
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

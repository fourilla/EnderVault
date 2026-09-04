import type { AdminAppBootstrap } from './types';

export interface BootstrapFailure {
  kind: 'unavailable' | 'session-expired' | 'forbidden';
  message: string;
}

export interface BootstrapState {
  bootstrap: AdminAppBootstrap | null;
  failure: BootstrapFailure | null;
  loading: boolean;
  requestId: number;
}

type BootstrapAction =
  | { type: 'start'; requestId: number }
  | { type: 'success'; requestId: number; bootstrap: AdminAppBootstrap }
  | { type: 'failure'; requestId: number; failure: BootstrapFailure };

export const initialBootstrapState: BootstrapState = {
  bootstrap: null, failure: null, loading: false, requestId: 0,
};

export function bootstrapReducer(state: BootstrapState, action: BootstrapAction): BootstrapState {
  if (action.type === 'start') {
    if (action.requestId <= state.requestId) return state;
    return { ...state, loading: true, requestId: action.requestId };
  }
  if (action.requestId !== state.requestId) return state;
  if (action.type === 'success') {
    return { ...state, bootstrap: action.bootstrap, failure: null, loading: false };
  }
  return {
    ...state,
    // A transport failure must not tear down the shell or its upload manager.
    bootstrap: action.failure.kind === 'unavailable' ? state.bootstrap : null,
    failure: action.failure,
    loading: false,
  };
}

import { notify, postEncodedForm, postForm } from '../shared/api/form-api';
import type {
  PendingFileDecisionAction,
  PendingFileDecisionActionPayload,
  PendingFileDecisionListPayload,
  PendingBulkAction,
  PendingBulkResult,
} from './types';

export class PendingDecisionLoadError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

export async function loadPendingDecisions(signal: AbortSignal, query = ''): Promise<PendingFileDecisionListPayload> {
  const params = new URLSearchParams();
  if (query) params.set('q', query);
  const response = await fetch(`/api/v1/pending-decisions${params.size ? `?${params}` : ''}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as PendingFileDecisionListPayload & {
    message?: string; notification?: { message?: string };
  };
  if (!response.ok || !Array.isArray(body.decisions)) {
    throw new PendingDecisionLoadError(
      body.notification?.message || body.message || 'Pending decisions could not be loaded.', response.status);
  }
  return body;
}

export async function resolvePendingSelection(
  ids: readonly string[], action: PendingBulkAction, refreshUrl: string,
): Promise<PendingBulkResult> {
  try {
    const body = await postEncodedForm('/api/v1/pending-decisions/resolve-selected', {
      ids: [...ids], action, replaceConfirmed: action === 'REPLACE' ? 'true' : undefined,
    }) as PendingBulkResult;
    notify(body);
    return body;
  } finally {
    // A lost response can still follow a successful mutation. Refresh, never automatically resubmit.
    window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
    if (action !== 'DISCARD') window.EnderVaultFileBrowser?.requestListingRefresh(refreshUrl);
  }
}

export async function resolvePendingDecision(
  id: string,
  action: PendingFileDecisionAction,
  options: { filename?: string; replaceConfirmed?: boolean } = {},
): Promise<PendingFileDecisionActionPayload> {
  const body = await postForm(`/api/v1/pending-decisions/${encodeURIComponent(id)}/resolve`, {
    action,
    filename: options.filename,
    replaceConfirmed: options.replaceConfirmed ? 'true' : undefined,
  }) as PendingFileDecisionActionPayload;
  notify(body);
  window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
  if (action !== 'DISCARD') window.EnderVaultFileBrowser?.requestListingRefresh(window.location.href);
  return body;
}

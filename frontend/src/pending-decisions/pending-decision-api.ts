import { notify, postForm } from '../shared/api/form-api';
import type {
  PendingFileDecisionAction,
  PendingFileDecisionActionPayload,
  PendingFileDecisionListPayload,
} from './types';

export async function loadPendingDecisions(signal: AbortSignal): Promise<PendingFileDecisionListPayload> {
  const response = await fetch('/api/v1/pending-decisions', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as PendingFileDecisionListPayload & { message?: string };
  if (!response.ok || !Array.isArray(body.decisions)) {
    throw new Error(body.message || 'Pending decisions could not be loaded.');
  }
  return body;
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
  return body;
}

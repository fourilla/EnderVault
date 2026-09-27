import { postForm } from '../shared/api/form-api';

export const mergeBase = '/api/v1/files/directory-merges';
export type MergeChoice = 'OVERWRITE' | 'SKIP' | 'KEEP_BOTH' | 'DISCARD_UPLOAD';
export interface MergeSummary {
  id: string;
  operation: 'COPY' | 'MOVE' | 'PENDING';
  title: string;
  statusLabel: string;
  sourceReference: string;
  destinationPath: string;
  revision: number;
  itemCount: number;
  conflictCount: number;
  fullyReviewed: boolean;
  editable: boolean;
  canAbandon: boolean;
  canAbandonRemainingTransfer: boolean;
  successorId: string | null;
  run: { phase: string; paused: boolean } | null;
}
export interface MergeEntry {
  id: string;
  relativePath: string;
  plannedTargetPath: string;
  sourceKind: string;
  targetKind: string | null;
  sourceSize: number;
  targetSize: number | null;
  conflict: string;
  choice: MergeChoice | null;
  stage: 'PUBLICATION_PENDING' | 'FINALIZATION_PENDING' | 'NEEDS_REVIEW' | 'COMPLETE' | 'RETAINED' | null;
}
export interface MergeDetail {
  executionView: boolean;
  review: MergeSummary;
  entries: { page: number; size: number; total: number; items: MergeEntry[] };
}

export function mergeChoices(operation: MergeSummary['operation'], conflict: string): MergeChoice[] {
  return [ ...(conflict === 'FILE_CONFLICT' ? ['OVERWRITE' as const] : []), 'KEEP_BOTH',
    operation === 'PENDING' ? 'DISCARD_UPLOAD' : 'SKIP' ];
}

export const mergeGet = <T,>(path: string, signal?: AbortSignal): Promise<T> =>
  window.EnderVault!.requestJson(`${mergeBase}${path}`, { signal });

export async function saveMergeChoices(id: string, revision: number, choices: Record<string, MergeChoice>) {
  return saveChoicesRequest(id, { revision, choices });
}

export async function saveAllMergeChoices(id: string, revision: number, choice: MergeChoice) {
  return saveChoicesRequest(id, { revision, choice }, '/all');
}

async function saveChoicesRequest(id: string, payload: object, suffix = '') {
  const csrf = window.EnderVault!.csrfPair();
  const header = document.querySelector<HTMLMetaElement>('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  return window.EnderVault!.requestJson(`${mergeBase}/${encodeURIComponent(id)}/choices${suffix}`, {
    method: 'POST', body: JSON.stringify(payload),
    headers: { 'Content-Type': 'application/json', ...(csrf ? { [header]: csrf.value } : {}) },
  });
}

export async function runMerge(review: MergeSummary, replan: boolean) {
  const task = await postForm(`${mergeBase}/${encodeURIComponent(review.id)}/${replan ? 'replan' : 'execute'}`,
    { revision: review.revision });
  window.EnderVaultServerTasks?.track(task, {
    announceStart: true,
    ...(!replan ? { refreshUrl: '/files?path=' + encodeURIComponent(
      review.destinationPath.substring(0, Math.max(0, review.destinationPath.lastIndexOf('/')))) } : {}),
  });
  window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
  return task;
}

export async function abandonMerge(review: MergeSummary) {
  const result = await postForm(`${mergeBase}/${encodeURIComponent(review.id)}/abandon`, { revision: review.revision });
  window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
  return result;
}

export async function abandonRemainingTransfer(review: MergeSummary) {
  const task = await postForm(`${mergeBase}/${encodeURIComponent(review.id)}/abandon-remaining`, { revision: review.revision });
  window.EnderVaultServerTasks?.track(task, { announceStart: true });
  window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
  return task;
}

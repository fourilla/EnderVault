import { postForm } from '../shared/api/form-api';

export const mergeBase = '/api/v1/files/directory-merges';
export type MergeChoice = 'OVERWRITE' | 'SKIP' | 'KEEP_BOTH' | 'DISCARD_UPLOAD';
export interface MergeSummary {
  id: string;
  operation: 'COPY' | 'MOVE' | 'PENDING';
  sourceReference: string;
  destinationPath: string;
  revision: number;
  itemCount: number;
  conflictCount: number;
  fullyReviewed: boolean;
  editable: boolean;
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
}
export interface MergeDetail {
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
  const csrf = window.EnderVault!.csrfPair();
  const header = document.querySelector<HTMLMetaElement>('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  return window.EnderVault!.requestJson(`${mergeBase}/${encodeURIComponent(id)}/choices`, {
    method: 'POST', body: JSON.stringify({ revision, choices }),
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

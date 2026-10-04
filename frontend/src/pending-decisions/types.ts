export interface PendingFileDecision {
  id: string;
  mergeId?: string | null;
  statusLabel: string;
  directory: boolean;
  originalFilename: string;
  submittedBy: string | null;
  sourceLabel: string;
  destinationLabel: string;
  sizeLabel: string;
  createdLabel: string;
  createdAt: string;
}

export interface PendingFileDecisionListPayload {
  decisions: PendingFileDecision[];
}

export type PendingFileDecisionAction = 'KEEP_BOTH' | 'SAVE_AS' | 'REPLACE' | 'DISCARD';

export type PendingBulkAction = Exclude<PendingFileDecisionAction, 'SAVE_AS'>;

export interface PendingBulkItemResult {
  id: string;
  status: 'RESOLVED' | 'NOT_FOUND' | 'REJECTED' | 'FAILED';
  message: string;
  removedId: string | null;
  committedPath: string | null;
}

export interface PendingBulkResult {
  ok: boolean;
  succeededCount: number;
  failedCount: number;
  results: PendingBulkItemResult[];
  notification?: unknown;
}

export interface PendingFileDecisionActionPayload {
  ok: boolean;
  removedId: string;
  committedPath: string | null;
  notification?: unknown;
}

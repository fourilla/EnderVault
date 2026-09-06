export interface PendingFileDecision {
  id: string;
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

export interface PendingFileDecisionActionPayload {
  ok: boolean;
  removedId: string;
  committedPath: string | null;
  notification?: unknown;
}

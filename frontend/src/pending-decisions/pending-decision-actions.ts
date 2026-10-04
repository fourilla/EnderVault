import type { PendingFileDecision, PendingFileDecisionAction } from './types';

export type PendingDecisionActionId = PendingFileDecisionAction | 'MERGE' | 'REVIEW';

export const MAX_PENDING_BULK_ITEMS = 200;
export const NO_COMMON_PENDING_ACTION = 'These items have no common action. Select staged items separately from transfer reviews.';

export interface PendingDecisionActionDefinition {
  id: PendingDecisionActionId;
  label: string;
  icon: string;
  description: string;
  danger?: boolean;
  multiple: boolean;
  supports: (decision: PendingFileDecision) => boolean;
}

const unowned = (decision: PendingFileDecision) => !decision.mergeId;

export const pendingDecisionActionDefinitions: readonly PendingDecisionActionDefinition[] = [
  { id: 'MERGE', label: 'Merge directory', icon: 'fas fa-code-branch', multiple: false,
    description: 'Combine the directory contents. Conflicting items require review; if none conflict, the merge proceeds automatically.',
    supports: (decision) => decision.directory && unowned(decision) },
  { id: 'REVIEW', label: 'Review transfer', icon: 'fas fa-code-branch', multiple: false,
    description: 'Open the existing transfer review to resolve conflicts or resume the operation.',
    supports: (decision) => decision.directory && Boolean(decision.mergeId) },
  { id: 'KEEP_BOTH', label: 'Keep both', icon: 'fas fa-copy', multiple: true,
    description: 'Keep the existing item and save this one with an available numbered name, such as name - 1.txt or folder - 1.',
    supports: unowned },
  { id: 'SAVE_AS', label: 'Save as', icon: 'fas fa-pen', multiple: false,
    description: 'Choose a different name and save this item without replacing an existing item.',
    supports: unowned },
  { id: 'REPLACE', label: 'Replace existing file', icon: 'fas fa-file-arrow-down', danger: true, multiple: true,
    description: 'Replace the existing destination file with this staged file after confirmation.',
    supports: (decision) => !decision.directory && unowned(decision) },
  { id: 'DISCARD', label: 'Discard staged item', icon: 'fas fa-trash-can', danger: true, multiple: true,
    description: 'Delete this staged item after confirmation. The existing destination item is kept.',
    supports: unowned },
];

// UI policy only; the server revalidates current ownership and destination state.
export function pendingDecisionActionsFor(items: readonly PendingFileDecision[]) {
  if (!items.length) return [];
  return pendingDecisionActionDefinitions.filter((action) => (items.length === 1 || action.multiple)
    && items.every(action.supports));
}

import type { BrowserMenuAction, BrowserMenuContext } from '../shared/browser/browser-menu-context';
import { NO_COMMON_PENDING_ACTION, pendingDecisionActionDefinitions, pendingDecisionActionsFor } from './pending-decision-actions';
import type { PendingDecisionController } from './pending-decision-controller';
import type { PendingFileDecision } from './types';

export function keepPendingMenuContext(context: BrowserMenuContext<PendingFileDecision>,
  items: readonly PendingFileDecision[], selected: ReadonlySet<string>) {
  if (context.mode === 'background') return true;
  const latest = new Map(items.map((item) => [item.id, item]));
  if (!context.items.every((item) => {
    const current = latest.get(item.id);
    return current && current.directory === item.directory && (current.mergeId || null) === (item.mergeId || null)
      && current.originalFilename === item.originalFilename && current.destinationLabel === item.destinationLabel;
  })) return false;
  if (context.mode === 'selection') {
    return selected.size === context.items.length && context.items.every((item) => selected.has(item.id));
  }
  return selected.size === 0 || (selected.size === 1 && selected.has(context.items[0].id));
}

export function pendingDecisionMenuActions(actions: PendingDecisionController): BrowserMenuAction<PendingFileDecision>[] {
  return [
    ...pendingDecisionActionDefinitions.map((action): BrowserMenuAction<PendingFileDecision> => ({
      id: `pending-${action.id.toLowerCase()}`, group: 'pending', icon: action.icon, danger: action.danger,
      label: ({ items }) => items.length > 1 ? `${action.label} (${items.length} selected)` : action.label,
      visible: ({ items }) => pendingDecisionActionsFor(items).some((candidate) => candidate.id === action.id),
      disabled: ({ items }) => Boolean(actions.unavailableReason(items.map((item) => item.id), action.id)),
      title: ({ items }) => actions.unavailableReason(items.map((item) => item.id), action.id) || action.description,
      run: ({ items }) => actions.runSelected(items.map((item) => item.id), action.id),
    })),
    { id: 'pending-no-common-action', group: 'pending', label: 'No common action for these items',
      icon: 'fas fa-circle-info', disabled: true, title: NO_COMMON_PENDING_ACTION,
      visible: ({ items }) => items.length > 1 && !pendingDecisionActionsFor(items).length, run() {} },
  ];
}

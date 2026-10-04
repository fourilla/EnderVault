import { postForm, toastError } from '../shared/api/form-api';
import { mergeBase } from '../directory-merges/merge-api';
import { resolvePendingDecision, resolvePendingSelection } from './pending-decision-api';
import { MAX_PENDING_BULK_ITEMS, pendingDecisionActionDefinitions, type PendingDecisionActionId } from './pending-decision-actions';
import type { PendingBulkAction, PendingBulkResult, PendingFileDecision } from './types';

export interface PendingDecisionControllerOptions {
  decision: (id: string) => PendingFileDecision | undefined;
  contextKey: unknown;
  enabled: boolean;
  nested?: boolean;
  resolved: (id: string) => void;
  openMerge: (id: string) => void;
  mergeStarted?: () => void;
  busyChanged?: (busy: boolean) => void;
  isCurrent?: () => boolean;
  selectedIds?: () => readonly string[];
  bulkResolved?: (result: PendingBulkResult) => void;
}

interface Execution {
  action: PendingDecisionActionId;
  controller: AbortController;
  timer?: ReturnType<typeof setTimeout>;
}

export function createPendingDecisionController(read: () => PendingDecisionControllerOptions, changed: () => void) {
  const executions = new Map<string, Execution>();
  let active = true;
  let generation = 0;
  const canRun = (id: string, action: PendingDecisionActionId) => {
    const options = read();
    const decision = options.decision(id);
    return active && options.enabled && (options.isCurrent?.() ?? true) && !executions.has(id) && Boolean(decision
      && pendingDecisionActionDefinitions.find((candidate) => candidate.id === action)?.supports(decision));
  };

  const finish = (id: string, execution: Execution, redraw = true) => {
    if (executions.get(id) !== execution) return;
    clearTimeout(execution.timer);
    execution.controller.abort();
    executions.delete(id);
    if (active && redraw) changed();
  };

  const pollPreparation = (id: string, taskId: string, execution: Execution) => {
    const signal = execution.controller.signal;
    const poll = async () => {
      try {
        const tasks = await window.EnderVault!.requestJson(`/api/v1/tasks?ids=${encodeURIComponent(taskId)}`, { signal });
        if (signal.aborted) return;
        const task = tasks[0];
        if (task?.active) { execution.timer = setTimeout(() => void poll(), 1000); return; }
        finish(id, execution);
        window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
        // The Shell already handles COMPLETE and opens a newly created PENDING review once.
        if (task?.status !== 'COMPLETE' && !(task?.status === 'PENDING' && task.resultReference)) {
          toastError(new Error(task?.message || 'Merge review is unavailable. Check pending decisions.'), 'Review failed.');
        }
      } catch (reason) {
        if (!signal.aborted) { finish(id, execution); toastError(reason, 'Review status unavailable.'); }
      }
    };
    void poll();
  };

  const run = async (id: string, action: PendingDecisionActionId) => {
    const options = read();
    const decision = options.decision(id);
    const definition = pendingDecisionActionDefinitions.find((candidate) => candidate.id === action);
    if (!canRun(id, action) || !decision || !definition) return;
    if (action === 'REVIEW') { options.openMerge(decision.mergeId!); return; }

    const execution: Execution = { action, controller: new AbortController() };
    const startedGeneration = generation;
    const contextKey = options.contextKey;
    const currentContext = () => active && generation === startedGeneration && (options.isCurrent?.() ?? true)
      && read().contextKey === contextKey;
    const canSubmit = () => {
      const latest = read();
      const item = latest.decision(id);
      return currentContext() && latest.enabled && Boolean(item && definition.supports(item)
        && item.directory === decision.directory && (item.mergeId || null) === (decision.mergeId || null));
    };
    executions.set(id, execution);
    changed();
    options.busyChanged?.(true);
    let polling = false;
    try {
      if (action === 'MERGE') {
        const refreshUrl = window.location.href;
        const task = await postForm(`${mergeBase}/pending/${encodeURIComponent(id)}`, {});
        // A submitted server task must survive view disposal, including its Shell tracking.
        window.EnderVaultServerTasks?.track(task, { announceStart: true, refreshUrl });
        if (currentContext()) read().mergeStarted?.();
        if (active && generation === startedGeneration) {
          polling = true;
          pollPreparation(id, task.id, execution);
        }
        return;
      }
      let filename: string | undefined;
      if (action === 'SAVE_AS') {
        const value = await window.EnderVault!.askTextInput({ nested: options.nested,
          title: decision.directory ? 'Save pending directory as' : 'Save pending file as',
          label: decision.directory ? 'Directory name' : 'File name',
          initialValue: decision.originalFilename, confirmLabel: 'Save',
        });
        if (!value) return;
        filename = value;
      }
      if (action === 'REPLACE' || action === 'DISCARD') {
        const confirmed = await window.EnderVault!.askConfirmation({ nested: options.nested,
          title: action === 'REPLACE' ? 'Replace existing file' : 'Discard pending item',
          message: action === 'REPLACE' ? 'Replace the existing destination file with this staged file?'
            : decision.directory ? 'Permanently discard this staged directory and its contents?' : 'Permanently discard this staged file?',
          confirmLabel: action === 'REPLACE' ? 'Replace' : 'Discard', danger: true,
        });
        if (!confirmed) return;
      }
      if (!canSubmit()) return;
      const result = await resolvePendingDecision(id, action, { filename, replaceConfirmed: action === 'REPLACE' });
      if (currentContext()) read().resolved(result.removedId);
    } catch (reason) {
      toastError(reason, action === 'MERGE' ? 'Merge review could not be prepared.' : 'Pending item resolution failed.');
    } finally {
      if (!polling) finish(id, execution);
      if (currentContext()) read().busyChanged?.(false);
    }
  };

  const unavailableReason = (ids: readonly string[], action: PendingDecisionActionId) => {
    const options = read();
    const definition = pendingDecisionActionDefinitions.find((candidate) => candidate.id === action);
    if (!ids.length) return 'Select pending items first.';
    if (ids.length > MAX_PENDING_BULK_ITEMS) return `Select no more than ${MAX_PENDING_BULK_ITEMS} items per action.`;
    if (!active || !options.enabled || !(options.isCurrent?.() ?? true)) return 'Wait for the current list to load successfully.';
    if (new Set(ids).size !== ids.length || !definition || (ids.length > 1 && !definition.multiple)) {
      return 'This action is not available for these items.';
    }
    if (ids.some((id) => executions.has(id))) return 'An action is already in progress for these items.';
    if (ids.some((id) => { const item = options.decision(id); return !item || !definition.supports(item); })) {
      return 'These items changed. Refresh pending decisions before continuing.';
    }
    return '';
  };

  const runSelected = async (selected: readonly string[], action: PendingDecisionActionId) => {
    const ids = [...selected];
    if (unavailableReason(ids, action)) return;
    if (ids.length === 1) { await run(ids[0], action); return; }
    const options = read();
    const selectedMatches = () => {
      const latest = read().selectedIds?.();
      return !latest || (latest.length === ids.length && ids.every((id) => latest.includes(id)));
    };
    if (!selectedMatches()) return;
    const definition = pendingDecisionActionDefinitions.find((candidate) => candidate.id === action)!;
    const originals = ids.map((id) => options.decision(id)!);
    const contextKey = options.contextKey;
    const startedGeneration = generation;
    const currentContext = () => active && generation === startedGeneration && (options.isCurrent?.() ?? true)
      && read().contextKey === contextKey;
    const execution: Execution = { action, controller: new AbortController() };
    ids.forEach((id) => executions.set(id, execution));
    changed();
    options.busyChanged?.(true);
    try {
      const confirmed = await window.EnderVault!.askConfirmation({ nested: options.nested,
        title: `${definition.label}: ${ids.length} items`,
        message: action === 'DISCARD' ? `Permanently discard these ${ids.length} staged items and their contents? Existing destination items are kept.`
          : action === 'REPLACE' ? `Replace the existing destination files with these ${ids.length} staged files?`
            : `Save these ${ids.length} staged items with available numbered names, keeping existing destination items?`,
        confirmLabel: definition.label, danger: definition.danger,
      });
      if (!confirmed || !currentContext() || !read().enabled || !selectedMatches()) return;
      if (!originals.every((original) => {
        const latest = read().decision(original.id);
        return latest && definition.supports(latest) && latest.directory === original.directory
          && (latest.mergeId || null) === (original.mergeId || null);
      })) {
        toastError(new Error('Selected items changed. Refresh pending decisions and review the selection again.'), 'Selection changed.');
        return;
      }
      const result = await resolvePendingSelection(ids, action as PendingBulkAction, window.location.href);
      if (currentContext()) read().bulkResolved?.(result);
    } catch (reason) {
      toastError(reason, 'Pending items could not be processed. Refresh pending decisions before retrying.');
    } finally {
      ids.forEach((id) => finish(id, execution, false));
      if (active) changed();
      if (currentContext()) read().busyChanged?.(false);
    }
  };

  return {
    run,
    runSelected,
    unavailableReason,
    canRun,
    busy: (id: string) => executions.has(id),
    preparing: (id: string) => executions.get(id)?.action === 'MERGE',
    activate: () => { active = true; },
    dispose: () => {
      active = false;
      generation++;
      executions.forEach((execution) => { clearTimeout(execution.timer); execution.controller.abort(); });
      executions.clear();
    },
  };
}

export type PendingDecisionController = ReturnType<typeof createPendingDecisionController>;

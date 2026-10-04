import { postForm, toastError } from '../shared/api/form-api';
import { mergeBase } from '../directory-merges/merge-api';
import { resolvePendingDecision } from './pending-decision-api';
import { pendingDecisionActionDefinitions, type PendingDecisionActionId } from './pending-decision-actions';
import type { PendingFileDecision } from './types';

export interface PendingDecisionControllerOptions {
  decision: (id: string) => PendingFileDecision | undefined;
  contextKey: unknown;
  enabled: boolean;
  nested?: boolean;
  resolved: (id: string) => void;
  openMerge: (id: string) => void;
  mergeStarted?: () => void;
  busyChanged?: (busy: boolean) => void;
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
    return active && options.enabled && !executions.has(id) && Boolean(decision
      && pendingDecisionActionDefinitions.find((candidate) => candidate.id === action)?.supports(decision));
  };

  const finish = (id: string, execution: Execution) => {
    if (executions.get(id) !== execution) return;
    clearTimeout(execution.timer);
    execution.controller.abort();
    executions.delete(id);
    if (active) changed();
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
    if (!active || !options.enabled || executions.has(id) || !decision || !definition?.supports(decision)) return;
    if (action === 'REVIEW') { options.openMerge(decision.mergeId!); return; }

    const execution: Execution = { action, controller: new AbortController() };
    const startedGeneration = generation;
    const contextKey = options.contextKey;
    const currentContext = () => active && generation === startedGeneration
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

  return {
    run,
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

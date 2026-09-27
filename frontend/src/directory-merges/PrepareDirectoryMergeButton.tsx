import { useEffect, useState } from 'react';
import { postForm, toastError } from '../shared/api/form-api';
import { mergeBase } from './merge-api';
import { useDecisionDialog } from '../pending-decisions/DecisionDialogContext';

export function PrepareDirectoryMergeButton({ pendingId, disabled, mergeId, started, busyChanged, labelled = false }: {
  pendingId: string; disabled: boolean; mergeId?: string | null; started?: () => void; busyChanged?: (busy: boolean) => void;
  labelled?: boolean;
}) {
  const { openMerge } = useDecisionDialog();
  const [busy, setBusy] = useState(false);
  const [taskId, setTaskId] = useState('');
  useEffect(() => {
    if (!taskId) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const tasks = await window.EnderVault!.requestJson(`/api/v1/tasks?ids=${encodeURIComponent(taskId)}`, { signal: controller.signal });
        if (controller.signal.aborted) return;
        const task = tasks[0];
        if (task?.active) { timer = setTimeout(() => void poll(), 1000); return; }
        setTaskId(''); setBusy(false);
        window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
        if (task?.status === 'COMPLETE') {
          // A conflict-free upload merge completes in the preparation task.
        } else if (task?.resultReference && task.status === 'PENDING') {
          // The shell opens newly created reviews through the shared task-terminal event.
        } else {
          toastError(new Error(task?.message || 'Merge review is unavailable. Check pending decisions.'), 'Review failed.');
        }
      } catch (reason) {
        if (!controller.signal.aborted) { setTaskId(''); setBusy(false); toastError(reason, 'Review status unavailable.'); }
      }
    };
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [taskId]);

  const prepare = async () => {
    setBusy(true); busyChanged?.(true);
    try {
      const task = await postForm(`${mergeBase}/pending/${encodeURIComponent(pendingId)}`, {});
      window.EnderVaultServerTasks?.track(task, { announceStart: true, refreshUrl: window.location.href });
      started?.();
      setTaskId(task.id);
    } catch (reason) { setBusy(false); toastError(reason, 'Merge review could not be prepared.'); }
    finally { busyChanged?.(false); }
  };
  // Keep this component mounted when the refreshed row gains an owner, so its task poll survives.
  const buttonClass = labelled ? 'ghost icon-text-button' : 'ghost icon-button action-icon';
  if (mergeId && !busy) return <button type="button" className={buttonClass}
    title={labelled ? 'Open the existing transfer review to resolve conflicts or resume the operation.' : 'Review transfer'} aria-label="Review transfer" disabled={disabled} onClick={() => openMerge(mergeId)}>
    <i className="fas fa-code-branch" aria-hidden="true" />
    {labelled && <span>Review transfer</span>}
  </button>;
  return <button type="button" className={buttonClass} title={labelled ? 'Combine the directory contents. Conflicting items require review; if none conflict, the merge proceeds automatically.' : 'Merge directory'} aria-label="Merge directory"
    disabled={disabled || busy} onClick={() => void prepare()}>
    <i className={`fas ${busy ? 'fa-spinner fa-spin' : 'fa-code-branch'}`} aria-hidden="true" />
    {labelled && <span>Merge directory</span>}
  </button>;
}

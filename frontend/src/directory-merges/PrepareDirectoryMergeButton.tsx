import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { postForm, toastError } from '../shared/api/form-api';
import { mergeBase } from './merge-api';

export function PrepareDirectoryMergeButton({ pendingId, disabled }: { pendingId: string; disabled: boolean }) {
  const navigate = useNavigate();
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
        if (task?.resultReference && task.status === 'PENDING') {
          navigate(`/admin/pending-decisions#merge-${encodeURIComponent(task.resultReference)}`);
        } else {
          toastError(new Error(task?.message || 'Merge review is unavailable. Check pending decisions.'), 'Review failed.');
        }
      } catch (reason) {
        if (!controller.signal.aborted) { setTaskId(''); setBusy(false); toastError(reason, 'Review status unavailable.'); }
      }
    };
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [taskId, navigate]);

  const prepare = async () => {
    setBusy(true);
    try {
      const task = await postForm(`${mergeBase}/pending/${encodeURIComponent(pendingId)}`, {});
      window.EnderVaultServerTasks?.track(task, { announceStart: true });
      setTaskId(task.id);
    } catch (reason) { setBusy(false); toastError(reason, 'Merge review could not be prepared.'); }
  };
  return <button type="button" className="ghost icon-button action-icon" title="Merge directory" aria-label="Merge directory"
    disabled={disabled || busy} onClick={() => void prepare()}>
    <i className={`fas ${busy ? 'fa-spinner fa-spin' : 'fa-folder-tree'}`} aria-hidden="true" />
  </button>;
}

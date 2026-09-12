import { lazy, Suspense, useEffect, useState } from 'react';

const ReviewDialog = lazy(() => import('./DirectoryMergeDialog')
  .then((module) => ({ default: module.DirectoryMergeDialog }))
  .catch(() => {
    window.EnderVault?.showToast?.('warning', 'Review could not be opened. Use Pending Decisions to retry.');
    return { default: () => <></> };
  }));

export function transferMergeReference(task: { type?: string; resultReference?: string | null }) {
  return (task.type === 'FILE_COPY' || task.type === 'FILE_MOVE') && task.resultReference
    ? task.resultReference : null;
}

export function TransferMergeDialogHost({ routeKey }: { routeKey: string }) {
  const [selected, select] = useState<string | null>(null);
  useEffect(() => { select(null); }, [routeKey]);
  useEffect(() => {
    const completed = (event: Event) => {
      const reference = transferMergeReference((event as CustomEvent).detail || {});
      if (!reference) return;
      window.dispatchEvent(new Event('endervault:notifications-changed'));
      // Keep the current review open; additional results remain available in notifications.
      select((current) => current || reference);
    };
    document.addEventListener('endervault:task-terminal', completed);
    return () => document.removeEventListener('endervault:task-terminal', completed);
  }, []);
  return selected ? <Suspense fallback={null}>
    <ReviewDialog key={selected} id={selected} close={() => select(null)}
      changed={() => window.dispatchEvent(new Event('endervault:notifications-changed'))} />
  </Suspense> : null;
}

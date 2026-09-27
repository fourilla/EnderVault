export type DecisionTarget = { kind: 'merge' | 'pending'; id: string };

export function announcePending(id: string) {
  window.dispatchEvent(new CustomEvent('endervault:decision-created', { detail: { kind: 'pending', id } }));
}

// No persistent queue: reopening a document must not reopen historical decisions.
export function observeNewDecisions(open: (target: DecisionTarget) => boolean) {
  const seen = new Set<string>();
  let frame: number | null = null;
  const consider = (target: DecisionTarget) => {
    if (typeof target.id !== 'string' || !target.id || !['merge', 'pending'].includes(target.kind)) return;
    const key = `${target.kind}:${target.id}`;
    if (seen.has(key)) return;
    seen.add(key);
    if (frame !== null) return;
    frame = window.requestAnimationFrame(() => {
      frame = null;
      if (document.visibilityState !== 'visible' || document.fullscreenElement
          || document.querySelector('dialog[open], [aria-modal="true"]:not([hidden]), '
            + '.is-editor-fullscreen, .is-comic-fullscreen, .is-image-fullscreen')) return;
      open(target);
    });
  };
  const decision = (event: Event) => {
    const target = (event as CustomEvent<DecisionTarget>).detail;
    if (target) consider(target);
  };
  const terminal = (event: Event) => {
    const task = (event as CustomEvent).detail;
    if (task?.initiatedHere !== true || !['FILE_COPY', 'FILE_MOVE', 'DIRECTORY_MERGE'].includes(task.type)
        || !['PENDING', 'PARTIAL'].includes(task.status) || !task.resultReference) return;
    consider({ kind: 'merge', id: task.resultReference });
  };
  window.addEventListener('endervault:decision-created', decision);
  document.addEventListener('endervault:task-terminal', terminal);
  return () => {
    if (frame !== null) window.cancelAnimationFrame(frame);
    window.removeEventListener('endervault:decision-created', decision);
    document.removeEventListener('endervault:task-terminal', terminal);
  };
}

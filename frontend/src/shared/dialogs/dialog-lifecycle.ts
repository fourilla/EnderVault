export type DialogDismissReason = 'backdrop' | 'escape' | 'native';

export interface DialogPolicy {
  dismissOnBackdrop: boolean;
  dismissOnEscape: boolean;
  busy: boolean;
  onDismiss: (reason: DialogDismissReason) => void;
}

interface PendingDialog { activate: () => void }
const waiting: PendingDialog[] = [];
const active: PendingDialog[] = [];
let scrollLock: { root: HTMLElement; properties: { name: string; value: string; priority: string }[] } | null = null;

function lockPageScroll(document: Document) {
  const root = document.documentElement;
  scrollLock = { root, properties: ['overflow-x', 'overflow-y'].map((name) => ({
    name, value: root.style.getPropertyValue(name), priority: root.style.getPropertyPriority(name),
  })) };
  // A body overflow lock creates a new scroll container and moves sticky shell controls.
  root.style.setProperty('overflow', 'hidden');
}

function unlockPageScroll() {
  if (!scrollLock) return;
  const { root, properties } = scrollLock;
  root.style.removeProperty('overflow');
  properties.forEach(({ name, value, priority }) => {
    if (value) root.style.setProperty(name, value, priority);
  });
  scrollLock = null;
}

function advance() {
  if (active.length || !waiting.length) return;
  const next = waiting.shift()!;
  active.push(next);
  next.activate();
}

// Policy is read at event time so changing busy never closes/reopens the dialog.
export function mountDialog(dialog: HTMLDialogElement, policy: () => DialogPolicy, nested = false): () => void {
  const document = dialog.ownerDocument;
  const opener = document.activeElement as HTMLElement | null;
  let activated = false;
  let disposed = false;
  let dismissRequested = false;
  let backdropPressed = false;

  const outside = (event: MouseEvent) => {
    const rect = dialog.getBoundingClientRect();
    return event.target === dialog && (event.clientX < rect.left || event.clientX > rect.right
      || event.clientY < rect.top || event.clientY > rect.bottom);
  };
  const dismiss = (reason: DialogDismissReason) => {
    const current = policy();
    if (disposed || dismissRequested || current.busy || active.at(-1) !== entry) return;
    if (reason === 'backdrop' && !current.dismissOnBackdrop) return;
    if (reason === 'escape' && !current.dismissOnEscape) return;
    dismissRequested = true;
    current.onDismiss(reason);
  };
  const cancel = (event: Event) => {
    event.preventDefault();
    dismiss('escape');
  };
  const pointerDown = (event: PointerEvent) => {
    backdropPressed = event.button === 0 && outside(event);
  };
  const pointerCancel = () => { backdropPressed = false; };
  const click = (event: MouseEvent) => {
    const dismissBackdrop = backdropPressed && outside(event);
    backdropPressed = false;
    if (dismissBackdrop) dismiss('backdrop');
  };
  const close = () => {
    // Ignore an old queued close event after a StrictMode remount/reopen.
    if (dialog.open || disposed) return;
    dismiss('native');
  };

  const entry: PendingDialog = {
    activate: () => {
      activated = true;
      if (active.length === 1) lockPageScroll(document);
      dialog.addEventListener('cancel', cancel);
      dialog.addEventListener('pointerdown', pointerDown);
      dialog.addEventListener('pointercancel', pointerCancel);
      dialog.addEventListener('click', click);
      dialog.addEventListener('close', close);
      dialog.showModal();
      // React autoFocus runs before a closed dialog becomes focusable.
      dialog.querySelector<HTMLElement>('[data-dialog-initial-focus]:not(:disabled)')?.focus({ preventScroll: true });
    },
  };
  // Only explicit child interactions may bypass the normal modal queue.
  if (nested && active.length) {
    active.push(entry);
    entry.activate();
  } else {
    waiting.push(entry);
    advance();
  }

  return () => {
    if (disposed) return;
    disposed = true;
    const index = waiting.indexOf(entry);
    if (index >= 0) waiting.splice(index, 1);
    if (activated) {
      const wasTop = active.at(-1) === entry;
      active.splice(active.indexOf(entry), 1);
      dialog.removeEventListener('cancel', cancel);
      dialog.removeEventListener('pointerdown', pointerDown);
      dialog.removeEventListener('pointercancel', pointerCancel);
      dialog.removeEventListener('click', click);
      dialog.removeEventListener('close', close);
      if (dialog.open) dialog.close();
      if (!active.length) unlockPageScroll();
      if (wasTop && opener?.isConnected) opener.focus({ preventScroll: true });
    }
    advance();
  };
}

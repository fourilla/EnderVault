export type DialogDismissReason = 'backdrop' | 'escape' | 'native';

export interface DialogPolicy {
  dismissOnBackdrop: boolean;
  dismissOnEscape: boolean;
  busy: boolean;
  onDismiss: (reason: DialogDismissReason) => void;
}

interface PendingDialog { activate: () => void }
const waiting: PendingDialog[] = [];
let active: PendingDialog | null = null;

function advance() {
  if (active || !waiting.length) return;
  active = waiting.shift()!;
  active.activate();
}

// Policy is read at event time so changing busy never closes/reopens the dialog.
export function mountDialog(dialog: HTMLDialogElement, policy: () => DialogPolicy): () => void {
  const document = dialog.ownerDocument;
  const opener = document.activeElement as HTMLElement | null;
  let activated = false;
  let disposed = false;
  let dismissRequested = false;
  let backdropPressed = false;
  let previousOverflow = '';

  const outside = (event: MouseEvent) => {
    const rect = dialog.getBoundingClientRect();
    return event.target === dialog && (event.clientX < rect.left || event.clientX > rect.right
      || event.clientY < rect.top || event.clientY > rect.bottom);
  };
  const dismiss = (reason: DialogDismissReason) => {
    const current = policy();
    if (disposed || dismissRequested || current.busy) return;
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
      previousOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      dialog.addEventListener('cancel', cancel);
      dialog.addEventListener('pointerdown', pointerDown);
      dialog.addEventListener('pointercancel', pointerCancel);
      dialog.addEventListener('click', click);
      dialog.addEventListener('close', close);
      dialog.showModal();
    },
  };
  waiting.push(entry);
  advance();

  return () => {
    if (disposed) return;
    disposed = true;
    const index = waiting.indexOf(entry);
    if (index >= 0) waiting.splice(index, 1);
    if (activated) {
      dialog.removeEventListener('cancel', cancel);
      dialog.removeEventListener('pointerdown', pointerDown);
      dialog.removeEventListener('pointercancel', pointerCancel);
      dialog.removeEventListener('click', click);
      dialog.removeEventListener('close', close);
      if (dialog.open) dialog.close();
      document.body.style.overflow = previousOverflow;
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    }
    if (active === entry) active = null;
    advance();
  };
}

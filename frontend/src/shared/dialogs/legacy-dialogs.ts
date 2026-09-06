import { mountDialog } from './dialog-lifecycle';

// Legacy tools keep their existing markup, but no longer own modal lifecycle.
export function createLegacyDialogBridge() {
  const opened = new Map<HTMLDialogElement, () => void>();
  const close = (dialog: HTMLDialogElement) => opened.get(dialog)?.();
  return {
    open: (dialog: HTMLDialogElement) => {
      if (opened.has(dialog)) return;
      let dispose: (() => void) | undefined;
      const nativeClose = () => { if (!dialog.open) close(dialog); };
      const release = () => {
        if (!opened.delete(dialog)) return;
        dialog.removeEventListener('close', nativeClose);
        dispose?.();
      };
      opened.set(dialog, release);
      dialog.addEventListener('close', nativeClose);
      dispose = mountDialog(dialog, () => ({
        dismissOnBackdrop: false, dismissOnEscape: true, busy: false,
        onDismiss: release,
      }));
    },
    close,
    closeAll: () => [...opened.values()].forEach((release) => release()),
  };
}

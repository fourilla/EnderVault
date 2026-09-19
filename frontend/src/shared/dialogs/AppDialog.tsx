import { type PropsWithChildren, useLayoutEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { mountDialog } from './dialog-lifecycle';
import './dialogs.css';

interface AppDialogProps extends PropsWithChildren {
  open: boolean;
  onDismiss: () => void;
  labelledBy: string;
  className?: string;
  dismissOnBackdrop?: boolean;
  dismissOnEscape?: boolean;
  busy?: boolean;
  nested?: boolean;
}

export function AppDialog({ open, onDismiss, labelledBy, className,
  dismissOnBackdrop = false, dismissOnEscape = true, busy = false, nested = false, children }: AppDialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const policy = useRef({ onDismiss, dismissOnBackdrop, dismissOnEscape, busy });
  useLayoutEffect(() => { policy.current = { onDismiss, dismissOnBackdrop, dismissOnEscape, busy }; });
  useLayoutEffect(() => {
    if (open && ref.current) return mountDialog(ref.current, () => policy.current, nested);
  }, [open, nested]);

  if (!open) return null;
  return createPortal(
    <dialog ref={ref} className={className} aria-labelledby={labelledBy} aria-busy={busy || undefined}>
      {children}
    </dialog>, document.body,
  );
}

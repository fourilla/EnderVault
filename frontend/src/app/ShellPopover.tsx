import {
  type PropsWithChildren,
  type ReactNode,
  useEffect,
  useId,
  useRef,
  useState,
} from 'react';
import { useLocation } from 'react-router-dom';

interface ShellPopoverProps extends PropsWithChildren {
  icon: string;
  label: string;
  indicator?: ReactNode;
}

export function ShellPopover({ icon, label, indicator, children }: ShellPopoverProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const location = useLocation();

  useEffect(() => setOpen(false), [location.pathname, location.search]);

  useEffect(() => {
    if (!open) return undefined;

    const closeOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };

    document.addEventListener('pointerdown', closeOutside);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  return (
    <div className={`topbar-control admin-shell-popover${open ? ' is-open' : ''}`} ref={rootRef}>
      <button
        className="ghost icon-button topbar-control-trigger"
        type="button"
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-controls={menuId}
        aria-label={label}
        title={label}
        onClick={() => setOpen((current) => !current)}
      >
        <i className={icon} aria-hidden="true" />
        {indicator}
      </button>
      <div className="topbar-control-popover">
        <div className="topbar-control-menu" id={menuId} role="dialog" aria-label={label}>
          {children}
        </div>
      </div>
    </div>
  );
}

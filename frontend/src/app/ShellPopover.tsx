import {
  type PropsWithChildren,
  type MouseEventHandler,
  type ReactNode,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
} from 'react';
import { useTopbarPopover } from './TopbarPopoverContext';

interface ShellPopoverProps extends PropsWithChildren {
  id: string;
  icon: string;
  label: string;
  indicator?: ReactNode;
  rootClassName?: string;
  triggerClassName?: string;
  triggerDataAttributes?: Record<string, string>;
  onTriggerClick?: MouseEventHandler<HTMLButtonElement>;
  onOpen?: () => void;
}

export function ShellPopover({
  id,
  icon,
  label,
  indicator,
  rootClassName,
  triggerClassName,
  triggerDataAttributes,
  onTriggerClick,
  onOpen,
  children,
}: ShellPopoverProps) {
  const { activeId, show, hide, closeAll } = useTopbarPopover();
  const rootRef = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const open = activeId === id;

  useLayoutEffect(() => {
    if (open && rootRef.current) window.EnderVaultTopbarControls?.positionPopover(rootRef.current);
  }, [open]);

  useEffect(() => {
    if (open) onOpen?.();
  }, [onOpen, open]);

  useEffect(() => {
    if (!open) return undefined;

    const closeOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) closeAll();
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeAll();
    };

    document.addEventListener('pointerdown', closeOutside);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [closeAll, open]);

  return (
    <div
      className={`topbar-control admin-shell-popover${rootClassName ? ` ${rootClassName}` : ''}${open ? ' is-open' : ''}`}
      data-topbar-popover-id={id}
      ref={rootRef}
      onPointerEnter={() => show(id)}
      onPointerLeave={() => hide(id)}
      onFocus={() => show(id)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) hide(id);
      }}
    >
      <button
        {...triggerDataAttributes}
        className={`ghost icon-button topbar-control-trigger${triggerClassName ? ` ${triggerClassName}` : ''}`}
        type="button"
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-controls={menuId}
        aria-label={label}
        title={label}
        onClick={onTriggerClick}
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

import { type ReactNode, useEffect, useId, useLayoutEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useLocation } from 'react-router-dom';
import { useTopbarPopover } from './TopbarPopoverContext';
import './floating-page-actions.css';

type FloatingPageActionsProps = { label: string } & (
  | { mode: 'menu'; children: ReactNode; selectedCount?: number }
  | { mode: 'single'; icon: string; onAction: () => void; disabled?: boolean; danger?: boolean }
);

export function FloatingPageActions(props: FloatingPageActionsProps) {
  const { activeId, show, closeAll } = useTopbarPopover();
  const { key: routeKey } = useLocation();
  const id = useId();
  const panelId = `${id}-panel`;
  const root = useRef<HTMLDivElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const openedRoute = useRef('');
  const keyboardOpen = useRef(false);
  const open = props.mode === 'menu' && activeId === id && openedRoute.current === routeKey;

  useLayoutEffect(() => {
    if (open && keyboardOpen.current) {
      panel.current?.querySelector<HTMLButtonElement>('button:not(:disabled):not([hidden])')?.focus({ preventScroll: true });
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const outside = (event: Event) => {
      if (!root.current?.contains(event.target as Node)) closeAll();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      closeAll();
      trigger.current?.focus({ preventScroll: true });
    };
    const hideForOverlay = () => {
      if (document.fullscreenElement || document.querySelector('dialog[open], [aria-modal="true"]:not([hidden]), '
          + '.is-editor-fullscreen, .is-comic-fullscreen, .is-image-fullscreen')) closeAll();
    };
    const observer = new MutationObserver(hideForOverlay);
    observer.observe(document.body, { subtree: true, childList: true, attributes: true,
      attributeFilter: ['open', 'aria-modal', 'hidden', 'class'] });
    document.addEventListener('pointerdown', outside);
    document.addEventListener('focusin', outside);
    document.addEventListener('keydown', escape);
    document.addEventListener('fullscreenchange', hideForOverlay);
    hideForOverlay();
    return () => {
      observer.disconnect();
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('focusin', outside);
      document.removeEventListener('keydown', escape);
      document.removeEventListener('fullscreenchange', hideForOverlay);
    };
  }, [open, closeAll]);

  return createPortal(<div className="floating-page-actions" data-mode={props.mode} ref={root}>
    {props.mode === 'menu' && <div className="floating-actions-panel" id={panelId} ref={panel}
      role="dialog" aria-label={props.label} hidden={!open}
      onClickCapture={(event) => {
        if (event.currentTarget.contains(event.target as Node)
            && (event.target as Element).closest('button:not(:disabled), a[href]')) {
          // A child may open a modal: its focus-return target must stay visible after this panel closes.
          trigger.current?.focus({ preventScroll: true });
        }
      }}
      onClick={(event) => {
        if (event.currentTarget.contains(event.target as Node)
            && (event.target as Element).closest('button:not(:disabled), a[href]')) closeAll();
      }}>
      {props.children}
    </div>}
    <button ref={trigger} type="button"
      className={`icon-button floating-actions-trigger${open ? ' is-open' : ''}${props.mode === 'single' && props.danger ? ' danger' : ''}`}
      title={props.label} aria-label={props.label}
      disabled={props.mode === 'single' && props.disabled}
      aria-expanded={props.mode === 'menu' ? open : undefined}
      aria-haspopup={props.mode === 'menu' ? 'dialog' : undefined}
      aria-controls={props.mode === 'menu' ? panelId : undefined}
      onClick={(event) => {
        if (props.mode === 'single') { closeAll(); props.onAction(); return; }
        if (open) closeAll();
        else { openedRoute.current = routeKey; keyboardOpen.current = event.detail === 0; show(id); }
      }}>
      <i className={props.mode === 'single' ? props.icon : open ? 'fas fa-angles-down' : 'fas fa-angles-up'} aria-hidden="true" />
      {props.mode === 'menu' && Boolean(props.selectedCount) &&
        <span className="floating-actions-count" aria-label={`${props.selectedCount} selected`}>{props.selectedCount}</span>}
    </button>
  </div>, document.body);
}

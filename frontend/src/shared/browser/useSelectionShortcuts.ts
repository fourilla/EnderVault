import { useEffect, useRef } from 'react';
import { toastError } from '../api/form-api';

interface SelectionShortcuts {
  enabled: boolean;
  contextKey: string;
  selectedCount: number;
  selectAll: () => void;
  clearSelection: () => void;
  deleteSelection?: (isCurrent: () => boolean) => Promise<void>;
  scope: () => HTMLElement | null;
}

const overlaySelector = 'dialog[open], [aria-modal="true"]:not([hidden]), .context-menu, '
  + '.admin-shell-popover.is-open, .floating-actions-panel:not([hidden]), '
  + '.is-editor-fullscreen, .is-comic-fullscreen, .is-image-fullscreen';

function outsideSelectionScope(target: EventTarget | null, scope: HTMLElement) {
  const element = target as Element | null;
  if (!element?.closest) return true;
  if (element === document.body || element === document.documentElement) return false;
  if (!scope.contains(element)) return true;
  if (element.closest('.sticky-note-layer, .CodeMirror, .cm-editor, [data-native-context-menu], '
    + '[contenteditable]:not([contenteditable="false"]), [role="textbox"]')) return true;
  const input = element.closest('input, textarea, select');
  if (input) return !input.matches('input[type="checkbox"].row-select-checkbox, input[type="checkbox"].card-check, input[type="checkbox"].select-all-checkbox');
  if (element.closest('.table-actions, .action-icon')) return true;
  return Boolean(element.closest('button, a[href], summary, [role="button"]')
    && !element.closest('[data-context-item="true"]'));
}

export function useSelectionShortcuts(options: SelectionShortcuts) {
  const current = useRef({ options, contextKey: options.contextKey, revision: 0 });
  if (current.current.contextKey !== options.contextKey) {
    current.current.contextKey = options.contextKey;
    current.current.revision += 1;
  }
  current.current.options = options;
  useEffect(() => {
    let active = true;
    let deleting = false;
    const onKeyDown = (event: KeyboardEvent) => {
      const selectAll = (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey
        && event.key.toLowerCase() === 'a';
      const clear = event.key === 'Escape' && !event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey;
      const remove = event.key === 'Delete' && !event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey;
      if ((!selectAll && !clear && !remove) || event.defaultPrevented || event.isComposing) return;
      const { options, revision } = current.current;
      const deleteSelection = options.deleteSelection;
      const scope = options.scope();
      if (!options.enabled || !scope || document.visibilityState !== 'visible'
        || document.fullscreenElement || document.querySelector(overlaySelector)
        || outsideSelectionScope(event.target, scope)
        || outsideSelectionScope(document.activeElement, scope)) return;
      if ((clear || remove) && options.selectedCount === 0) {
        // An unchecked last item can still be the Shift anchor. Reset it without
        // consuming native Escape when there is no visible selection to clear.
        if (clear) options.clearSelection();
        return;
      }
      if (remove && (!deleteSelection || event.repeat || deleting)) return;
      event.preventDefault();
      if (selectAll) options.selectAll();
      else if (clear) options.clearSelection();
      else if (deleteSelection) {
        deleting = true;
        const isCurrent = () => active && current.current.revision === revision && current.current.options.enabled;
        void (async () => {
          try { await deleteSelection(isCurrent); }
          catch (reason) { toastError(reason, 'The selected action failed.'); }
          finally { deleting = false; }
        })();
      }
    };
    // Inspect overlays before their Escape handlers close them in the bubble phase.
    document.addEventListener('keydown', onKeyDown, true);
    return () => {
      active = false;
      document.removeEventListener('keydown', onKeyDown, true);
    };
  }, []);
}

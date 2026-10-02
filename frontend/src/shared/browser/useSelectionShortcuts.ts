import { useEffect, useRef } from 'react';

interface SelectionShortcuts {
  enabled: boolean;
  selectedCount: number;
  selectAll: () => void;
  clearSelection: () => void;
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
  if (input) return !input.matches('input[type="checkbox"].row-select-checkbox, input[type="checkbox"].select-all-checkbox');
  if (element.closest('.table-actions, .action-icon')) return true;
  return Boolean(element.closest('button, a[href], summary, [role="button"]')
    && !element.closest('[data-context-item="true"]'));
}

export function useSelectionShortcuts(options: SelectionShortcuts) {
  const current = useRef(options);
  current.current = options;
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const selectAll = (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey
        && event.key.toLowerCase() === 'a';
      const clear = event.key === 'Escape' && !event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey;
      if ((!selectAll && !clear) || event.defaultPrevented || event.isComposing) return;
      const options = current.current;
      const scope = options.scope();
      if (!options.enabled || !scope || document.visibilityState !== 'visible'
        || document.fullscreenElement || document.querySelector(overlaySelector)
        || outsideSelectionScope(event.target, scope)
        || outsideSelectionScope(document.activeElement, scope)) return;
      if (clear && options.selectedCount === 0) return;
      event.preventDefault();
      if (selectAll) options.selectAll();
      else options.clearSelection();
    };
    // Inspect overlays before their Escape handlers close them in the bubble phase.
    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, []);
}

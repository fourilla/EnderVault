import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import { applySuggestion } from './query-completion';
import { useSearchAssistance } from './useSearchAssistance';

interface SearchInputOptions {
  scope?: string;
  owner: string;
  hidden: string;
  value: string;
  disabled: boolean;
  open: boolean;
  onChange: (value: string) => void;
  onOpen: () => void;
  onClose: () => void;
}

function blocked() {
  return document.visibilityState !== 'visible' || Boolean(document.fullscreenElement
    || document.querySelector('dialog[open], [aria-modal="true"]:not([hidden]), .context-menu, '
      + '.is-editor-fullscreen, .is-comic-fullscreen, .is-image-fullscreen'));
}

export function useSearchInput(options: SearchInputOptions) {
  const { scope, owner, hidden, value, disabled, open, onChange, onOpen, onClose } = options;
  const current = useRef(options);
  current.current = options;
  const inputRef = useRef<HTMLInputElement>(null);
  const rootRef = useRef<HTMLFormElement>(null);
  const pendingCaret = useRef<{ value: string; caret: number } | null>(null);
  const [selection, setSelection] = useState({ start: 0, end: 0 });
  const [active, setActive] = useState({ key: '', index: -1 });
  const listId = useId();
  const enabled = Boolean(scope && open && !disabled);
  const assistance = useSearchAssistance({ scope, owner, query: value, caret: selection.start,
    selectionEnd: selection.end, hidden, enabled });
  const key = JSON.stringify([owner, scope, value, selection, assistance.options]);
  const activeIndex = active.key === key ? active.index : -1;

  useLayoutEffect(() => { pendingCaret.current = null; }, [owner, scope]);
  useLayoutEffect(() => {
    const pending = pendingCaret.current;
    if (!pending || pending.value !== value) return;
    pendingCaret.current = null;
    inputRef.current?.focus({ preventScroll: true });
    inputRef.current?.setSelectionRange(pending.caret, pending.caret);
  }, [value, selection.start]);

  useEffect(() => {
    if (!enabled) return;
    const closeOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) current.current.onClose();
    };
    const close = () => current.current.onClose();
    const visibility = () => { if (document.visibilityState !== 'visible') close(); };
    document.addEventListener('pointerdown', closeOutside);
    document.addEventListener('visibilitychange', visibility);
    window.addEventListener('blur', close);
    return () => {
      document.removeEventListener('pointerdown', closeOutside);
      document.removeEventListener('visibilitychange', visibility);
      window.removeEventListener('blur', close);
    };
  }, [enabled]);

  const readSelection = (input: HTMLInputElement) => setSelection({
    start: input.selectionStart ?? value.length, end: input.selectionEnd ?? value.length,
  });
  const choose = (index: number) => {
    if (disabled || blocked() || !assistance.context || !assistance.options[index]) return;
    const suggestion = assistance.options[index];
    const next = applySuggestion(value, assistance.context, suggestion);
    pendingCaret.current = next;
    setSelection({ start: next.caret, end: next.caret });
    setActive({ key: '', index: -1 });
    onChange(next.value);
    if (!suggestion.keepOpen) onClose();
  };
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.nativeEvent.isComposing || event.keyCode === 229 || event.defaultPrevented || disabled) return;
    if (blocked()) { onClose(); return; }
    if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    if (enabled && event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onClose();
    } else if (scope && !enabled && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault();
      onOpen();
    } else if (selection.start === selection.end && scope && assistance.options.length
      && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault();
      onOpen();
      const index = event.key === 'ArrowDown' ? (activeIndex + 1) % assistance.options.length
        : (activeIndex < 0 ? assistance.options.length - 1 : (activeIndex - 1 + assistance.options.length) % assistance.options.length);
      setActive({ key, index });
    } else if (enabled && event.key === 'Enter' && activeIndex >= 0) {
      event.preventDefault();
      choose(activeIndex);
    } else if (event.key === 'Enter') onClose();
  };
  return {
    rootRef,
    listId,
    activeIndex,
    choose,
    assistance,
    visible: enabled,
    close: onClose,
    rootProps: {
      onBlur: (event: React.FocusEvent<HTMLFormElement>) => {
        if (!rootRef.current?.contains(event.relatedTarget as Node)) onClose();
      },
    },
    inputProps: {
      ref: inputRef,
      onFocus: (event: React.FocusEvent<HTMLInputElement>) => {
        readSelection(event.currentTarget);
        if (!disabled && scope && !blocked()) onOpen();
      },
      onChange: (event: React.ChangeEvent<HTMLInputElement>) => {
        if (disabled) return;
        readSelection(event.currentTarget);
        onChange(event.currentTarget.value);
        if (scope && !blocked()) onOpen();
      },
      onSelect: (event: React.SyntheticEvent<HTMLInputElement>) => readSelection(event.currentTarget),
      onKeyDown,
      role: scope ? 'combobox' : undefined,
      'aria-autocomplete': scope ? 'list' as const : undefined,
      'aria-expanded': scope ? enabled : undefined,
      'aria-controls': enabled ? listId : undefined,
      'aria-activedescendant': enabled && activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined,
    },
  };
}

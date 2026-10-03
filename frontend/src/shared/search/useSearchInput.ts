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

interface InputCompletion {
  owner: string;
  scope?: string;
  value: string;
  caret: number;
  keepOpen: boolean;
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
  const pendingCompletion = useRef<InputCompletion | null>(null);
  const completionFrame = useRef<number | null>(null);
  const composition = useRef(false);
  // Route registration echoes edits after a layout effect; keep the DOM value synchronous.
  const [inputValue, setInputValue] = useState(value);
  const [composing, setComposing] = useState(false);
  const [selection, setSelection] = useState({ start: 0, end: 0 });
  const [active, setActive] = useState({ key: '', index: -1 });
  const listId = useId();
  const enabled = Boolean(scope && open && !disabled);
  const assistance = useSearchAssistance({ scope, owner, query: inputValue,
    caret: composing ? selection.end : selection.start, selectionEnd: selection.end, hidden, enabled });
  const key = JSON.stringify([owner, scope, inputValue, selection, assistance.options]);
  const activeIndex = active.key === key ? active.index : -1;

  const cancelCompletion = () => {
    pendingCompletion.current = null;
    if (completionFrame.current != null) cancelAnimationFrame(completionFrame.current);
    completionFrame.current = null;
  };
  const close = () => { cancelCompletion(); onClose(); };
  useLayoutEffect(() => {
    cancelCompletion();
    pendingCaret.current = null;
    composition.current = false;
    setComposing(false);
    setInputValue(current.current.value);
  }, [owner, scope]);
  useLayoutEffect(() => {
    if (!composition.current) setInputValue(value);
  }, [value]);
  useLayoutEffect(() => {
    const pending = pendingCaret.current;
    if (composition.current || !pending || pending.value !== value || pending.value !== inputValue) return;
    pendingCaret.current = null;
    inputRef.current?.focus({ preventScroll: true });
    inputRef.current?.setSelectionRange(pending.caret, pending.caret);
  }, [value, inputValue, selection.start]);
  useEffect(() => () => cancelCompletion(), []);

  useEffect(() => {
    if (!enabled) return;
    const close = () => { cancelCompletion(); current.current.onClose(); };
    const closeOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) close();
    };
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
    start: input.selectionStart ?? input.value.length, end: input.selectionEnd ?? input.value.length,
  });
  const updateInput = (input: HTMLInputElement) => {
    setInputValue(input.value);
    readSelection(input);
    if (!composition.current) onChange(input.value);
    if (scope && !blocked()) onOpen();
  };
  const applyCompletion = (next: InputCompletion) => {
    const latest = current.current;
    if (composition.current || latest.disabled || latest.owner !== next.owner || latest.scope !== next.scope || blocked()) return;
    pendingCaret.current = next;
    setInputValue(next.value);
    setSelection({ start: next.caret, end: next.caret });
    setActive({ key: '', index: -1 });
    latest.onChange(next.value);
    if (next.keepOpen) latest.onOpen();
    else latest.onClose();
  };
  const completeComposition = () => {
    if (!pendingCompletion.current || completionFrame.current != null) return;
    // Apply an explicit choice only after native composition/input events have settled.
    completionFrame.current = requestAnimationFrame(() => {
      completionFrame.current = null;
      const next = pendingCompletion.current;
      pendingCompletion.current = null;
      if (next) applyCompletion(next);
    });
  };
  const choose = (index: number) => {
    if (disabled || blocked() || !assistance.context || !assistance.options[index]) return;
    const suggestion = assistance.options[index];
    const next = { ...applySuggestion(inputValue, assistance.context, suggestion), owner, scope,
      keepOpen: suggestion.keepOpen };
    cancelCompletion();
    if (composition.current) {
      pendingCompletion.current = next;
      inputRef.current?.blur();
      if (!composition.current) completeComposition();
    } else applyCompletion(next);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.defaultPrevented || disabled) return;
    if (composition.current || event.nativeEvent.isComposing || event.keyCode === 229) {
      if (event.key === 'Enter') event.preventDefault();
      return;
    }
    if (blocked()) { close(); return; }
    if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    if (enabled && event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
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
    } else if (event.key === 'Enter') close();
  };
  return {
    rootRef,
    listId,
    activeIndex,
    choose,
    assistance,
    visible: enabled,
    close,
    isComposing: () => composition.current,
    rootProps: {
      onBlur: (event: React.FocusEvent<HTMLFormElement>) => {
        if (!rootRef.current?.contains(event.relatedTarget as Node)) onClose();
      },
    },
    inputProps: {
      ref: inputRef,
      value: inputValue,
      onFocus: (event: React.FocusEvent<HTMLInputElement>) => {
        readSelection(event.currentTarget);
        if (!disabled && scope && !blocked()) onOpen();
      },
      onChange: (event: React.ChangeEvent<HTMLInputElement>) => {
        if (disabled) return;
        updateInput(event.currentTarget);
      },
      onCompositionStart: () => {
        if (disabled) return;
        cancelCompletion();
        composition.current = true;
        pendingCaret.current = null;
        setComposing(true);
        setActive({ key: '', index: -1 });
      },
      onCompositionEnd: (event: React.CompositionEvent<HTMLInputElement>) => {
        const wasComposing = composition.current;
        composition.current = false;
        setComposing(false);
        if (wasComposing && !disabled) {
          updateInput(event.currentTarget);
          completeComposition();
        }
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

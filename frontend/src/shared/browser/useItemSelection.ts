import { useEffect, useMemo, useRef, useState } from 'react';

const selectionCheckbox = 'input[type="checkbox"].row-select-checkbox, input[type="checkbox"].card-check';
const interactiveTarget = 'input, button, label, select, textarea, summary, .table-actions, .action-icon';
const isRangeClick = (event: { shiftKey?: boolean; ctrlKey?: boolean; metaKey?: boolean; altKey?: boolean }) =>
  event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey;

export function useItemSelection<T>({
  items,
  enabled,
  locationKey,
  itemKey,
  openItem,
}: {
  items: T[];
  enabled: boolean;
  locationKey: string;
  itemKey: (item: T) => string;
  openItem: (item: T) => void;
}) {
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const selectedRef = useRef(selected);
  const enabledRef = useRef(enabled);
  const anchorRef = useRef<string | null>(null);
  const longPressRef = useRef<{
    timer: number | null;
    pointerId: number | null;
    startX: number;
    startY: number;
    item: T | null;
    suppressClick: boolean;
  }>({ timer: null, pointerId: null, startX: 0, startY: 0, item: null, suppressClick: false });

  selectedRef.current = selected;
  enabledRef.current = enabled;

  const selectedItems = useMemo(
    () => items.filter((item) => selected.has(itemKey(item))),
    [itemKey, items, selected],
  );

  const selectItem = (item: T, checked: boolean) => {
    const key = itemKey(item);
    setSelected((current) => {
      const next = new Set(current);
      if (checked) next.add(key);
      else next.delete(key);
      return next;
    });
  };

  const toggleItemSelection = (item: T) => {
    const key = itemKey(item);
    anchorRef.current = key;
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const selectRange = (item: T) => {
    const keys = items.map(itemKey);
    const endpoint = keys.indexOf(itemKey(item));
    if (endpoint < 0) return;
    let anchor = anchorRef.current == null ? -1 : keys.indexOf(anchorRef.current);
    if (anchor < 0) {
      anchor = endpoint;
      anchorRef.current = keys[endpoint];
    }
    setSelected(new Set(keys.slice(Math.min(anchor, endpoint), Math.max(anchor, endpoint) + 1)));
  };

  const cancelLongPress = () => {
    if (longPressRef.current.timer != null) window.clearTimeout(longPressRef.current.timer);
    longPressRef.current.timer = null;
    longPressRef.current.pointerId = null;
    longPressRef.current.item = null;
  };

  const clearSelection = () => {
    anchorRef.current = null;
    setSelected((current) => current.size ? new Set() : current);
  };

  const selectAll = () => {
    if (!enabledRef.current || items.length === 0) return;
    const keys = new Set(items.map(itemKey));
    setSelected((current) => current.size === keys.size && [...keys].every((key) => current.has(key))
      ? current : keys);
  };

  useEffect(() => {
    clearSelection();
    cancelLongPress();
    longPressRef.current.suppressClick = false;
  }, [locationKey]);

  useEffect(() => {
    const visibleKeys = new Set(items.map(itemKey));
    if (anchorRef.current != null && !visibleKeys.has(anchorRef.current)) anchorRef.current = null;
    setSelected((current) => {
      const retained = [...current].filter((key) => visibleKeys.has(key));
      return retained.length === current.size ? current : new Set(retained);
    });
  }, [items, itemKey]);

  useEffect(() => {
    if (!enabled) cancelLongPress();
    return cancelLongPress;
  }, [enabled]);

  const itemInteractionProps = (item: T) => ({
    onMouseDownCapture: (event: React.MouseEvent<HTMLElement>) => {
      if (enabledRef.current && event.button === 0 && isRangeClick(event)
          && !(event.target as HTMLElement).closest(interactiveTarget)) {
        event.preventDefault();
        // Preventing text selection must not leave keyboard focus in a previous editor.
        event.currentTarget.querySelector<HTMLInputElement>(selectionCheckbox)?.focus({ preventScroll: true });
      }
    },
    onPointerDown: (event: React.PointerEvent<HTMLElement>) => {
      if (!enabledRef.current
          || event.button !== 0
          || event.shiftKey
          || (event.target as HTMLElement).closest('input, button, label, .table-actions, .action-icon')) {
        return;
      }
      cancelLongPress();
      longPressRef.current.pointerId = event.pointerId;
      longPressRef.current.startX = event.clientX;
      longPressRef.current.startY = event.clientY;
      longPressRef.current.item = item;
      longPressRef.current.timer = window.setTimeout(() => {
        longPressRef.current.suppressClick = true;
        toggleItemSelection(item);
        cancelLongPress();
      }, 520);
    },
    onPointerMove: (event: React.PointerEvent<HTMLElement>) => {
      const pending = longPressRef.current;
      if (pending.timer == null || pending.pointerId !== event.pointerId) return;
      if (Math.abs(event.clientX - pending.startX) > 10
          || Math.abs(event.clientY - pending.startY) > 10) {
        cancelLongPress();
      }
    },
    onPointerUp: cancelLongPress,
    onPointerCancel: cancelLongPress,
    onContextMenu: (event: React.MouseEvent<HTMLElement>) => {
      if (longPressRef.current.suppressClick) {
        event.preventDefault();
        event.stopPropagation();
      }
    },
    onChangeCapture: (event: React.FormEvent<HTMLElement>) => {
      const target = event.target as HTMLElement;
      if (!enabledRef.current || !target.closest(selectionCheckbox)) return;
      // Checkbox change is derived from the click. Keep its native checked state,
      // but replace the range once instead of also invoking the checkbox toggle.
      if (isRangeClick(event.nativeEvent as MouseEvent)) {
        event.stopPropagation();
        selectRange(item);
      } else {
        anchorRef.current = itemKey(item);
      }
    },
    onClickCapture: (event: React.MouseEvent<HTMLElement>) => {
      if (longPressRef.current.suppressClick) {
        event.preventDefault();
        event.stopPropagation();
        longPressRef.current.suppressClick = false;
        return;
      }
      const target = event.target as HTMLElement;
      if (target.closest(interactiveTarget)) {
        return;
      }
      if (enabledRef.current && isRangeClick(event)) {
        event.preventDefault();
        event.stopPropagation();
        selectRange(item);
        return;
      }
      const selectionClick = enabledRef.current
        && (selectedRef.current.size > 0 || event.ctrlKey || event.metaKey);
      if (selectionClick) {
        event.preventDefault();
        event.stopPropagation();
        toggleItemSelection(item);
        return;
      }
      if (target.closest('a[href]')) return;
      openItem(item);
    },
  });

  useEffect(() => {
    const clearSelectionFromBackground = (event: globalThis.MouseEvent) => {
      if (selectedRef.current.size === 0 && anchorRef.current == null) return;
      const target = event.target as HTMLElement;
      // Header controls are outside item rows, but are not background clicks.
      if (target.closest('[data-context-item="true"], .select-all-checkbox, .select-all-label, dialog, .toolbar, .floating-page-actions, .transfer-buffer-panel, .toast-region, .context-menu')) {
        return;
      }
      clearSelection();
    };
    document.addEventListener('click', clearSelectionFromBackground);
    return () => document.removeEventListener('click', clearSelectionFromBackground);
  }, []);

  return {
    selected,
    selectedRef,
    setSelected,
    selectedItems,
    selectItem,
    selectAll,
    clearSelection,
    itemInteractionProps,
  };
}

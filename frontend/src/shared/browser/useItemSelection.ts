import { useEffect, useMemo, useRef, useState } from 'react';

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

  useEffect(() => setSelected(new Set()), [locationKey]);

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
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const cancelLongPress = () => {
    if (longPressRef.current.timer != null) window.clearTimeout(longPressRef.current.timer);
    longPressRef.current.timer = null;
    longPressRef.current.pointerId = null;
    longPressRef.current.item = null;
  };

  const itemInteractionProps = (item: T) => ({
    onPointerDown: (event: React.PointerEvent<HTMLElement>) => {
      if (!enabledRef.current
          || event.button !== 0
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
    onClickCapture: (event: React.MouseEvent<HTMLElement>) => {
      if (longPressRef.current.suppressClick) {
        event.preventDefault();
        event.stopPropagation();
        longPressRef.current.suppressClick = false;
        return;
      }
      const target = event.target as HTMLElement;
      if (target.closest('input, button, label, select, textarea, summary, .table-actions, .action-icon')) {
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
      if (selectedRef.current.size === 0) return;
      const target = event.target as HTMLElement;
      // Header controls are outside item rows, but are not background clicks.
      if (target.closest('[data-context-item="true"], .select-all-checkbox, .select-all-label, dialog, .toolbar, .transfer-buffer-panel, .toast-region, .context-menu')) {
        return;
      }
      setSelected(new Set());
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
    itemInteractionProps,
  };
}

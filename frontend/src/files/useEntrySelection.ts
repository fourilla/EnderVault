import { useEffect, useMemo, useRef, useState } from 'react';
import type { BrowserEntry } from './types';

export function useEntrySelection(
  selectableEntries: BrowserEntry[],
  selectionEnabled: boolean,
  browse: (path: string) => void,
  locationKey: string,
) {
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const selectedRef = useRef(selected);
  const selectionEnabledRef = useRef(selectionEnabled);
  const longPressRef = useRef<{
    timer: number | null;
    pointerId: number | null;
    startX: number;
    startY: number;
    entry: BrowserEntry | null;
    suppressClick: boolean;
  }>({ timer: null, pointerId: null, startX: 0, startY: 0, entry: null, suppressClick: false });

  selectedRef.current = selected;
  selectionEnabledRef.current = selectionEnabled;

  useEffect(() => setSelected(new Set()), [locationKey]);

  const selectedEntries = useMemo(
    () => selectableEntries.filter((entry) => selected.has(entry.path)),
    [selectableEntries, selected],
  );

  const selectEntry = (entry: BrowserEntry, checked: boolean) => {
    setSelected((current) => {
      const next = new Set(current);
      if (checked) next.add(entry.path);
      else next.delete(entry.path);
      return next;
    });
  };

  const toggleEntrySelection = (entry: BrowserEntry) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(entry.path)) next.delete(entry.path);
      else next.add(entry.path);
      return next;
    });
  };

  const openEntry = (entry: BrowserEntry) => {
    if (entry.type === 'directory') browse(entry.path);
    else window.location.assign(entry.detailUrl);
  };

  const cancelLongPress = () => {
    if (longPressRef.current.timer != null) window.clearTimeout(longPressRef.current.timer);
    longPressRef.current.timer = null;
    longPressRef.current.pointerId = null;
    longPressRef.current.entry = null;
  };

  const itemInteractionProps = (entry: BrowserEntry) => ({
    onPointerDown: (event: React.PointerEvent<HTMLElement>) => {
      if (!selectionEnabledRef.current
          || event.button !== 0
          || (event.target as HTMLElement).closest('input, button, label, .table-actions, .action-icon')) {
        return;
      }
      cancelLongPress();
      longPressRef.current.pointerId = event.pointerId;
      longPressRef.current.startX = event.clientX;
      longPressRef.current.startY = event.clientY;
      longPressRef.current.entry = entry;
      longPressRef.current.timer = window.setTimeout(() => {
        longPressRef.current.suppressClick = true;
        toggleEntrySelection(entry);
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
      const selectionClick = selectionEnabledRef.current
        && (selectedRef.current.size > 0 || event.ctrlKey || event.metaKey);
      if (selectionClick) {
        event.preventDefault();
        event.stopPropagation();
        toggleEntrySelection(entry);
        return;
      }
      if (target.closest('a[href]')) return;
      openEntry(entry);
    },
  });

  useEffect(() => {
    const clearSelectionFromBackground = (event: globalThis.MouseEvent) => {
      if (selectedRef.current.size === 0) return;
      const target = event.target as HTMLElement;
      if (target.closest('[data-context-item="true"], .toolbar, .transfer-buffer-panel, .upload-activity, .toast-region, .context-menu')) {
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
    selectedEntries,
    selectEntry,
    itemInteractionProps,
  };
}

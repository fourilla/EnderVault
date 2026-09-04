export type BrowserMenuItem<T> = T & { element: HTMLElement };

export interface BrowserMenuContext<T> {
  mode: 'single' | 'selection' | 'background';
  item: BrowserMenuItem<T> | null;
  items: BrowserMenuItem<T>[];
  event: MouseEvent;
}

export interface BrowserMenuAction<T> {
  id: string;
  group: string;
  label: string | ((context: BrowserMenuContext<T>) => string);
  icon: string | ((context: BrowserMenuContext<T>) => string);
  danger?: boolean;
  visible?: (context: BrowserMenuContext<T>) => boolean;
  run: (context: BrowserMenuContext<T>) => unknown;
}

export function browserMenuContext<T>({
  event, workspace, entries, itemKey, keyAttribute, selected, clearSelection,
}: {
  event: MouseEvent;
  workspace: Element;
  entries: T[];
  itemKey: (entry: T) => string;
  keyAttribute: string;
  selected: Set<string>;
  clearSelection: () => void;
}): BrowserMenuContext<T> | null {
  const target = event.target as Element | null;
  if (!target?.closest || !workspace.contains(target)
      || target.closest('input, textarea, select, [contenteditable="true"], dialog, .context-menu, .sticky-note-layer')) {
    return null;
  }
  const element = target.closest<HTMLElement>('[data-context-item="true"]');
  if (element) {
    const entry = entries.find((candidate) => itemKey(candidate) === element.getAttribute(keyAttribute));
    if (!entry) return null;
    const item = { ...entry, element };
    const targetSelected = selected.has(itemKey(entry));
    if (!targetSelected && selected.size > 0) clearSelection();
    const elements = new Map(Array.from(workspace.querySelectorAll<HTMLElement>('[data-context-item="true"]'))
      .map((candidate) => [candidate.getAttribute(keyAttribute), candidate]));
    const items = targetSelected ? entries.flatMap((candidate) => {
      const key = itemKey(candidate);
      const row = elements.get(key);
      return selected.has(key) && row ? [{ ...candidate, element: row }] : [];
    }) : [];
    const useSelection = items.length > 1;
    return { mode: useSelection ? 'selection' : 'single', item,
      items: useSelection ? items : [item], event };
  }
  if (target.closest('a, button, label, summary, iframe, object, embed, video, audio, canvas')) return null;
  return { mode: 'background', item: null, items: [], event };
}

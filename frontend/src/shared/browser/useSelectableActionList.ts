import { useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useItemSelection } from './useItemSelection';
import { useListItemActions } from './ListItemActions';
import { useBrowserContextMenu } from './useBrowserContextMenu';
import { useSelectionShortcuts } from './useSelectionShortcuts';
import { useLocationGuard } from './ListingHistoryContext';
import { listItemMenuActions, type ListItemAction } from './list-item-actions';

const keepRowClick = () => {};
const notBlocked = () => false;

export function useSelectableActionList<T>({ items, enabled, contextKey, itemKey, itemIdentity, definitions,
  reload, removeApplied, deleteActionId, menuId, pageScope, keyAttribute, itemLabel, blocked = notBlocked,
}: {
  items: T[]; enabled: boolean; contextKey: string; itemKey: (item: T) => string; itemIdentity: (item: T) => string;
  definitions: readonly ListItemAction<T>[]; reload: () => void; removeApplied: (ids: ReadonlySet<string>) => void;
  deleteActionId: string; menuId: string; pageScope: string; keyAttribute: string; itemLabel: string;
  blocked?: () => boolean;
}) {
  const location = useLocation();
  const listRef = useRef<HTMLElement>(null);
  const isCurrent = useLocationGuard();
  const [feedback, setFeedback] = useState<{ visit: string; failures: Map<string, string> } | null>(null);
  const selection = useItemSelection({ items, enabled, locationKey: contextKey, itemKey, openItem: keepRowClick });
  const actions = useListItemActions({ items, definitions, enabled, blocked, contextKey: location.key, isCurrent,
    itemKey, itemIdentity, itemLabel, reload, selectedIds: () => [...selection.selectedRef.current],
    bulkResolved: result => {
      const applied = new Set(result.results.filter(item => item.status === 'APPLIED').map(item => item.id));
      selection.setSelected(new Set([...selection.selectedRef.current].filter(id => !applied.has(id))));
      removeApplied(applied);
      setFeedback({ visit: location.key,
        failures: new Map(result.results.filter(item => item.status !== 'APPLIED').map(item => [item.id, item.message])) });
    } });
  const content = useMemo(() => ({ items, enabled, selected: selection.selected }), [items, enabled, selection.selected]);
  useBrowserContextMenu({ menuId, pageScope, entries: () => items, itemKey, keyAttribute,
    selectedRef: selection.selectedRef, setSelected: selection.setSelected,
    actions: () => listItemMenuActions(actions, itemKey), contentKey: content, contextKey,
    errorMessage: 'The list action failed.' });
  useSelectionShortcuts({ enabled: enabled && items.length > 0, contextKey,
    selectedCount: selection.selectedItems.length, selectAll: selection.selectAll,
    clearSelection: selection.clearSelection, scope: () => listRef.current,
    deleteSelection: async isCurrentSelection => {
      if (isCurrentSelection()) await actions.runSelected([...selection.selectedRef.current], deleteActionId);
    } });
  return { selection, actions, listRef, isCurrent,
    failures: feedback?.visit === location.key ? feedback.failures : new Map<string, string>() };
}

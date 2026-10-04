// Feedback-only fixtures isolate list interaction; behavioral tests use the real hooks.
export function linkListStub(id) {
  if (id.endsWith('/useItemSelection')) return { useItemSelection: () => ({
    selected: new Set(), selectedRef: { current: new Set() }, selectedItems: [], setSelected() {},
    selectAll() {}, clearSelection() {}, selectItem() {}, itemInteractionProps: () => ({}),
  }) };
  if (id.endsWith('/useSelectionShortcuts')) return { useSelectionShortcuts() {} };
  if (id.endsWith('/useBrowserContextMenu')) return { useBrowserContextMenu() {} };
  if (id.endsWith('/ListingHistoryContext')) return { useLocationGuard: () => () => true };
  if (id.endsWith('/ListItemActions')) return { ListItemActions: () => null,
    useListItemActions: () => ({ isBusy: () => false }) };
  if (id.endsWith('/list-item-actions')) return { listItemMenuActions: () => [] };
  if (id.endsWith('/share-list-actions')) return { shareListActions: [], shareItemKey: item => item.token };
  if (id.endsWith('/file-request-list-actions')) return { fileRequestListActions: () => [], fileRequestItemKey: item => item.id };
  if (id.endsWith('/SelectionHeader')) return { SelectionHeader: () => null };
  if (id.endsWith('/ListItemSelectionActions')) return { ListItemSelectionActions: () => null };
  return null;
}

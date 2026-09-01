import type { BrowserEntry } from './types';
import { useItemSelection } from './useItemSelection';

export function useEntrySelection(
  selectableEntries: BrowserEntry[],
  selectionEnabled: boolean,
  browse: (path: string) => void,
  locationKey: string,
  openFile: (detailUrl: string) => void,
) {
  const selection = useItemSelection({
    items: selectableEntries,
    enabled: selectionEnabled,
    locationKey,
    itemKey: (entry) => entry.path,
    openItem: (entry) => {
      if (entry.type === 'directory') browse(entry.path);
      else openFile(entry.detailUrl);
    },
  });
  return {
    selected: selection.selected,
    selectedRef: selection.selectedRef,
    setSelected: selection.setSelected,
    selectedEntries: selection.selectedItems,
    selectEntry: selection.selectItem,
    itemInteractionProps: selection.itemInteractionProps,
  };
}

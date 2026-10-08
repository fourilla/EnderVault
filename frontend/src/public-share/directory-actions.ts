import type { BrowserMenuAction } from '../shared/browser/browser-menu-context';
import type { SharedEntry, SharedListing } from './types';

export function sharedZipUrl(baseUrl: string, names: string[]): string {
  const queryIndex = baseUrl.indexOf('?');
  const path = queryIndex < 0 ? baseUrl : baseUrl.slice(0, queryIndex);
  const query = new URLSearchParams(queryIndex < 0 ? '' : baseUrl.slice(queryIndex + 1));
  query.delete('items');
  names.forEach(name => query.append('items', name));
  return `${path}?${query.toString()}`;
}

export function sharedDirectoryMenuActions({ listing, selectedCount, navigate, download, selectAll, clearSelection }: {
  listing: SharedListing;
  selectedCount: number;
  navigate: (url: string) => void;
  download: (url: string) => void;
  selectAll: () => void;
  clearSelection: () => void;
}): BrowserMenuAction<SharedEntry>[] {
  return [
    { id: 'open', group: 'entry', label: 'Open', icon: 'fas fa-folder-open',
      visible: ({ mode, item }) => mode === 'single' && Boolean(item?.openUrl),
      run: ({ item }) => { if (item) navigate(item.openUrl); } },
    { id: 'download', group: 'entry', label: 'Download', icon: 'fas fa-download',
      visible: ({ mode, item }) => mode === 'single' && !item?.directory && Boolean(item?.downloadUrl),
      run: ({ item }) => { if (item?.downloadUrl) download(item.downloadUrl); } },
    { id: 'download-directory', group: 'entry', label: 'Download ZIP', icon: 'fas fa-file-zipper',
      visible: ({ mode, item }) => mode === 'single' && Boolean(item?.directory),
      run: ({ item }) => { if (item) download(sharedZipUrl(listing.downloadZipUrl, [item.name])); } },
    { id: 'download-selected', group: 'selection', label: 'Download selected ZIP', icon: 'fas fa-download',
      visible: ({ mode }) => mode === 'selection',
      run: ({ items }) => download(sharedZipUrl(listing.downloadZipUrl, items.map(item => item.name))) },
    { id: 'select-all', group: 'selection', label: 'Select all', icon: 'fas fa-check-double',
      visible: ({ mode }) => mode === 'background' && listing.entries.length > 0,
      run: selectAll },
    { id: 'clear-selection', group: 'selection', label: 'Clear selection', icon: 'fas fa-xmark',
      visible: ({ mode }) => mode === 'selection' || mode === 'background' && selectedCount > 0,
      run: clearSelection },
  ];
}

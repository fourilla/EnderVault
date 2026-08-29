export type BookmarkEntryType = 'directory' | 'link';

export interface BookmarkEntry {
  id: string;
  type: BookmarkEntryType;
  title: string;
  url: string | null;
  external: boolean;
  favorite: boolean;
  metadataRefreshable: boolean;
  faviconAvailable: boolean;
  updatedAt: string;
  updatedLabel: string;
  openUrl: string;
  detailUrl: string;
  faviconUrl: string | null;
  primaryUrl: string;
  primaryNewTab: boolean;
}

export interface BookmarkPayload {
  breadcrumbs: Array<{ id: string | null; label: string }>;
  currentDirectoryId: string | null;
  directories: BookmarkEntry[];
  links: BookmarkEntry[];
  search: { query: string; performed: boolean };
  metadataFetchEnabled: boolean;
  linkClickAction: 'detail' | 'open';
  totalItems: number;
}

export interface BookmarkHistoryState {
  surface: 'bookmarks';
  version: 1;
  directoryId: string;
  query: string;
  scrollTop: number;
}

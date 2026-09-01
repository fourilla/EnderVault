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

export interface BookmarkDetailPayload {
  id: string;
  type: BookmarkEntryType;
  typeLabel: string;
  title: string;
  url: string;
  note: string;
  directory: boolean;
  link: boolean;
  external: boolean;
  favorite: boolean;
  metadataRefreshable: boolean;
  titleSource: string;
  metadataFetchAttempted: boolean;
  metadataFetchStatus: string;
  metadataFetchedLabel: string;
  faviconAvailable: boolean;
  faviconContentType: string;
  faviconUrl: string | null;
  iconClass: string;
  createdLabel: string;
  updatedLabel: string;
  lastOpenedLabel: string;
  parentId: string | null;
  parentLabel: string;
  parentUrl: string;
  openUrl: string | null;
  directoryUrl: string | null;
}

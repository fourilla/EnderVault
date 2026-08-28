export type BrowserMode = 'browse' | 'search';
export type BrowserView = 'table' | 'grid';
export type BrowserSort = 'name' | 'size' | 'modified' | 'type';
export type SortDirection = 'asc' | 'desc';
export type HiddenMode = 'hide' | 'show';

export interface BrowserEntry {
  name: string;
  path: string;
  parentPath: string;
  type: 'directory' | 'file';
  typeLabel: string;
  extensionLabel: string;
  size: number;
  sizeLabel: string;
  modifiedAt: string;
  modifiedLabel: string;
  accessedAt?: string | null;
  accessedLabel?: string | null;
  mediaType: string;
  previewable: boolean;
  streamable: boolean;
  hidden: boolean;
  favorite: boolean;
  image: boolean;
  video: boolean;
  pdf: boolean;
  comic: boolean;
  detailUrl: string;
  downloadUrl: string | null;
  previewUrl: string | null;
  thumbnailUrl: string | null;
}

export interface BrowserPayload {
  mode: BrowserMode;
  path: string;
  parentPath: string | null;
  breadcrumbs: Array<{ label: string; path: string }>;
  directories: BrowserEntry[];
  entries: BrowserEntry[];
  page: {
    number: number;
    size: number;
    totalItems: number;
    totalPages: number;
    startItem: number;
    endItem: number;
    hasPrevious: boolean;
    hasNext: boolean;
  };
  preferences: {
    view: BrowserView;
    sort: BrowserSort;
    direction: SortDirection;
    hidden: HiddenMode;
    pageSize: number;
    pageSizeOptions: number[];
  };
  search: {
    query: string;
    performed: boolean;
  };
}

export interface BrowserHistoryState {
  version: 1;
  mode: BrowserMode;
  path: string;
  query: string;
  page: number;
  view?: BrowserView;
  sort?: BrowserSort;
  direction?: SortDirection;
  hidden?: HiddenMode;
  pageSize?: number;
  scrollTop: number;
}

export interface TransferBufferPayload {
  active: boolean;
  count: number;
  items: Array<{
    path: string;
    name: string;
    iconClass: string;
  }>;
}

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

export interface BrowserPage {
  number: number;
  size: number;
  totalItems: number;
  totalPages: number;
  startItem: number;
  endItem: number;
  hasPrevious: boolean;
  hasNext: boolean;
}

export interface TransferBufferPayload {
  active: boolean;
  count: number;
  items: Array<{ path: string; name: string; iconClass: string }>;
}

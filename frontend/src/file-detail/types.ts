import type { TransferBufferPayload } from '../files/types';

export interface FileDetailPayload {
  detail: {
    name: string;
    path: string;
    parentPath: string;
    directory: boolean;
    size: number;
    sizeLabel: string;
    childCount: number;
    createdLabel: string;
    modifiedLabel: string;
    accessedLabel: string;
    mediaType: string;
    extension: string;
    previewable: boolean;
    streamable: boolean;
    hidden: boolean;
  };
  tool: {
    type: 'directory' | 'image' | 'video' | 'audio' | 'text' | 'pdf' | 'comic' | 'archive' | 'hex';
    label: string;
    inlinePreview: boolean;
    previewPage: boolean;
    editable: boolean;
    markdown: boolean;
  };
  urls: {
    parentDirectory: string;
    openDirectory: string | null;
    download: string;
    downloadZip: string | null;
    cardMedia: string | null;
    previewContent: string | null;
    actions: Array<{
      id: string;
      label: string;
      icon: string;
      href: string;
      newTab: boolean;
    }>;
  };
  text: TextContentPayload | null;
  comic: ComicPayload | null;
  archive: ArchivePayload | null;
  shares: SharePayload[];
  favorite: boolean;
  transferBuffer: TransferBufferPayload;
}

export interface TextContentPayload {
  loaded: boolean;
  editable: boolean;
  manualLoadAvailable: boolean;
  content: string;
  message: string;
  autoLoadSizeLabel: string;
  manualLoadSizeLabel: string;
  sizeLabel: string;
}

export interface SharePayload {
  token: string;
  url: string;
  directDownloadUrl: string | null;
  createdLabel: string;
  expiresLabel: string;
  statusLabel: string;
  statusClass: string;
  active: boolean;
}

export interface ComicPayload {
  manifest: {
    pageCount: number;
    pages: Array<{
      index: number;
      entryName: string;
      displayName: string;
      mediaType: string;
      size: number;
      number: number;
    }>;
    metadata: {
      present: boolean;
      truncated: boolean;
      rawText: string;
      entries: Array<{ name: string; value: string }>;
      hasEntries: boolean;
    };
    empty: boolean;
  };
  pageIndex: number;
  pageNumber: number;
  previousPageNumber: number;
  nextPageNumber: number;
  pageUrl: string;
}

export interface ArchivePayload {
  suggestedName: string;
  destinationPath: string;
  entriesUrl: string;
}

export interface ArchiveEntryPayload {
  name: string;
  path: string;
  directory: boolean;
  sizeLabel: string;
}

export interface ArchiveEntriesPayload {
  parentPath: string;
  format: string;
  fileCount: number;
  directoryCount: number;
  totalSizeLabel: string;
  browsable: boolean;
  extractable: boolean;
  message: string;
  entries: ArchiveEntryPayload[];
}

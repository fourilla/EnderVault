import type {
  BrowserEntry,
  BrowserPage,
  BrowserSort,
  BrowserView,
  HiddenMode,
  SortDirection,
} from '../shared/browser/types';

export type {
  TransferBufferPayload,
  BrowserEntry,
  BrowserPage,
  BrowserSort,
  BrowserView,
  HiddenMode,
  SortDirection,
} from '../shared/browser/types';

export type BrowserMode = 'browse' | 'search';

export interface BrowserPayload {
  mode: BrowserMode;
  path: string;
  parentPath: string | null;
  breadcrumbs: Array<{ label: string; path: string }>;
  directories: BrowserEntry[];
  entries: BrowserEntry[];
  page: BrowserPage;
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

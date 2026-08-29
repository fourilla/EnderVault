import type {
  BrowserEntry,
  BrowserPage,
  BrowserView,
  HiddenMode,
  SortDirection,
} from '../shared/browser/types';

export type RecentSort = 'recent' | 'name' | 'size' | 'modified' | 'type';

export interface RecentPayload {
  directories: BrowserEntry[];
  entries: BrowserEntry[];
  page: BrowserPage;
  preferences: {
    view: BrowserView;
    sort: RecentSort;
    direction: SortDirection;
    hidden: HiddenMode;
    pageSize: number;
    pageSizeOptions: number[];
  };
  search: {
    query: string;
    performed: boolean;
  };
  totalItems: number;
}

export interface RecentHistoryState {
  surface: 'recent';
  version: 1;
  query: string;
  page: number;
  view?: BrowserView;
  sort?: RecentSort;
  direction?: SortDirection;
  hidden?: HiddenMode;
  pageSize?: number;
  scrollTop: number;
}

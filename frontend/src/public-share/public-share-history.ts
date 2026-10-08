import type { ListingHistoryConfig, ListingState } from '../shared/browser/listing-history';

export interface SharedVisitState extends ListingState {
  surface: 'public-share';
  selectedNames: string[];
}

export const defaultSharedVisit = (): SharedVisitState => ({
  surface: 'public-share', version: 1, scrollTop: 0, selectedNames: [],
});

export function parseSharedVisit(candidate: unknown): SharedVisitState | null {
  if (!candidate || typeof candidate !== 'object') return null;
  const raw = candidate as Partial<SharedVisitState>;
  if (raw.surface !== 'public-share' || raw.version !== 1) return null;
  return {
    ...defaultSharedVisit(),
    scrollTop: typeof raw.scrollTop === 'number' && Number.isFinite(raw.scrollTop)
      ? Math.max(0, Math.round(raw.scrollTop)) : 0,
    selectedNames: Array.isArray(raw.selectedNames)
      ? [...new Set(raw.selectedNames.filter((name): name is string => typeof name === 'string'))] : [],
  };
}

export function sharedVisitConfig(pathname: string): ListingHistoryConfig<SharedVisitState> {
  return { pathname, parse: parseSharedVisit, fromSearch: defaultSharedVisit };
}

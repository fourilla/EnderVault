import type { BrowserHistoryState, BrowserPayload } from './types';

export function BrowserPagination({
  page,
  effectiveState,
  navigate,
  jumpToPage,
}: {
  page: BrowserPayload['page'];
  effectiveState: () => BrowserHistoryState;
  navigate: (state: BrowserHistoryState) => void;
  jumpToPage: () => Promise<void>;
}) {
  if (page.totalPages <= 1) return null;
  const go = (number: number) => navigate({ ...effectiveState(), page: number, scrollTop: 0 });
  return (
    <nav className="pagination" aria-label="File pages">
      <button className="ghost pagination-link" type="button" disabled={!page.hasPrevious} onClick={() => go(1)}>
        First
      </button>
      <button className="ghost pagination-link" type="button" disabled={!page.hasPrevious}
        onClick={() => go(page.number - 1)}>
        Previous
      </button>
      <button className="page-status files-page-status" type="button" onClick={() => void jumpToPage()}>
        Page {page.number} of {page.totalPages}
      </button>
      <button className="ghost pagination-link" type="button" disabled={!page.hasNext}
        onClick={() => go(page.number + 1)}>
        Next
      </button>
      <button className="ghost pagination-link" type="button" disabled={!page.hasNext}
        onClick={() => go(page.totalPages)}>
        Last
      </button>
    </nav>
  );
}

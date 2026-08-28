import type { BrowserPayload } from './types';

export function BrowserPagination({
  page,
  onPageChange,
  jumpToPage,
  ariaLabel = 'File pages',
}: {
  page: BrowserPayload['page'];
  onPageChange: (page: number) => void;
  jumpToPage: () => Promise<void>;
  ariaLabel?: string;
}) {
  if (page.totalPages <= 1) return null;
  return (
    <nav className="pagination" aria-label={ariaLabel}>
      <button className="ghost pagination-link" type="button" disabled={!page.hasPrevious} onClick={() => onPageChange(1)}>
        First
      </button>
      <button className="ghost pagination-link" type="button" disabled={!page.hasPrevious}
        onClick={() => onPageChange(page.number - 1)}>
        Previous
      </button>
      <button className="page-status files-page-status" type="button" onClick={() => void jumpToPage()}>
        Page {page.number} of {page.totalPages}
      </button>
      <button className="ghost pagination-link" type="button" disabled={!page.hasNext}
        onClick={() => onPageChange(page.number + 1)}>
        Next
      </button>
      <button className="ghost pagination-link" type="button" disabled={!page.hasNext}
        onClick={() => onPageChange(page.totalPages)}>
        Last
      </button>
    </nav>
  );
}

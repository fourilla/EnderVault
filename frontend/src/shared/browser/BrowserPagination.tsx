export interface PaginationPage {
  number: number;
  totalPages: number;
}

export function BrowserPagination({
  page,
  onPageChange,
  ariaLabel = 'File pages',
  disabled = false,
}: {
  page: PaginationPage;
  onPageChange: (page: number) => void;
  ariaLabel?: string;
  disabled?: boolean;
}) {
  if (page.totalPages <= 1) return null;

  const jumpToPage = async () => {
    const requested = await window.EnderVault?.askTextInput({
      title: 'Go to page',
      message: `Enter a page from 1 to ${page.totalPages}. Larger values open the last page.`,
      label: 'Page',
      initialValue: String(page.number),
      confirmLabel: 'Go',
    });
    if (requested == null) return;

    const parsed = Number.parseInt(requested, 10);
    const target = Number.isFinite(parsed) ? parsed : page.number;
    onPageChange(Math.max(1, Math.min(page.totalPages, target)));
  };

  return (
    <nav className="pagination" aria-label={ariaLabel}>
      <button className="ghost pagination-link" type="button" disabled={disabled || page.number <= 1}
        onClick={() => onPageChange(1)}>
        First
      </button>
      <button className="ghost pagination-link" type="button" disabled={disabled || page.number <= 1}
        onClick={() => onPageChange(page.number - 1)}>
        Previous
      </button>
      <button className="page-status is-page-jump-enabled" type="button" disabled={disabled}
        title="Go to page" aria-label={`Page ${page.number} of ${page.totalPages}. Go to page.`}
        onClick={() => void jumpToPage()}>
        Page {page.number} of {page.totalPages}
      </button>
      <button className="ghost pagination-link" type="button" disabled={disabled || page.number >= page.totalPages}
        onClick={() => onPageChange(page.number + 1)}>
        Next
      </button>
      <button className="ghost pagination-link" type="button" disabled={disabled || page.number >= page.totalPages}
        onClick={() => onPageChange(page.totalPages)}>
        Last
      </button>
    </nav>
  );
}

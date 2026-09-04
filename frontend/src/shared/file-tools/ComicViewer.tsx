import { useEffect, useState } from 'react';
import type { ComicManifest } from './comic-types';

export function ComicViewer({ name, manifest, pageUrl, page, onPageChange }: {
  name: string;
  manifest: ComicManifest;
  pageUrl: string;
  page: number;
  onPageChange: (page: number) => void;
}) {
  const [fullscreen, setFullscreen] = useState(false);
  const [failedSource, setFailedSource] = useState('');
  const total = manifest.pageCount;
  const pageSource = (index: number) => `${pageUrl}${pageUrl.includes('?') ? '&' : '?'}page=${index}`;
  const source = pageSource(page);

  useEffect(() => {
    document.body.classList.toggle('is-comic-viewer-fullscreen', fullscreen);
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setFullscreen(false);
    };
    if (fullscreen) document.addEventListener('keydown', escape);
    return () => {
      document.body.classList.remove('is-comic-viewer-fullscreen');
      document.removeEventListener('keydown', escape);
    };
  }, [fullscreen]);
  useEffect(() => {
    setFailedSource('');
    if (total === 0) return;
    [page - 1, page + 1].filter((index) => index >= 0 && index < total).forEach((index) => {
      const preload = new Image();
      preload.src = pageSource(index);
    });
  }, [pageUrl, page, total]);

  const showPage = (next: number) => {
    if (!Number.isFinite(next)) return;
    const normalized = Math.max(0, Math.min(Math.max(0, total - 1), Math.trunc(next)));
    if (normalized === page) return;
    onPageChange(normalized);
  };
  const nav = (name: string, target: number, iconClass: string, disabled: boolean) => (
    <button className={'ghost icon-button action-icon' + (disabled ? ' is-disabled' : '')}
      type="button" disabled={disabled} title={name} aria-label={name} onClick={() => showPage(target)}>
      <i className={iconClass} aria-hidden="true" />
    </button>
  );
  return (
    <div className={'comic-viewer' + (fullscreen ? ' is-comic-fullscreen' : '')}>
      <div className="comic-toolbar">
        <span className="comic-status">{total ? `Page ${page + 1} / ${total}` : 'No image pages found.'}</span>
        {total > 0 && <div className="comic-actions">
          {nav('First page', 0, 'fas fa-angles-left', page === 0)}
          {nav('Previous page', page - 1, 'fas fa-chevron-left', page === 0)}
          <form className="comic-page-form" onSubmit={(event) => {
            event.preventDefault();
            const value = Number(new FormData(event.currentTarget).get('comicPage'));
            if (Number.isFinite(value)) showPage(value - 1);
          }}>
            <input className="comic-page-input" type="number" name="comicPage" min="1" max={total}
              value={page + 1} onChange={(event) => showPage(Number(event.target.value) - 1)}
              aria-label="Comic page number" />
          </form>
          {nav('Next page', page + 1, 'fas fa-chevron-right', page >= total - 1)}
          {nav('Last page', total - 1, 'fas fa-angles-right', page >= total - 1)}
          <button className={'ghost icon-button action-icon' + (fullscreen ? ' is-active' : '')}
            type="button" title={fullscreen ? 'Exit fullscreen viewer' : 'Toggle fullscreen viewer'}
            aria-label={fullscreen ? 'Exit fullscreen viewer' : 'Toggle fullscreen viewer'}
            aria-pressed={fullscreen} onClick={() => setFullscreen((value) => !value)}>
            <i className={fullscreen ? 'fas fa-compress' : 'fas fa-expand'} aria-hidden="true" />
          </button>
        </div>}
      </div>
      {total > 0 && <div className="comic-stage">
        {failedSource === source
          ? <p className="tool-message" role="status">This comic page could not be loaded.</p>
          : <img key={source} className="comic-page-image" src={source}
              onError={() => setFailedSource(source)} alt={`${name} page ${page + 1}`} />}
      </div>}
      {manifest.metadata.present && <aside className="comic-metadata">
        <h3>info.txt</h3>
        {manifest.metadata.entries.length > 0 && <dl className="details compact-details">
          {manifest.metadata.entries.map((entry) => <div key={entry.name}>
            <dt>{entry.name}</dt><dd>{entry.value}</dd>
          </div>)}
        </dl>}
        {manifest.metadata.truncated && <p className="muted">info.txt was truncated for preview.</p>}
        <details className="comic-raw-metadata"><summary>Raw metadata</summary>
          <pre>{manifest.metadata.rawText}</pre></details>
      </aside>}
    </div>
  );
}

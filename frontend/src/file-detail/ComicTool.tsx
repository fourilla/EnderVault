import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { comicPageIndex } from './comic-page';
import { icon } from '../shared/browser/BrowserEntries';
import type { FileDetailPayload } from './types';

export function ComicTool({ payload }: { payload: FileDetailPayload }) {
  const location = useLocation();
  const navigate = useNavigate();
  const comic = payload.comic;
  const [fullscreen, setFullscreen] = useState(false);
  const total = comic?.manifest.pageCount || 0;
  const page = comicPageIndex(location.search, total, comic?.pageIndex || 0);

  useEffect(() => {
    document.body.classList.toggle('is-comic-viewer-fullscreen', fullscreen);
    return () => document.body.classList.remove('is-comic-viewer-fullscreen');
  }, [fullscreen]);
  useEffect(() => {
    if (!comic || total === 0) return;
    [page - 1, page + 1].filter((index) => index >= 0 && index < total).forEach((index) => {
      const preload = new Image();
      preload.src = `${comic.pageUrl}&page=${index}`;
    });
  }, [comic, page, total]);

  if (!comic) return null;
  const showPage = (next: number) => {
    if (!Number.isFinite(next)) return;
    const normalized = Math.max(0, Math.min(total - 1, next));
    if (normalized === page) return;
    const params = new URLSearchParams(location.search);
    params.set('comicPage', String(normalized + 1));
    void navigate({ pathname: location.pathname, search: '?' + params, hash: location.hash },
      { replace: true, state: location.state, preventScrollReset: true });
  };
  const nav = (name: string, target: number, iconClass: string, disabled: boolean) => (
    <button className={'ghost icon-button action-icon' + (disabled ? ' is-disabled' : '')}
      type="button" disabled={disabled} title={name} aria-label={name} onClick={() => showPage(target)}>
      {icon(iconClass)}
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
            {icon(fullscreen ? 'fas fa-compress' : 'fas fa-expand')}
          </button>
        </div>}
      </div>
      {total > 0 && <div className="comic-stage">
        <img className="comic-page-image" src={`${comic.pageUrl}&page=${page}`}
          alt={`${payload.detail.name} page ${page + 1}`} />
      </div>}
      {comic.manifest.metadata.present && <aside className="comic-metadata">
        <h3>info.txt</h3>
        {comic.manifest.metadata.entries.length > 0 && <dl className="details compact-details">
          {comic.manifest.metadata.entries.map((entry) => <div key={entry.name}>
            <dt>{entry.name}</dt><dd>{entry.value}</dd>
          </div>)}
        </dl>}
        {comic.manifest.metadata.truncated && <p className="muted">info.txt was truncated for preview.</p>}
        <details className="comic-raw-metadata"><summary>Raw metadata</summary>
          <pre>{comic.manifest.metadata.rawText}</pre></details>
      </aside>}
    </div>
  );
}

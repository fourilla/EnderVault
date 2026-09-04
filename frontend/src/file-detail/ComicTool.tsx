import { useLocation, useNavigate } from 'react-router-dom';
import { ComicViewer } from '../shared/file-tools/ComicViewer';
import { comicPageIndex } from './comic-page';
import type { FileDetailPayload } from './types';

export function ComicTool({ payload }: { payload: FileDetailPayload }) {
  const location = useLocation();
  const navigate = useNavigate();
  const comic = payload.comic;
  if (!comic) return null;
  const page = comicPageIndex(location.search, comic.manifest.pageCount, comic.pageIndex);
  return <ComicViewer name={payload.detail.name} manifest={comic.manifest} pageUrl={comic.pageUrl} page={page}
    onPageChange={(next) => {
      const params = new URLSearchParams(location.search);
      params.set('comicPage', String(next + 1));
      void navigate({ pathname: location.pathname, search: '?' + params, hash: location.hash },
        { replace: true, state: location.state, preventScrollReset: true });
    }} />;
}

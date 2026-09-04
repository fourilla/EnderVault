import { useEffect, useState } from 'react';
import { ComicViewer } from '../shared/file-tools/ComicViewer';
import type { ComicManifest } from '../shared/file-tools/comic-types';

interface SharedComicPayload extends ComicManifest {
  name: string;
  pageUrl: string;
}

export function SharedComicViewer({ manifestUrl }: { manifestUrl: string }) {
  const [comic, setComic] = useState<SharedComicPayload | null>(null);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setComic(null);
    setError('');
    setPage(0);
    void (async () => {
      try {
        const response = await fetch(manifestUrl, {
          headers: { Accept: 'application/json' }, credentials: 'omit', mode: 'same-origin',
          signal: controller.signal,
        });
        if (!response.headers.get('content-type')?.includes('application/json')) {
          throw new Error('Comic preview could not be loaded.');
        }
        const body = await response.json();
        if (!response.ok) throw new Error(body.notification?.message || 'Comic preview could not be loaded.');
        if (active) setComic(body);
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : 'Comic preview could not be loaded.');
      }
    })();
    return () => { active = false; controller.abort(); };
  }, [manifestUrl]);

  if (error) return <p className="tool-message" role="status">{error}</p>;
  if (!comic) return <p className="tool-message" role="status">Loading comic...</p>;
  return <ComicViewer name={comic.name} manifest={comic} pageUrl={comic.pageUrl} page={page} onPageChange={setPage} />;
}

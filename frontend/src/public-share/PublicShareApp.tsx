import { useCallback, useEffect, useMemo, useRef } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useListingHistory, useListingSnapshot } from '../shared/browser/ListingHistoryContext';
import { useNavigationScroll } from '../shared/browser/useNavigationScroll';
import { SharedDirectoryPage } from './SharedDirectoryPage';
import { SharedFilePage } from './SharedFilePage';
import { sharedVisitConfig } from './public-share-history';
import { PublicShareError, requestSharedView, sharedViewUrl } from './public-share-request';
import type { SharedBootstrap, SharedView } from './types';

type Snapshot = { data: SharedView; error?: never } | { error: string; data?: never };

export function PublicShareApp({ bootstrap }: { bootstrap: SharedBootstrap }) {
  const location = useLocation();
  const { token } = useParams();
  const config = useMemo(() => sharedVisitConfig(location.pathname), [location.pathname]);
  // Public path/item queries are the address of the shared item and must stay in the URL.
  const visit = useListingHistory(config, { preserveSearch: true });
  const queryVisit = useRef({ key: visit.key, revision: 0 });
  if (queryVisit.current.key !== visit.key) {
    queryVisit.current = { key: visit.key, revision: queryVisit.current.revision + 1 };
  }
  // Returning before another request finishes must still revalidate the old visit before displaying it.
  const queryKey = `${visit.key}:${queryVisit.current.revision}`;
  const [snapshot, setSnapshot] = useListingSnapshot<Snapshot>(queryKey);
  const url = sharedViewUrl(bootstrap, location.pathname, location.search);
  const validToken = token === bootstrap.token;

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    if (!validToken) {
      setSnapshot({ error: 'This shared item is unavailable.' });
      return () => { active = false; controller.abort(); };
    }
    void requestSharedView(url, controller.signal).then((data) => {
      if (active) setSnapshot({ data });
    }).catch((error: unknown) => {
      if (active) setSnapshot({ error: error instanceof PublicShareError
        ? error.message : 'The shared item could not be loaded. Try again.' });
    });
    return () => { active = false; controller.abort(); };
  }, [url, queryKey, validToken, setSnapshot]);

  useNavigationScroll(snapshot, snapshot == null, visit.state.scrollTop, visit.key, visit.ready);
  const rememberSelection = useCallback((selectedNames: string[]) => {
    visit.remember({ ...visit.state, selectedNames });
  }, [visit.remember, visit.state]);

  useEffect(() => {
    document.title = snapshot?.data?.view === 'detail' ? snapshot.data.name : 'Shared Directory';
  }, [snapshot]);

  return <div className="public-share-app">
    <header className="topbar">
      <Link className="brand" to={bootstrap.rootUrl}>EnderVault Share</Link>
    </header>
    <main className="workspace public-share-main" aria-busy={snapshot == null}>
      {!snapshot && <p className="tool-message" role="status">Loading shared item...</p>}
      {snapshot?.error && <section className="shared-summary public-share-error" role="alert">
        <h1>Shared item unavailable</h1>
        <p>{snapshot.error}</p>
        <Link className="button-link" to={bootstrap.rootUrl}>Share root</Link>
      </section>}
      {snapshot?.data?.view === 'listing' && <SharedDirectoryPage key={visit.key}
        listing={snapshot.data} initialSelectedNames={visit.state.selectedNames}
        onSelectionChange={rememberSelection} />}
      {snapshot?.data?.view === 'detail' && <SharedFilePage key={visit.key} detail={snapshot.data} />}
    </main>
  </div>;
}

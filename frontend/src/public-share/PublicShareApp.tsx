import { useCallback, useEffect, useLayoutEffect, useMemo, type ReactNode } from 'react';
import { Link, useLoaderData, useLocation, useNavigation, useParams } from 'react-router-dom';
import { useListingHistory } from '../shared/browser/ListingHistoryContext';
import { useNavigationScroll } from '../shared/browser/useNavigationScroll';
import { LoadingState } from '../shared/layout/LoadingState';
import { SharedDirectoryPage } from './SharedDirectoryPage';
import { SharedFilePage } from './SharedFilePage';
import { sharedVisitConfig } from './public-share-history';
import { sharedRouteToken, type SharedViewSnapshot } from './public-share-router';
import type { SharedBootstrap } from './types';

function SharedShell({ bootstrap, busy, blocked = false, children }: {
  bootstrap: SharedBootstrap; busy: boolean; blocked?: boolean; children: ReactNode;
}) {
  return <div className="public-share-app" inert={blocked}>
    <header className="topbar">
      <Link className="brand" to={bootstrap.rootUrl}>EnderVault Share</Link>
    </header>
    <main className="workspace public-share-main" aria-busy={busy}>{children}</main>
  </div>;
}

export function PublicShareLoading({ bootstrap }: { bootstrap: SharedBootstrap }) {
  return <SharedShell bootstrap={bootstrap} busy>
    <LoadingState label="Loading shared item..." />
  </SharedShell>;
}

export function PublicShareApp({ bootstrap }: { bootstrap: SharedBootstrap }) {
  const location = useLocation();
  const { token } = useParams();
  const snapshot = useLoaderData<SharedViewSnapshot>();
  const navigation = useNavigation();
  const pending = navigation.state !== 'idle';
  const pendingPath = navigation.location?.pathname;
  const otherShare = token !== bootstrap.token || Boolean(pendingPath
    && sharedRouteToken(pendingPath) !== bootstrap.token);
  const config = useMemo(() => sharedVisitConfig(location.pathname), [location.pathname]);
  // Public path/item queries are the address of the shared item and must stay in the URL.
  const visit = useListingHistory(config, { preserveSearch: true });
  // Loader data and visit identity change together, after the new response is verified.
  useNavigationScroll(snapshot, false, visit.state.scrollTop, visit.key, visit.ready);
  const rememberSelection = useCallback((selectedNames: string[]) => {
    visit.remember({ ...visit.state, selectedNames });
  }, [visit.remember, visit.state]);

  useEffect(() => {
    document.title = snapshot?.data?.view === 'detail' ? snapshot.data.name : 'Shared Directory';
  }, [snapshot]);

  useLayoutEffect(() => {
    if (!pending) return;
    const blockViewerKeys = (event: KeyboardEvent) => {
      // Inert blocks element controls; viewers also have document-level shortcuts.
      if (event.ctrlKey || event.metaKey || event.altKey || event.key === 'Tab') return;
      event.preventDefault();
      event.stopPropagation();
    };
    document.addEventListener('keydown', blockViewerKeys, true);
    return () => document.removeEventListener('keydown', blockViewerKeys, true);
  }, [pending]);

  const error = otherShare ? 'This shared item is unavailable.' : snapshot.error;
  const stopPendingClick = (event: React.MouseEvent<HTMLElement>) => {
    event.preventDefault();
    event.stopPropagation();
  };
  return <>
    <SharedShell bootstrap={bootstrap} busy={pending} blocked={pending}>
      {error && <section className="shared-summary public-share-error" role="alert">
        <h1>Shared item unavailable</h1>
        <p>{error}</p>
        <Link className="button-link" to={bootstrap.rootUrl}>Share root</Link>
      </section>}
      {!otherShare && snapshot.data?.view === 'listing' && <SharedDirectoryPage key={visit.key}
        listing={snapshot.data} initialSelectedNames={visit.state.selectedNames}
        onSelectionChange={rememberSelection} disabled={pending} />}
      {!otherShare && snapshot.data?.view === 'detail' && <SharedFilePage key={visit.key} detail={snapshot.data} />}
    </SharedShell>
    {pending && <div className="public-share-pending-overlay" onClickCapture={stopPendingClick}
      onContextMenuCapture={stopPendingClick} onMouseDownCapture={stopPendingClick}>
      <LoadingState compact className="public-share-pending-status" label="Loading shared item..." />
    </div>}
  </>;
}

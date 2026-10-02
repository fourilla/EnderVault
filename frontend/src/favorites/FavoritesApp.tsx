import { LoadingState } from '../shared/layout/LoadingState';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { StableTable } from '../shared/browser/StableTable';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { moveFavorite, removeFavorite } from '../shared/api/favorite-api';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { loadFavorites } from './favorite-api';
import type { FavoriteEntry, FavoritesPayload } from './types';
import './favorites-app.css';

export function FavoritesApp() {
  const [payload, setPayload] = useState<FavoritesPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const [busyPath, setBusyPath] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadFavorites(controller.signal)
      .then(setPayload)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Favorites could not be loaded.');
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [refreshToken]);

  useEffect(() => {
    const refresh = () => setRefreshToken((value) => value + 1);
    document.addEventListener('endervault:favorites-changed', refresh);
    return () => document.removeEventListener('endervault:favorites-changed', refresh);
  }, []);

  const move = async (entry: FavoriteEntry, direction: 'up' | 'down') => {
    setBusyPath(entry.path);
    try {
      await moveFavorite(entry.path, direction);
    } catch (reason) {
      toastError(reason, 'Favorite order could not be updated.');
    } finally {
      setBusyPath('');
    }
  };

  const remove = async (entry: FavoriteEntry) => {
    setBusyPath(entry.path);
    try {
      await removeFavorite(entry.path);
    } catch (reason) {
      toastError(reason, 'Favorite could not be removed.');
    } finally {
      setBusyPath('');
    }
  };

  return (
    <>
      <section className="breadcrumb-panel" aria-label="Favorites heading">
        <div className="breadcrumb-main">
          <p className="breadcrumb-label">Pinned locations</p>
          <nav className="breadcrumbs"><Link className="current" to="/files/favorites">Favorites</Link></nav>
        </div>
      </section>

      <div className="page-feedback-layout">
      {error && <PageErrorPanel title="Favorites unavailable" message={error} stale={payload !== null}
        actions={<button type="button" className="icon-text-button" disabled={loading}
          onClick={() => setRefreshToken((value) => value + 1)}>
          {icon('fas fa-arrows-rotate')}<span>Retry</span>
        </button>} />}
      {loading && !payload && (
        <LoadingState label="Loading favorites..." />
      )}
      {payload && (
        <section className="dashboard-panel favorites-panel" aria-label="Favorite files and directories">
          <header className="section-heading">
            <h2>Favorite Items</h2>
            <p>{payload.items.length} item(s)</p>
          </header>
          <div className="table-wrap compact-table">
            <StableTable columns={['text', 'type', 'text', 'date', 'actions']} actionCount={3}>
              <thead>
                <tr><th>Name</th><th>Type</th><th>Target</th><th>Added</th><th>Actions</th></tr>
              </thead>
              <tbody>
                {payload.items.map((entry, index) => (
                  <tr key={entry.path} className={entry.hidden ? 'is-hidden-item' : undefined}>
                    <td>
                      <div className="table-item-label">
                      <a className="item-name" href={entry.openUrl}
                        target={entry.openInNewTab ? '_blank' : undefined}
                        rel={entry.openInNewTab ? 'noopener noreferrer' : undefined}
                        title={entry.targetLabel}>
                        <i className={`${entry.iconClass} item-icon`} aria-hidden="true" /><OverflowMarquee text={entry.name} />
                      </a>
                      {entry.hidden && <span className="status-badge expired hidden-badge">Hidden</span>}
                      </div>
                    </td>
                    <td>{entry.typeLabel}</td>
                    <td><span className="path-cell"><OverflowMarquee text={entry.targetLabel} /></span></td>
                    <td>{entry.createdLabel}</td>
                    <td>
                      <div className="table-actions">
                        <button className="ghost icon-button action-icon" type="button"
                          disabled={index === 0 || busyPath === entry.path}
                          title="Move up" aria-label="Move up" onClick={() => void move(entry, 'up')}>
                          {icon('fas fa-arrow-up')}
                        </button>
                        <button className="ghost icon-button action-icon" type="button"
                          disabled={index === payload.items.length - 1 || busyPath === entry.path}
                          title="Move down" aria-label="Move down" onClick={() => void move(entry, 'down')}>
                          {icon('fas fa-arrow-down')}
                        </button>
                        <button className="ghost icon-button action-icon" type="button"
                          disabled={busyPath === entry.path} title="Remove" aria-label="Remove"
                          onClick={() => void remove(entry)}>
                          {icon('fas fa-star-half-stroke')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {payload.items.length === 0 && (
                  <tr className="empty-row"><td colSpan={5} className="empty">No favorites yet.</td></tr>
                )}
              </tbody>
            </StableTable>
          </div>
        </section>
      )}
      </div>
    </>
  );
}

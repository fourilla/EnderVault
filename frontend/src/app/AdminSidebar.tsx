import { useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { useAdminApp } from './AdminAppContext';
import {
  isSpaNavigationUrl,
  navigationEntryAvailable,
  navigationFor,
} from './navigation';
import { ShellNavigationLink } from './ShellNavigationLink';
import type { AdminAppFavorite } from './types';

function SidebarFavorite({ favorite }: { favorite: AdminAppFavorite }) {
  const content = <>
    <i className={favorite.iconClass} aria-hidden="true" />
    <span>{favorite.name}</span>
  </>;
  const common = {
    className: favorite.hidden ? 'is-hidden-item' : undefined,
    'data-favorite-sidebar-path': favorite.path,
    'data-favorite-direct-open-url': favorite.directOpenUrl,
    'data-favorite-detail-url': favorite.detailUrl,
    'data-favorite-bookmark-link': favorite.path.startsWith('bookmark:'),
    title: favorite.path,
  };
  if (!favorite.openInNewTab && isSpaNavigationUrl(favorite.openUrl)) {
    return <Link {...common} to={favorite.openUrl}>{content}</Link>;
  }
  return <a {...common} href={favorite.openUrl}
    rel={favorite.openInNewTab ? 'noopener noreferrer' : undefined}
    target={favorite.openInNewTab ? '_blank' : undefined}>{content}</a>;
}

export function AdminSidebar() {
  const { bootstrap } = useAdminApp();
  const [favoritesOpen, setFavoritesOpen] = useState(true);
  const entries = navigationFor('sidebar')
    .filter((entry) => navigationEntryAvailable(entry, bootstrap.capabilities));

  return (
    <aside className="sidebar">
      <NavLink className="sidebar-brand" to="/files">EnderVault</NavLink>
      <nav className="sidebar-nav" aria-label="Primary navigation">
        {entries.map((entry) => <ShellNavigationLink entry={entry} key={entry.id} />)}
      </nav>

      <div className="sidebar-favorites-shell">
        <section className={`sidebar-favorites${favoritesOpen ? ' is-open' : ''}`}>
          <div className="sidebar-favorites-header">
            <NavLink to="/files/favorites">
              <i className="fas fa-star" aria-hidden="true" />
              <span>Favorites</span>
            </NavLink>
            <button
              type="button"
              className="sidebar-favorites-toggle"
              aria-controls="sidebar-favorites-list"
              aria-expanded={favoritesOpen}
              title={favoritesOpen ? 'Collapse favorites' : 'Expand favorites'}
              onClick={() => setFavoritesOpen((open) => !open)}
            >
              <i className="fas fa-chevron-down" aria-hidden="true" />
              <span className="sr-only">{favoritesOpen ? 'Collapse favorites' : 'Expand favorites'}</span>
            </button>
          </div>
          {favoritesOpen && (
            <div id="sidebar-favorites-list" className="sidebar-favorites-list" data-sidebar-favorites-list>
              {bootstrap.favorites.map((favorite) => (
                <SidebarFavorite favorite={favorite} key={favorite.path} />
              ))}
              {bootstrap.favorites.length === 0 && <p data-sidebar-favorites-empty>No favorites yet.</p>}
            </div>
          )}
        </section>
      </div>

      <section className="sidebar-usage" aria-label="Disk usage">
        <p>Disk usage</p>
        <strong>{bootstrap.storage.usedLabel} / {bootstrap.storage.totalLabel}</strong>
        <span>{bootstrap.storage.usedPercent}% used / {bootstrap.storage.usableLabel} free</span>
      </section>
    </aside>
  );
}

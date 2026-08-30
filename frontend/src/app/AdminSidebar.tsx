import { NavLink } from 'react-router-dom';
import { useAdminApp } from './AdminAppContext';
import {
  navigationEntryAvailable,
  navigationFor,
} from './navigation';
import { ShellNavigationLink } from './ShellNavigationLink';

export function AdminSidebar() {
  const { bootstrap } = useAdminApp();
  const entries = navigationFor('sidebar')
    .filter((entry) => navigationEntryAvailable(entry, bootstrap.capabilities));

  return (
    <aside className="sidebar">
      <NavLink className="sidebar-brand" to="/files">EnderVault</NavLink>
      <nav className="sidebar-nav" aria-label="Primary navigation">
        {entries.map((entry) => <ShellNavigationLink entry={entry} key={entry.id} />)}
      </nav>

      <div className="sidebar-favorites-shell">
        <details className="sidebar-favorites" open>
          <summary>
            <i className="fas fa-star" aria-hidden="true" />
            <span>Favorites</span>
          </summary>
          <div className="sidebar-favorites-list" data-sidebar-favorites-list>
            {bootstrap.favorites.map((favorite) => (
              <a
                className={favorite.hidden ? 'is-hidden-item' : undefined}
                data-favorite-sidebar-path={favorite.path}
                data-favorite-direct-open-url={favorite.directOpenUrl}
                data-favorite-detail-url={favorite.detailUrl}
                data-favorite-bookmark-link={favorite.path.startsWith('bookmark:')}
                href={favorite.openUrl}
                key={favorite.path}
                rel={favorite.openInNewTab ? 'noopener noreferrer' : undefined}
                target={favorite.openInNewTab ? '_blank' : undefined}
                title={favorite.path}
              >
                <i className={favorite.iconClass} aria-hidden="true" />
                <span>{favorite.name}</span>
              </a>
            ))}
          </div>
          {bootstrap.favorites.length === 0 && <p data-sidebar-favorites-empty>No favorites yet.</p>}
        </details>
      </div>

      <section className="sidebar-usage" aria-label="Disk usage">
        <p>Disk usage</p>
        <strong>{bootstrap.storage.usedLabel} / {bootstrap.storage.totalLabel}</strong>
        <span>{bootstrap.storage.usedPercent}% used / {bootstrap.storage.usableLabel} free</span>
      </section>
    </aside>
  );
}

import { Link } from 'react-router-dom';
import { navigationEntryAvailable, navigationFor, type NavigationEntry } from './navigation';
import { ActivityControl } from './ActivityControl';
import { NotificationCenterControl } from './NotificationCenterControl';
import { OutboundRouteControl } from './OutboundRouteControl';
import { RouteActionSlot } from './RouteActions';
import { ShellNavigationLink } from './ShellNavigationLink';
import { ShellPopover } from './ShellPopover';
import { StickyNoteControl } from './StickyNoteControl';
import { useAdminApp } from './AdminAppContext';

const groupLabel: Record<string, string> = {
  sharing: 'Sharing',
  transfers: 'Transfers',
  operations: 'Operations',
  notes: 'Notes',
  application: 'Application',
  account: 'Account',
};

function NavigationGroups({ entries }: { entries: NavigationEntry[] }) {
  const groups = entries.reduce((result, entry) => {
    const group = entry.group ?? 'application';
    result.set(group, [...(result.get(group) ?? []), entry]);
    return result;
  }, new Map<string, NavigationEntry[]>());
  return (
    <div className="admin-shell-navigation-groups">
      {[...groups.entries()].map(([group, groupEntries]) => (
        <section className="admin-shell-navigation-group" key={group}>
          <strong>{groupLabel[group] ?? group}</strong>
          <div className="topbar-control-menu-actions">
            {groupEntries.map((entry) => (
              <ShellNavigationLink
                className="ghost button-link icon-text-button"
                entry={entry}
                key={entry.id}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function LogoutForm() {
  const csrf = window.EnderVault?.csrfPair();
  return (
    <form method="post" action="/logout">
      {csrf && <input type="hidden" name={csrf.name} value={csrf.value} />}
      <button className="ghost icon-text-button" type="submit">
        <i className="fas fa-right-from-bracket" aria-hidden="true" />
        <span>Log out</span>
      </button>
    </form>
  );
}

export function AdminTopbar({ sidebarCollapsed, onToggleSidebar }: {
  sidebarCollapsed: boolean;
  onToggleSidebar: () => void;
}) {
  const { bootstrap } = useAdminApp();
  const activeSessionCount = bootstrap.sessions.activeCount;
  const available = (placement: 'apps' | 'account') => navigationFor(placement)
    .filter((entry) => navigationEntryAvailable(entry, bootstrap.capabilities));

  return (
    <header className="topbar app-topbar">
      <div className="admin-shell-brand">
        <button type="button" className="ghost icon-button sidebar-collapse-toggle"
          aria-label={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          title={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          aria-expanded={!sidebarCollapsed} aria-controls="admin-sidebar" onClick={onToggleSidebar}>
          <i className="fas fa-bars" aria-hidden="true" />
        </button>
        <Link className="sidebar-brand" to="/files">EnderVault</Link>
      </div>
      <div className="admin-shell-route-actions"><RouteActionSlot /></div>
      <div className="topbar-actions">
        <ShellPopover id="applications" icon="fas fa-grip" label="Applications">
          <strong className="topbar-control-title">Applications</strong>
          <p>Sharing, transfers, operations, and application settings.</p>
          <NavigationGroups entries={available('apps')} />
        </ShellPopover>
        <NotificationCenterControl />
        <ActivityControl />
        <OutboundRouteControl />
        <StickyNoteControl />
        <ShellPopover
          id="account"
          icon={activeSessionCount > 1 ? 'fas fa-user-group' : 'fas fa-circle-user'}
          label={`Account, ${activeSessionCount} active ${activeSessionCount === 1 ? 'session' : 'sessions'}`}
          indicator={<span className="account-session-count" aria-hidden="true">
            {activeSessionCount > 99 ? '99+' : activeSessionCount}
          </span>}
        >
          <strong className="topbar-control-title">{bootstrap.username}</strong>
          <p>Account security, signed-in devices, and session controls.</p>
          <div className="topbar-control-status">
            <span>Active sessions</span>
            <strong>{activeSessionCount}</strong>
          </div>
          <NavigationGroups entries={available('account')} />
          <div className="topbar-control-menu-actions admin-shell-logout">
            <LogoutForm />
          </div>
        </ShellPopover>
      </div>
    </header>
  );
}

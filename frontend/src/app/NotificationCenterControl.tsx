import { useShellStatus, type NotificationCenterPayload } from './ShellStatusContext';
import { AppNavigationLink } from './AppNavigationLink';
import { ShellPopover } from './ShellPopover';

const emptyPayload: NotificationCenterPayload = {
  actionableCount: 0,
  items: [],
  reviewAllHref: '/admin/pending-decisions',
};

export function NotificationCenterControl() {
  const { notifications } = useShellStatus();
  const payload = notifications.data ?? emptyPayload;
  const available = Boolean(notifications.data) && !notifications.error;

  const countLabel = available ? `${payload.actionableCount} pending` : 'Unavailable';
  return (
    <ShellPopover
      id="notifications"
      icon="fas fa-bell"
      label={payload.actionableCount > 0
        ? `${payload.actionableCount} pending decision(s)`
        : 'Notifications'}
      indicator={payload.actionableCount > 0
        ? <span className="notification-center-indicator" aria-hidden="true" />
        : undefined}
    >
      <strong className="topbar-control-title">Notifications</strong>
      <div className="topbar-control-status">
        <span>Action required</span>
        <strong>{countLabel}</strong>
      </div>
      {payload.items.length > 0 ? (
        <div className="notification-center-list">
          {payload.items.map((item) => (
            <AppNavigationLink className="notification-center-item" href={item.href} key={item.id}>
              <i className="fas fa-file-circle-exclamation" aria-hidden="true" />
              <span>
                <strong>{item.title}</strong>
                <small>{item.detail}</small>
                <time>{item.createdLabel}</time>
              </span>
            </AppNavigationLink>
          ))}
        </div>
      ) : (
        <p className="notification-center-empty">{available ? 'No pending notifications.' : 'Notification status unavailable.'}</p>
      )}
      {payload.reviewAllHref && (
        <div className="topbar-control-menu-actions">
          <AppNavigationLink className="ghost button-link icon-text-button" href={payload.reviewAllHref}>
            <i className="fas fa-list-check" aria-hidden="true" />
            <span>Review all</span>
          </AppNavigationLink>
        </div>
      )}
    </ShellPopover>
  );
}

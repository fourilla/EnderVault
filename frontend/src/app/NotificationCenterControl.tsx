import { useShellStatus, type NotificationCenterPayload } from './ShellStatusContext';
import { AppNavigationLink } from './AppNavigationLink';
import { ShellPopover } from './ShellPopover';
import { useNavigate } from 'react-router-dom';
import { useDecisionDialog } from '../pending-decisions/DecisionDialogContext';
import { useTopbarPopover } from './TopbarPopoverContext';

const emptyPayload: NotificationCenterPayload = {
  actionableCount: 0,
  items: [],
  reviewAllHref: '/admin/pending-decisions',
};

export function NotificationCenterControl() {
  const { notifications } = useShellStatus();
  const navigate = useNavigate();
  const { openMerge } = useDecisionDialog();
  const { closeAll } = useTopbarPopover();
  const payload = notifications.data ?? emptyPayload;
  const available = Boolean(notifications.data) && !notifications.error;

  const countLabel = available ? `${payload.actionableCount} pending` : 'Unavailable';
  return (
    <ShellPopover
      id="notifications"
      onTriggerClick={() => { closeAll(); navigate(payload.reviewAllHref || '/admin/pending-decisions'); }}
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
            <AppNavigationLink className="notification-center-item" href={item.href} key={item.id}
              onClick={(event) => {
                if (item.target?.kind !== 'DIRECTORY_MERGE' || event.button !== 0
                    || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
                event.preventDefault();
                openMerge(item.target.id);
              }}>
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

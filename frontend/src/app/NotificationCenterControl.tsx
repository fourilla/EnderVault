import { useCallback, useEffect, useState } from 'react';
import { ShellPopover } from './ShellPopover';

interface NotificationCenterItem {
  id: string;
  title: string;
  detail: string;
  createdLabel: string;
  href: string;
}

interface NotificationCenterPayload {
  actionableCount: number;
  items: NotificationCenterItem[];
  reviewAllHref: string;
}

const emptyPayload: NotificationCenterPayload = {
  actionableCount: 0,
  items: [],
  reviewAllHref: '/admin/pending-decisions',
};

export function NotificationCenterControl() {
  const [payload, setPayload] = useState(emptyPayload);
  const [available, setAvailable] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const next = await window.EnderVault!.requestJson('/api/v1/notifications');
      setPayload(next as NotificationCenterPayload);
      setAvailable(true);
    } catch {
      setAvailable(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const interval = window.setInterval(() => void refresh(), 20_000);
    const onFocus = () => void refresh();
    window.addEventListener('focus', onFocus);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener('focus', onFocus);
    };
  }, [refresh]);

  const countLabel = available ? `${payload.actionableCount} pending` : 'Unavailable';
  return (
    <ShellPopover
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
            <a className="notification-center-item" href={item.href} key={item.id}>
              <i className="fas fa-file-circle-exclamation" aria-hidden="true" />
              <span>
                <strong>{item.title}</strong>
                <small>{item.detail}</small>
                <time>{item.createdLabel}</time>
              </span>
            </a>
          ))}
        </div>
      ) : (
        <p className="notification-center-empty">No pending notifications.</p>
      )}
      {payload.reviewAllHref && (
        <div className="topbar-control-menu-actions">
          <a className="ghost button-link icon-text-button" href={payload.reviewAllHref}>
            <i className="fas fa-list-check" aria-hidden="true" />
            <span>Review all</span>
          </a>
        </div>
      )}
    </ShellPopover>
  );
}

import { useEffect, useState } from 'react';
import { useUploadManager } from './uploads/UploadManagerContext';
import { ShellPopover } from './ShellPopover';

interface ActivitySnapshot {
  activeCount: number;
  finishedCount: number;
  totalCount: number;
  minimized: boolean;
}

const emptySnapshot: ActivitySnapshot = {
  activeCount: 0,
  finishedCount: 0,
  totalCount: 0,
  minimized: false,
};

const readSnapshot = () => window.EnderVaultActivity?.snapshot() ?? emptySnapshot;

export function ActivityControl() {
  const { activeCount: activeUploads } = useUploadManager();
  const [activity, setActivity] = useState<ActivitySnapshot>(readSnapshot);

  useEffect(() => {
    const onChanged = (event: Event) => {
      const next = (event as CustomEvent<ActivitySnapshot>).detail;
      setActivity(next ?? readSnapshot());
    };
    document.addEventListener('endervault:activity-changed', onChanged);
    setActivity(readSnapshot());
    return () => document.removeEventListener('endervault:activity-changed', onChanged);
  }, []);

  const activeCount = Math.max(activity.activeCount, activeUploads);
  const label = activeCount > 0 ? `${activeCount} active task(s)` : 'Activity';
  return (
    <ShellPopover
      id="activity"
      icon="fas fa-list-check"
      label={label}
      onTriggerClick={() => window.EnderVaultActivity?.toggle()}
      indicator={activeCount > 0
        ? <span className="activity-control-indicator" aria-hidden="true" />
        : undefined}
    >
      <strong className="topbar-control-title">Tasks & Uploads</strong>
      <p>Uploads and server tasks continue while you move through EnderVault.</p>
      <div className="topbar-control-status"><span>Active</span><strong>{activeCount}</strong></div>
      <div className="topbar-control-status"><span>Recently finished</span><strong>{activity.finishedCount}</strong></div>
      <small>{activity.totalCount === 0
        ? 'No activity to show.'
        : `Click the icon to ${activity.minimized ? 'show' : 'minimize'} the activity dock.`}</small>
    </ShellPopover>
  );
}

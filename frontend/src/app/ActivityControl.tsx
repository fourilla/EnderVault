import { useEffect, useRef } from 'react';
import { useActivitySnapshot, type ActivityItem } from './useActivitySnapshot';
import { useRemoteDownloadTasks } from './remote-downloads/RemoteDownloadTasksContext';
import { useUploadManager } from './uploads/UploadManagerContext';
import { ShellPopover } from './ShellPopover';
import { useTopbarPopover } from './TopbarPopoverContext';
import { observeActivityStarts } from './activity-starts';

const rowClass = (status: string) => {
  if (status === 'complete' || status === 'partial') return 'upload-complete';
  if (status === 'pending') return 'upload-pending';
  if (status === 'failed') return 'upload-failed';
  if (status === 'canceled') return 'upload-canceled';
  return 'upload-uploading';
};

function ActivityRow({ item }: { item: ActivityItem }) {
  return (
    <article className={`upload-item ${rowClass(item.status)}`}>
      <div className="upload-item-main">
        <div className="upload-item-row">
          <span className="upload-name" title={item.title}>{item.title}</span>
          <span className="upload-percent">{item.percent}%</span>
        </div>
        <div className="upload-meta">{item.message || item.typeLabel}</div>
        <div className="upload-progress-track" role="progressbar"
          aria-valuemin={0} aria-valuemax={100} aria-valuenow={item.percent}>
          <div className="upload-progress-bar" style={{ width: `${item.percent}%` }} />
        </div>
      </div>
      {item.cancelable && (
        <button className="ghost icon-button upload-cancel" type="button"
          disabled={item.cancelRequested}
          onClick={() => void window.EnderVaultActivity?.cancel(item.id)}
          title={item.cancelRequested ? 'Canceling' : 'Cancel'}
          aria-label={`Cancel ${item.title}`}>
          <i className="fas fa-xmark" aria-hidden="true" />
        </button>
      )}
    </article>
  );
}

export function ActivityControl() {
  const { activeCount: activeUploads } = useUploadManager();
  const { refresh: refreshRemoteDownloads } = useRemoteDownloadTasks();
  const activity = useActivitySnapshot();
  const popover = useTopbarPopover();
  const popoverRef = useRef(popover);
  popoverRef.current = popover;

  useEffect(() => observeActivityStarts({
    activeId: () => popoverRef.current.activeId,
    show: () => popoverRef.current.show('activity'),
  }), []);

  const activeCount = Math.max(activity.activeCount, activeUploads);
  const label = activeCount > 0 ? `${activeCount} active task(s)` : 'Tasks and uploads';
  return (
    <ShellPopover
      id="activity"
      icon="fas fa-list-check"
      label={label}
      rootClassName="activity-control"
      onOpen={refreshRemoteDownloads}
      indicator={activeCount > 0
        ? <span className="activity-control-indicator" aria-hidden="true" />
        : undefined}
    >
      <strong className="topbar-control-title">Tasks & Uploads</strong>
      <p>Uploads, remote downloads, and server tasks continue while you move through EnderVault.</p>
      <div className="topbar-control-status"><span>Active</span><strong>{activeCount}</strong></div>
      <div className="topbar-control-status"><span>Recently finished</span><strong>{activity.finishedCount}</strong></div>
      {activity.items.length === 0 ? (
        <p className="activity-popover-empty">No activity to show.</p>
      ) : (
        <div className="activity-popover-list" aria-label="Current and recent tasks">
          {activity.items.map((item) => <ActivityRow item={item} key={item.id} />)}
        </div>
      )}
    </ShellPopover>
  );
}

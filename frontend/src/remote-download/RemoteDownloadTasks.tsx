import type { RemoteDownloadTask } from './types';

export function RemoteDownloadTasks({ tasks, busyId, details, action }: {
  tasks: RemoteDownloadTask[];
  busyId: string;
  details: (task: RemoteDownloadTask) => void;
  action: (task: RemoteDownloadTask) => void;
}) {
  if (!tasks.length) return <p className="empty browser-grid-empty">No remote downloads yet.</p>;

  return (
    <section className="browser-section" aria-label="Remote download tasks">
      <header className="section-heading"><h2>Tasks ({tasks.length})</h2></header>
      <div className="table-wrap">
        <table>
          <thead><tr><th>Source</th><th>Destination</th><th>Route</th><th>Status / Progress</th><th>Time</th><th>Actions</th></tr></thead>
          <tbody>
            {tasks.map((task) => (
              <tr key={task.id}>
                <td className="remote-source" title={task.sourceUrl}>
                  <strong>{task.fileName || `Remote download #${task.shortId}`}</strong><small>{task.sourceUrl}</small>
                </td>
                <td>{task.targetPath || task.targetDirectory}</td>
                <td><span className={`status-badge ${task.networkRoute === 'vpn-required' ? 'active' : 'info'}`}>{task.networkRouteLabel}</span></td>
                <td>
                  <div className="remote-status-progress">
                    <span className={`status-badge ${task.statusClass}`}>{task.cancelRequested && task.active ? 'Canceling' : task.statusLabel}</span>
                    <div className="remote-progress">
                      <div className="upload-progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100}
                        aria-valuenow={task.progressPercent}>
                        <div className="upload-progress-bar" style={{ width: `${task.progressPercent}%` }} />
                      </div>
                      <span>{task.progressLabel}</span>
                    </div>
                  </div>
                </td>
                <td>{task.createdLabel}</td>
                <td>
                  <div className="table-actions">
                    <button className="ghost icon-button action-icon" type="button" onClick={() => details(task)} title="Details" aria-label="Details">
                      <i className="fas fa-circle-info" aria-hidden="true" />
                    </button>
                    <button className={`${task.active ? 'danger' : 'ghost'} icon-button action-icon`} type="button"
                      disabled={busyId === task.id || (task.active && task.cancelRequested)} onClick={() => action(task)}
                      title={task.active ? 'Cancel' : 'Remove task'} aria-label={task.active ? 'Cancel' : 'Remove task'}>
                      <i className={`fas ${task.active ? 'fa-ban' : 'fa-trash-can'}`} aria-hidden="true" />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

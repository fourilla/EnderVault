import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppNavigationLink } from '../app/AppNavigationLink';
import { useAdminApp } from '../app/AdminAppContext';
import { useUploadManager } from '../app/uploads/UploadManagerContext';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import type { DashboardPayload } from './types';
import './dashboard-app.css';

function MetricContent({ iconClass, label, value, detail }: {
  iconClass: string;
  label: string;
  value: string | number;
  detail: React.ReactNode;
}) {
  return <>{icon(iconClass)}<div><span>{label}</span><strong>{value}</strong>{detail}</div></>;
}

export function DashboardApp() {
  const { bootstrap } = useAdminApp();
  const { activeCount: activeUploads } = useUploadManager();
  const [payload, setPayload] = useState<DashboardPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const requestVersion = useRef(0);

  const refresh = useCallback((signal?: AbortSignal) => {
    const version = ++requestVersion.current;
    setLoading(true);
    setError('');
    void window.EnderVault?.requestJson('/api/v1/dashboard', { signal }).then((body) => {
      if (signal?.aborted || version !== requestVersion.current) return;
      setPayload(body);
    }).catch((reason) => {
      if (signal?.aborted || version !== requestVersion.current
          || (reason instanceof DOMException && reason.name === 'AbortError')) return;
      setError(reason instanceof Error ? reason.message : 'Dashboard status could not be loaded.');
    }).finally(() => {
      if (!signal?.aborted && version === requestVersion.current) setLoading(false);
    });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    refresh(controller.signal);
    return () => controller.abort();
  }, [refresh]);

  if (loading && !payload) return <main className="browser-load-state" aria-live="polite">
    {icon('fas fa-spinner fa-spin')}<span>Loading dashboard...</span>
  </main>;
  if (error || !payload) return <main className="browser-load-state browser-load-error" role="alert">
    <strong>Dashboard unavailable</strong><span>{error}</span>
    <button type="button" onClick={() => refresh()}>Try again</button>
  </main>;

  const activeWork = payload.thumbnails.inProgressCount + payload.remoteDownloads.running
    + payload.appTasks.running + activeUploads;
  const thumbnailsEnabled = payload.thumbnails.videoEnabled
    || payload.thumbnails.comicEnabled
    || payload.thumbnails.pdfEnabled;
  return <div className="dashboard-workspace">
    <PageHeader title="Dashboard" className="dashboard-heading" actions={(
      <button className="ghost icon-button action-icon" type="button" disabled={loading}
        onClick={() => refresh()} title="Refresh dashboard" aria-label="Refresh dashboard">
        {icon(`fas fa-arrows-rotate${loading ? ' fa-spin' : ''}`)}
      </button>
    )} />

    <section className="dashboard-metrics" aria-label="Dashboard metrics">
      <Link className="dashboard-metric dashboard-metric-link" to="/files">
        <MetricContent iconClass="fas fa-hard-drive" label="Storage" value={`${payload.storage.usedPercent}%`}
          detail={<ul className="dashboard-metric-breakdown">
            <li><span>Used</span><b>{payload.storage.usedLabel}</b></li>
            <li><span>Total</span><b>{payload.storage.totalLabel}</b></li>
            <li><span>Free</span><b>{payload.storage.usableLabel}</b></li>
          </ul>} />
      </Link>
      <Link className="dashboard-metric dashboard-metric-link" to="/admin/trash">
        <MetricContent iconClass="fas fa-trash-can" label="Trash" value={payload.trash.count}
          detail={<p>{payload.trash.sizeLabel}</p>} />
      </Link>
      <Link className="dashboard-metric dashboard-metric-link" to="/admin/shares">
        <MetricContent iconClass="fas fa-link" label="Shared links" value={payload.shares.active}
          detail={<p>{payload.shares.total} total</p>} />
      </Link>
      <article className="dashboard-metric">
        <MetricContent iconClass="fas fa-image" label="Thumbnail cache" value={payload.thumbnails.cachedFiles}
          detail={<p>{payload.thumbnails.sizeLabel}</p>} />
      </article>
    </section>

    <section className="dashboard-grid" aria-label="Dashboard panels">
      <article className="dashboard-panel dashboard-panel-wide dashboard-panel-focus">
        <header className="section-heading"><div><h2>Task Manager</h2>
          <p>Recent server-side work and remote transfers</p></div>
          <span className={`status-badge ${activeWork > 0 ? 'active' : 'expired'}`}>
            {activeWork > 0 ? 'Active' : 'Idle'}
          </span></header>
        <div className="dashboard-task-summary" aria-label="Task counts">
          <div><span>Background tasks</span><strong>{payload.appTasks.running}</strong>
            <small>{payload.appTasks.total} tracked</small></div>
          <div><span>Remote downloads</span><strong>{payload.remoteDownloads.running}</strong>
            <small>{bootstrap.capabilities.remoteDownloads ? `${payload.remoteDownloads.total} tracked` : 'Disabled'}</small></div>
          <div><span>Thumbnail queue</span><strong>{payload.thumbnails.inProgressCount}</strong>
            <small>Generating now</small></div>
          <div><span>Uploads</span><strong>{activeUploads}</strong><small>Active in this browser</small></div>
        </div>
        {payload.recentTasks.length === 0 ? <p className="dashboard-empty">No background tasks are tracked yet.</p>
          : <div className="dashboard-task-table-wrap"><table className="dashboard-task-table">
            <thead><tr><th>Work</th><th>Status</th><th>Progress</th><th>Route</th></tr></thead>
            <tbody>{payload.recentTasks.map((task, index) => <tr key={`${task.createdAt}-${task.detail}-${index}`}>
              <td><div className="dashboard-task-name" title={task.target}>{icon(task.iconClass)}<span>
                <strong>{task.title}</strong><small>{task.detail}</small></span></div></td>
              <td><span className={`status-badge ${task.statusClass}`}>{task.statusLabel}</span></td>
              <td><div className="dashboard-task-progress"><progress max="100" value={task.progressPercent} />
                <small>{task.progressLabel}</small></div></td>
              <td><span className={`status-badge ${task.routeClass}`}>{task.routeLabel}</span></td>
            </tr>)}</tbody>
          </table></div>}
      </article>

      <article className="dashboard-panel dashboard-panel-wide dashboard-panel-focus">
        <header className="section-heading"><div><h2>System Health</h2>
          <p>Storage, cache, sessions, and outbound connectivity</p></div>
          <AppNavigationLink className="ghost icon-text-button" href="/admin/vpn">
            {icon('fas fa-shield-halved')}<span>VPN status</span>
          </AppNavigationLink>
        </header>
        <div className="dashboard-health-layout">
          <ul className="dashboard-list">
            <li><span>Storage remaining</span><strong>{payload.storage.usableLabel} free</strong></li>
            <li><span>Storage usage</span><strong>{payload.storage.usedLabel} / {payload.storage.totalLabel}
              {' '}({payload.storage.usedPercent}%)</strong></li>
            <li><span>Trash footprint</span><strong>{payload.trash.count} items / {payload.trash.sizeLabel}</strong></li>
            <li><span>Thumbnails</span><strong>{thumbnailsEnabled ? 'Enabled' : 'Disabled'} / {payload.thumbnails.cachedFiles}
              {' '}cached / {payload.thumbnails.sizeLabel}</strong></li>
            <li><span>Remote download results</span><strong>{payload.remoteDownloads.complete} complete /
              {' '}{payload.remoteDownloads.failed} failed</strong></li>
            <li><span>Signed-in sessions</span><strong>{payload.activeSessions} active</strong></li>
          </ul>
          <section className="dashboard-vpn-overview" aria-labelledby="dashboardVpnHeading">
            <header><div><h3 id="dashboardVpnHeading">VPN Egress</h3><p>{payload.vpn.detail}</p></div>
              <span className={`status-badge ${payload.vpn.statusClass}`}>{payload.vpn.label}</span></header>
            <dl><div><dt>Outbound route</dt><dd><span className={`status-badge ${payload.vpn.vpnRouteSelected ? 'active' : 'info'}`}>
              {payload.vpn.routeLabel}</span></dd></div>
            <div><dt>Proxy health</dt><dd><span className={`status-badge ${payload.vpn.health.statusClass}`}>
              {payload.vpn.health.label}</span></dd></div>
            <div><dt>VPN public IP</dt><dd>{payload.vpn.publicIp}</dd></div>
            <div><dt>Last checked</dt><dd>{payload.vpn.checkedAtLabel} · {payload.vpn.latencyLabel}</dd></div>
            <div><dt>Active VPN tasks</dt><dd>{payload.vpn.activeVpnTasks}</dd></div></dl>
          </section>
        </div>
      </article>
    </section>
  </div>;
}

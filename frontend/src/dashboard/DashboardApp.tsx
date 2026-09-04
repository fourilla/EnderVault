import { useEffect, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAdminApp } from '../app/AdminAppContext';
import { useShellStatus } from '../app/ShellStatusContext';
import { useActivitySnapshot } from '../app/useActivitySnapshot';
import { useUploadManager } from '../app/uploads/UploadManagerContext';
import { useRemoteDownloadTasks } from '../app/remote-downloads/RemoteDownloadTasksContext';
import { usePolledJson } from '../shared/api/usePolledJson';
import { PageHeader } from '../shared/layout/PageHeader';
import { formatBytes, mergeOperations, uptimeLabel, usagePercent } from './dashboard-model';
import type { DashboardPayload, RuntimeResources } from './types';
import './dashboard-app.css';

const timeLabel = (value?: string | null) => value
  ? new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
  : 'Not yet checked';
const percentLabel = (value?: number | null) => value == null ? 'Unavailable' : `${value}%`;
const statusClass = (status: string) => status === 'failed' ? 'error' : status === 'pending' || status === 'partial'
  ? 'warning' : status === 'complete' ? 'active' : 'info';
const statusLabel = (status: string) => status === 'pending' ? 'Needs review'
  : status.charAt(0).toUpperCase() + status.slice(1);

function Gauge({ label, value, detail, tone = '' }: {
  label: string; value: number | null | undefined; detail?: ReactNode; tone?: string;
}) {
  return <div className={`overview-gauge ${tone}`}>
    <span>{label}</span>
    <div className="overview-gauge-meter">
      <progress max={100} value={value ?? 0} aria-label={label} aria-valuetext={percentLabel(value)} />
      {detail && <small>{detail}</small>}
    </div>
    <strong>{percentLabel(value)}</strong>
  </div>;
}

function Metric({ label, value, detail, icon, href, tone = '' }: {
  label: string; value: ReactNode; detail: ReactNode; icon: string; href?: string; tone?: string;
}) {
  const content = <><div className="overview-metric-label"><i className={`fas ${icon}`} aria-hidden="true" />{label}</div>
    <strong>{value}</strong><div className="overview-metric-detail">{detail}</div></>;
  return href ? <Link className={`dashboard-panel overview-metric ${tone}`} to={href}>{content}</Link>
    : <article className={`dashboard-panel overview-metric ${tone}`}>{content}</article>;
}

function Service({ label, value, detail, href, icon }: {
  label: string; value: ReactNode; detail: string; href?: string; icon: string;
}) {
  const content = <><i className={`fas ${icon}`} aria-hidden="true" />
    <span>{label}<small>{detail}</small></span><strong>{value}</strong></>;
  return href ? <Link className="dashboard-panel overview-service" to={href} title={detail}>{content}</Link>
    : <div className="dashboard-panel overview-service" title={detail}>{content}</div>;
}

export function DashboardApp() {
  const { bootstrap } = useAdminApp();
  const { notifications, outbound } = useShellStatus();
  const { activeCount: activeUploads } = useUploadManager();
  const remote = useRemoteDownloadTasks();
  const activity = useActivitySnapshot();
  const summary = usePolledJson<DashboardPayload>('/api/v1/dashboard', 60_000);
  const runtime = usePolledJson<RuntimeResources>('/api/v1/dashboard/runtime', 10_000);
  const payload = summary.data;
  const resources = runtime.error ? undefined : runtime.data;
  const route = outbound.data!;

  useEffect(() => {
    void remote.refresh();
  }, [remote.refresh]);

  useEffect(() => {
    const refresh = () => void summary.refresh();
    document.addEventListener('endervault:task-terminal', refresh);
    return () => document.removeEventListener('endervault:task-terminal', refresh);
  }, [summary.refresh]);

  const refreshAll = () => {
    void summary.refresh(); void runtime.refresh(); void notifications.refresh();
    void outbound.refresh(); void remote.refresh();
  };
  if (!payload) return <div className="browser-load-state" role={summary.error ? 'alert' : 'status'}>
    <i className={`fas ${summary.error ? 'fa-triangle-exclamation' : 'fa-spinner fa-spin'}`} aria-hidden="true" />
    <span>{summary.error || 'Loading dashboard...'}</span>
    {summary.error && <button type="button" onClick={refreshAll} disabled={summary.loading}>Retry</button>}
  </div>;

  const operations = mergeOperations(payload.serverTasks, remote.tasks ?? [], activity.items);
  const uploadItems = operations.filter((item) => item.source === 'upload' && item.active).length;
  const activeCount = operations.filter((item) => item.active).length + Math.max(0, activeUploads - uploadItems);
  const failedCount = operations.filter((item) => item.status === 'failed').length;
  const pending = notifications.data?.actionableCount;
  const warnings: { text: string; href?: string }[] = [];
  if (summary.error) warnings.push({ text: 'Summary refresh failed; showing last known values' });
  if (runtime.error) warnings.push({ text: 'Runtime resources unavailable' });
  if (outbound.error) warnings.push({ text: 'Outbound status unavailable', href: '/admin/vpn' });
  if (notifications.error) warnings.push({ text: 'Notification status unavailable', href: '/admin/pending-decisions' });
  if (remote.error) warnings.push({ text: 'Remote task status unavailable', href: '/admin/utils/remote-download' });
  if (!outbound.error && route.vpnSelected && !route.vpnReady) {
    warnings.push({ text: 'VPN unavailable; outbound requests are blocked', href: '/admin/vpn' });
  }
  if (payload.storage.usedPercent >= 90) warnings.push({ text: 'Storage is nearly full', href: '/files' });
  if (pending) warnings.push({ text: `${pending} decision(s) need review`, href: notifications.data?.reviewAllHref });
  if (failedCount) warnings.push({ text: `${failedCount} failed task(s) in recent history`, href: '/admin/logs' });
  if (bootstrap.capabilities.metadataInspector && payload.inspection.issues > 0) {
    warnings.push({ text: `${payload.inspection.issues} issue(s) in last inspection`, href: '/admin/metadata' });
  }
  const thumbnailEnabled = payload.thumbnails.videoEnabled || payload.thumbnails.comicEnabled || payload.thumbnails.pdfEnabled;
  const refreshFailed = Boolean(summary.error || runtime.error || outbound.error || notifications.error || remote.error);
  return <div className="dashboard-overview">
    <PageHeader title="Dashboard" />

    <section className="overview-metrics" aria-label="Status overview">
      <Metric label="Storage" icon="fa-hard-drive" value={`${payload.storage.usedPercent}%`} href="/files"
        tone={payload.storage.usedPercent >= 90 ? 'is-warning' : ''}
        detail={<><progress max={100} value={payload.storage.usedPercent} aria-label="Storage usage" />
          <span>{payload.storage.usableLabel} free / {payload.storage.totalLabel}</span></>} />
      <Metric label="Running tasks" icon="fa-list-check" value={activeCount}
        detail={`${activeUploads} upload(s) in this browser`} />
      <Metric label="Action required" icon="fa-bell" value={notifications.error || pending == null ? 'Unavailable' : pending}
        href={notifications.data?.reviewAllHref || '/admin/pending-decisions'}
        tone={pending ? 'is-warning' : ''} detail="Pending decisions and recovery reviews" />
      <Metric label="Outbound route" icon="fa-shield-halved" href="/admin/vpn"
        value={outbound.error ? 'Unavailable' : route.label}
        tone={route.vpnSelected ? route.vpnReady ? 'is-success' : 'is-warning' : ''}
        detail={outbound.error ? 'Status could not be refreshed' : route.vpnSelected
          ? route.vpnReady ? 'VPN ready' : 'VPN unavailable' : 'Direct connection'} />
    </section>

    <section className={`overview-attention${warnings.length ? ' has-warnings' : ''}`} aria-label="Attention">
      <i className={`fas ${warnings.length ? 'fa-triangle-exclamation' : 'fa-circle-check'}`} aria-hidden="true" />
      <div>{warnings.length ? warnings.map((warning) => warning.href
        ? <Link to={warning.href} key={warning.text}>{warning.text}</Link>
        : <span key={warning.text}>{warning.text}</span>)
        : <span>{!notifications.data || !runtime.data ? 'Checking status...' : 'No issues reported by current checks'}</span>}</div>
      {refreshFailed && <button className="ghost icon-button action-icon" type="button" onClick={refreshAll}
        disabled={summary.loading || runtime.loading} title="Retry status checks" aria-label="Retry status checks">
        <i className="fas fa-arrows-rotate" aria-hidden="true" />
      </button>}
    </section>

    <div className="overview-main">
      <section className="overview-section" aria-labelledby="runtimeHeading">
        <header><h2 id="runtimeHeading">Runtime Resources</h2>
          <span title="Resources visible to the JVM; Docker may report container limits rather than the physical host.">
            <i className="fas fa-circle-info" aria-hidden="true" /> JVM environment</span></header>
        <div className="dashboard-panel overview-runtime-panel">
          <div className="overview-resource-gauges">
            <Gauge label="CPU" value={resources?.cpuPercent} detail={`EnderVault process: ${percentLabel(resources?.processCpuPercent)}`} />
            <Gauge label="Memory" value={usagePercent(resources?.memoryUsedBytes, resources?.memoryTotalBytes)} tone="memory"
              detail={`${formatBytes(resources?.memoryUsedBytes)} / ${formatBytes(resources?.memoryTotalBytes)}`} />
            <Gauge label="JVM heap" value={usagePercent(resources?.heapUsedBytes, resources?.heapMaxBytes)} tone="heap"
              detail={`${formatBytes(resources?.heapUsedBytes)} / ${formatBytes(resources?.heapMaxBytes)}`} />
          </div>
          <dl className="overview-runtime-facts">
            <div><dt>Uptime</dt><dd>{uptimeLabel(resources?.uptimeMs)}</dd></div>
            <div><dt>Logical CPUs</dt><dd>{resources?.processors ?? '-'}</dd></div>
            <div><dt>Threads</dt><dd>{resources?.threads ?? '-'}</dd></div>
          </dl>
        </div>
      </section>
      <section className="overview-section" aria-labelledby="operationsHeading">
        <header><h2 id="operationsHeading">Operations</h2><span>{activeCount ? `${activeCount} active` : 'Idle'}</span></header>
        <ul className="overview-operations">
          {operations.slice(0, 4).map((item) => <li key={item.id}>
            <div className="overview-operation-copy"><strong title={item.title}>{item.title}</strong>
              <small title={item.detail}>{item.detail}</small></div>
            <div className="overview-operation-state"><span className={`status-badge ${statusClass(item.status)}`}>
              {statusLabel(item.status)}</span>
              {item.active && <progress max={100} value={item.percent} aria-label={`${item.title} progress`} />}</div>
          </li>)}
          {operations.length === 0 && <li className="overview-empty">No recent activity</li>}
        </ul>
        {operations.length > 4 && <small className="overview-more">{operations.length - 4} more in task history</small>}
      </section>
    </div>

    <section className="overview-section" aria-labelledby="servicesHeading">
      <header><h2 id="servicesHeading">Services & Maintenance</h2>
        <time className="overview-updated" dateTime={payload.updatedAt} title="Summary updated">{timeLabel(payload.updatedAt)}</time>
      </header>
      <div className="overview-services">
        <Service label="Shared links" icon="fa-link" value={payload.shares.active} href="/admin/shares"
          detail={`${payload.shares.total} total / ${payload.shares.expired} expired`} />
        <Service label="File requests" icon="fa-inbox" href={bootstrap.capabilities.fileRequests ? '/admin/file-requests' : undefined}
          value={bootstrap.capabilities.fileRequests ? payload.fileRequests.active : 'Disabled'}
          detail={`${payload.fileRequests.total} total`} />
        <Service label="Sessions" icon="fa-laptop" value={payload.activeSessions} href="/admin/sessions" detail="Signed-in devices" />
        <Service label="Trash" icon="fa-trash-can" value={payload.trash.sizeLabel} href="/admin/trash"
          detail={`${payload.trash.count} item(s)`} />
        <Service label="Thumbnail cache" icon="fa-image" value={payload.thumbnails.sizeLabel}
          href={bootstrap.capabilities.metadataInspector ? '/admin/metadata' : undefined}
          detail={`${payload.thumbnails.cachedFiles} cached / ${thumbnailEnabled ? 'Enabled' : 'Disabled'}`} />
        <Service label="Metadata inspection" icon="fa-magnifying-glass-chart"
          href={bootstrap.capabilities.metadataInspector ? '/admin/metadata' : undefined}
          value={!bootstrap.capabilities.metadataInspector ? 'Disabled' : payload.inspection.present ? `${payload.inspection.issues} issues` : 'Not run'}
          detail={timeLabel(payload.inspection.scannedAt)} />
      </div>
    </section>
  </div>;
}

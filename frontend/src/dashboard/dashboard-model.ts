import type { ActivityItem } from '../app/useActivitySnapshot';
import type { RemoteDownloadTask } from '../remote-download/types';
import type { DashboardTask } from './types';

export interface DashboardOperation {
  id: string; title: string; detail: string; status: string; active: boolean;
  percent: number; source: 'server' | 'remote' | 'upload';
}

export function mergeOperations(server: DashboardTask[], remote: RemoteDownloadTask[], live: ActivityItem[]) {
  const items = new Map<string, DashboardOperation>();
  server.forEach((task) => items.set(task.id, {
    id: task.id, title: task.title, detail: task.progressLabel || task.detail,
    status: task.status, active: task.active, percent: task.progressPercent, source: 'server',
  }));
  remote.filter((task, index) => task.active || index < 6).forEach((task) => items.set(`remote-${task.id}`, {
    id: `remote-${task.id}`, title: task.fileName || `Remote download #${task.shortId}`,
    detail: `${task.networkRouteLabel} / ${task.progressLabel}`, status: task.status.toLowerCase(),
    active: task.active, percent: task.progressPercent, source: 'remote',
  }));
  live.forEach((item) => {
    // The remote provider is canonical; its activity mirror can retain an older terminal item.
    if (item.type === 'REMOTE_DOWNLOAD' && items.has(item.id)) return;
    items.set(item.id, {
      id: item.id, title: item.title, detail: item.message || item.typeLabel, status: item.status,
      active: !['pending', 'complete', 'partial', 'failed', 'canceled'].includes(item.status),
      percent: item.percent, source: item.type === 'UPLOAD' ? 'upload'
        : item.type === 'REMOTE_DOWNLOAD' ? 'remote' : 'server',
    });
  });
  const priority = (item: DashboardOperation) => item.active ? 0 : item.status === 'failed' ? 1
    : item.status === 'pending' || item.status === 'partial' ? 2 : 3;
  return [...items.values()].sort((a, b) => priority(a) - priority(b));
}

export function formatBytes(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value) || value < 0) return 'Unavailable';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++; }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

export function usagePercent(used: number | null | undefined, total: number | null | undefined) {
  return used != null && total != null && total > 0 && used >= 0 && used <= total
    ? Math.round(used / total * 100) : null;
}

export function uptimeLabel(ms: number | undefined) {
  if (ms == null) return 'Unavailable';
  const minutes = Math.floor(ms / 60_000);
  const hours = Math.floor(minutes / 60);
  return hours >= 24 ? `${Math.floor(hours / 24)}d ${hours % 24}h`
    : hours > 0 ? `${hours}h ${minutes % 60}m` : `${minutes}m`;
}

import type { AdminAppBootstrap } from '../app/types';

export interface DashboardPayload {
  storage: AdminAppBootstrap['storage'];
  trash: { count: number; sizeBytes: number; sizeLabel: string };
  shares: { total: number; active: number; expired: number; revoked: number };
  thumbnails: {
    videoEnabled: boolean; comicEnabled: boolean; pdfEnabled: boolean;
    cachedFiles: number; sizeBytes: number; sizeLabel: string; inProgressCount: number;
  };
  fileRequests: { total: number; active: number };
  inspection: { present: boolean; issues: number; scannedAt: string | null };
  serverTasks: DashboardTask[];
  activeSessions: number;
  updatedAt: string;
}

export interface DashboardTask {
  id: string;
  active: boolean;
  status: string;
  title: string;
  detail: string;
  progressPercent: number;
  progressLabel: string;
  createdAt: string;
}

export interface RuntimeResources {
  cpuPercent: number | null;
  processCpuPercent: number | null;
  memoryTotalBytes: number | null;
  memoryUsedBytes: number | null;
  heapUsedBytes: number;
  heapMaxBytes: number | null;
  processors: number;
  threads: number;
  uptimeMs: number;
  sampledAt: string;
}

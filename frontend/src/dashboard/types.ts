export interface DashboardPayload {
  storage: {
    usedBytes: number;
    totalBytes: number;
    usableBytes: number;
    usedLabel: string;
    totalLabel: string;
    usableLabel: string;
    usedPercent: number;
  };
  trash: { count: number; sizeBytes: number; sizeLabel: string };
  shares: { total: number; active: number; expired: number; revoked: number };
  thumbnails: {
    videoEnabled: boolean;
    comicEnabled: boolean;
    pdfEnabled: boolean;
    cachedFiles: number;
    sizeBytes: number;
    sizeLabel: string;
    inProgressCount: number;
  };
  remoteDownloads: { total: number; running: number; complete: number; failed: number };
  appTasks: { total: number; running: number; complete: number; failed: number };
  recentTasks: DashboardTask[];
  activeSessions: number;
  vpn: {
    state: string;
    label: string;
    statusClass: string;
    controllable: boolean;
    running: boolean;
    publicIp: string;
    checkedAtLabel: string;
    latencyLabel: string;
    detail: string;
    profileName: string;
    routeLabel: string;
    vpnRouteSelected: boolean;
    activeVpnTasks: number;
    health: {
      state: string;
      label: string;
      statusClass: string;
      proxyReachable: boolean;
      routeReady: boolean;
      checkedAtLabel: string;
      latencyLabel: string;
      detail: string;
    };
  };
}

export interface DashboardTask {
  title: string;
  detail: string;
  iconClass: string;
  statusLabel: string;
  statusClass: string;
  progressPercent: number;
  progressLabel: string;
  routeLabel: string;
  routeClass: string;
  target: string;
  createdAt: string;
}

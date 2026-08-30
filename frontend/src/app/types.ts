export interface AdminAppCapabilities {
  remoteDownloads: boolean;
  fileRequests: boolean;
  vpn: boolean;
  metadataInspector: boolean;
}

export interface AdminAppFavorite {
  path: string;
  name: string;
  directory: boolean;
  iconClass: string;
  openUrl: string;
  directOpenUrl: string;
  detailUrl: string;
  openInNewTab: boolean;
  hidden: boolean;
}

export interface AdminAppBootstrap {
  username: string;
  capabilities: AdminAppCapabilities;
  storage: {
    usedBytes: number;
    totalBytes: number;
    usableBytes: number;
    usedLabel: string;
    totalLabel: string;
    usableLabel: string;
    usedPercent: number;
  };
  favorites: AdminAppFavorite[];
  tasks: {
    activityPanelEnabled: boolean;
    completedDisplayMs: number;
    failedDisplayMs: number;
  };
  uploads: {
    maxConcurrentUploads: number;
  };
  outboundRoute: {
    route: string;
    label: string;
    nextRoute: string;
    iconClass: string;
    statusClass: string;
    title: string;
    vpnSelected: boolean;
    vpnReady: boolean;
  };
  stickyNoteTheme: {
    backgroundColor: string;
    borderColor: string;
    textColor: string;
  };
}

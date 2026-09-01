export interface RemoteDownloadTask {
  id: string;
  shortId: string;
  sourceUrl: string;
  fileName: string;
  targetDirectory: string;
  targetPath: string;
  networkRoute: string;
  networkRouteLabel: string;
  status: string;
  statusLabel: string;
  statusClass: string;
  progressPercent: number;
  progressLabel: string;
  createdLabel: string;
  finishedLabel: string;
  message: string;
  requestedConnections: number;
  actualConnections: number;
  pendingDecisionId: string;
  retryCount: number;
  startedLabel: string;
  active: boolean;
  cancelRequested: boolean;
}

export interface RemoteDownloadProbe {
  sourceUrl: string;
  finalUrl: string;
  targetDirectory: string;
  fileName: string;
  targetPath: string;
  contentType: string;
  contentLength: number;
  contentLengthLabel: string;
  contentTypeLabel: string;
  warningLabel: string;
  networkRoute: string;
  networkRouteLabel: string;
  status: string;
  statusLabel: string;
  rangeCapability: string;
  rangeCapabilityLabel: string;
  requestedConnections: number;
  inspectionSkipped: boolean;
  requestOptionsLabel: string;
  startAllowed: boolean;
  detail: string;
}

export interface RemoteDownloadPagePayload {
  tasks: RemoteDownloadTask[];
  skipInspectByDefault: boolean;
  defaultTargetDirectory: string;
}

export interface RemoteDownloadInspection {
  ok: boolean;
  requestId: string;
  probe: RemoteDownloadProbe;
}

export interface RemoteDownloadActionResponse {
  ok: boolean;
  notification?: unknown;
  task?: RemoteDownloadTask;
}

export type RemoteNetworkRoute = 'global' | 'direct' | 'vpn-required';

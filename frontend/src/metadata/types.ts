export interface MetadataArea {
  name: string;
  label: string;
  description: string;
  iconClass: string;
}

export interface MetadataIssue {
  token: string;
  severityLabel: string;
  severityClass: string;
  title: string;
  detail: string;
  recommendation: string;
  actionLabel: string;
  repairable: boolean;
}

export interface MetadataAreaReport {
  area: MetadataArea;
  issueCount: number;
  repairableCount: number;
  healthy: boolean;
  issues: MetadataIssue[];
}

export interface MetadataReport {
  createdAtLabel: string;
  scannedAtLabel: string;
  selectedAreaCount: number;
  issueCount: number;
  repairableCount: number;
  healthy: boolean;
  areaReports: MetadataAreaReport[];
}

export interface MetadataTask {
  id: string;
  type: string;
  iconClass: string;
  title: string;
  status: string;
  statusLabel: string;
  progressLabel: string;
  message: string;
  active: boolean;
}

export interface MetadataPagePayload {
  areas: MetadataArea[];
  report: MetadataReport | null;
  activeInspectionTask: MetadataTask | null;
}

export interface MetadataScanPayload {
  ok: boolean;
  notification?: unknown;
  task: MetadataTask;
  replacedExistingTask: boolean;
  reportUrl: string;
}

export interface MetadataRepairPayload {
  ok: boolean;
  notification?: unknown;
  repairedTokens: string[];
  repaired: number;
  failed: number;
  messages: string[];
  issueCount: number;
  repairableCount: number;
}

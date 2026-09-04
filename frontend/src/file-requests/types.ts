export interface FileRequestItem {
  id: string;
  title: string;
  description: string;
  url: string;
  destinationPath: string;
  destinationLabel: string;
  uploaderNamePolicy: string;
  uploaderNameLabel: string;
  maxFileSizeBytes: number;
  fileLimitLabel: string;
  maxTotalBytes: number;
  totalLimitLabel: string;
  maxFiles: number;
  allowedExtensions: string[];
  extensionsLabel: string;
  acceptedBytes: number;
  acceptedFiles: number;
  usageLabel: string;
  createdLabel: string;
  expiresLabel: string;
  statusLabel: string;
  statusClass: string;
  active: boolean;
}

export interface FileRequestCreateDefaults {
  title: string;
  description: string;
  destinationPath: string;
  expirationDays: number;
  maxFileSizeGb: string;
  maxTotalGb: string;
  maxFiles: number;
  allowedExtensions: string;
  uploaderNamePolicy: string;
  duplicating: boolean;
}

export interface FileRequestListPayload {
  requests: FileRequestItem[];
  enabled: boolean;
  customTokensEnabled: boolean;
  customTokenMinLength: number;
  customTokenMaxLength: number;
  uploaderNamePolicies: Array<{ value: string; label: string }>;
  defaults: FileRequestCreateDefaults;
}

export interface FileRequestActiveUpload {
  id: string;
  originalFilename: string;
  submittedBy: string;
  sizeLabel: string;
  status: string;
  createdLabel: string;
  expiresLabel: string;
}

export interface FileRequestPendingFile {
  id: string;
  originalFilename: string;
  submittedBy: string;
  sizeLabel: string;
  createdLabel: string;
}

export interface FileRequestActivity {
  id: string;
  timestampLabel: string;
  typeLabel: string;
  ipLabel: string;
  messageLabel: string;
}

export interface FileRequestDetailPayload {
  item: FileRequestItem;
  activeUploads: FileRequestActiveUpload[];
  pendingDecisions: FileRequestPendingFile[];
  activityHistory: FileRequestActivity[];
  canDelete: boolean;
}

export interface FileRequestCreateValues {
  title: string;
  description: string;
  destinationPath: string;
  uploaderNamePolicy: string;
  expirationDays: string;
  maxFileSizeGb: string;
  maxTotalGb: string;
  maxFiles: string;
  allowedExtensions: string;
  customToken: string;
}

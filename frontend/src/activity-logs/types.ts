export interface ActivityLogFile {
  name: string;
  label: string;
  sizeLabel: string;
  modifiedLabel: string;
  current: boolean;
  deletable: boolean;
}

export interface ActivityLogEntry {
  id: string;
  timestampLabel: string;
  statusLabel: string;
  statusClass: string;
  type: string;
  actorLabel: string;
  ipLabel: string;
  messageLabel: string;
  pathLabel: string;
  targetPathLabel: string;
  metadataLabel: string;
  detailLine: string;
}

export interface ActivityLogQuery {
  text: string;
  type: string;
  status: string;
  order: string;
  from: string;
  to: string;
  page: number;
  size: number;
}

export interface ActivityLogPayload {
  files: ActivityLogFile[];
  selectedFile: string;
  selectedFileDeletable: boolean;
  entries: ActivityLogEntry[];
  typeOptions: string[];
  totalCount: number;
  matchedCount: number;
  firstIndex: number;
  lastIndex: number;
  page: number;
  size: number;
  totalPages: number;
  pageSizeOptions: number[];
  query: ActivityLogQuery;
}

export interface ActivityLogFilterDraft {
  text: string;
  type: string;
  status: string;
  order: string;
  from: string;
  to: string;
  size: string;
}

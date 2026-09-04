export interface ShareLink {
  token: string;
  url: string;
  directDownloadUrl: string | null;
  path: string;
  type: string;
  createdLabel: string;
  expiresLabel: string;
  statusLabel: string;
  statusClass: string;
  previewEnabled: boolean;
  active: boolean;
}

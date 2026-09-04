export interface ActiveSession {
  managementId: string;
  username: string;
  ip: string;
  userAgent: string;
  deviceLabel: string;
  authMethodLabel: string;
  createdLabel: string;
  lastActiveLabel: string;
  expiresLabel: string;
  current: boolean;
}

export interface ActiveSessionListPayload {
  sessions: ActiveSession[];
}

export interface SessionRevokePayload {
  ok: boolean;
  notification?: unknown;
  redirectUrl?: string | null;
}

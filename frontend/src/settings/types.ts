export type NotificationPayload = {
  type: string;
  message: string;
  actionLabel?: string | null;
  actionValue?: string | null;
  actionHref?: string | null;
};

export type ActionResponse = {
  ok: boolean;
  notification?: NotificationPayload | null;
  redirectUrl?: string | null;
};

export type FormValue = string | boolean | string[];
export type FormValues = Record<string, FormValue>;

export type GeneralSettingsSnapshot = {
  browser: {
    defaultView: string;
    defaultSort: string;
    defaultDirection: string;
    defaultPageSize: number;
  };
  stickyNotes: {
    backgroundColor: string;
    borderColor: string;
    textColor: string;
    defaultBackgroundColor: string;
    defaultBorderColor: string;
    defaultTextColor: string;
  };
  storage: { defaultConflictPolicy: string };
  recent: { maxItems: number; recordDirectories: boolean };
  trash: {
    retentionDays: number;
    cleanupOnStartup: boolean;
    cleanupIntervalMs: number;
    cleanupIntervalMinutes: string;
  };
  fileTools: {
    textAutoLoadMaxBytes: number;
    textManualLoadMaxBytes: number;
    textDraftRetentionHours: number;
    textDraftCleanupIntervalMs: number;
    textDraftLeaseSeconds: number;
    comicMaxPages: number;
    comicPageMaxBytes: number;
    comicInfoMaxBytes: number;
    textAutoLoadMaxMib: string;
    textManualLoadMaxMib: string;
    textDraftCleanupIntervalMinutes: string;
    comicPageMaxMib: string;
    comicInfoMaxKib: string;
  };
  remoteDownload: {
    enabled: boolean;
    directEnabled: boolean;
    blockPrivateNetworks: boolean;
    allowedPorts: string;
    responseTimeoutSeconds: number;
    maxRedirects: number;
    maxFileSizeBytes: number;
    historyLimit: number;
    maxRetries: number;
    skipInspectByDefault: boolean;
    defaultTargetDirectory: string;
    maxFileSizeGib: string;
  };
  configPath: string;
};

export type AdvancedSettingChoice = { value: string; label: string };

export type AdvancedSettingField = {
  name: string;
  label: string;
  description: string;
  type: 'boolean' | 'number' | 'text' | 'textarea' | 'select';
  value: string;
  min: string;
  max: string;
  step: string;
  unit: string;
  restartRequired: boolean;
  confirmationRequired: boolean;
  choices: AdvancedSettingChoice[];
};

export type AdvancedSettingsSnapshot = {
  groups: Array<{
    id: string;
    title: string;
    description: string;
    fields: AdvancedSettingField[];
  }>;
  deployment: Array<{ label: string; value: string; description: string }>;
  configPath: string;
};

export type BookmarkSettingsSnapshot = {
  behavior: { linkClickAction: string };
  metadata: {
    metadataFetchEnabled: boolean;
    blockPrivateNetworks: boolean;
    allowedPorts: string;
    connectTimeoutSeconds: number;
    responseTimeoutSeconds: number;
    maxRedirects: number;
    htmlMaxBytes: number;
    faviconMaxBytes: number;
    htmlMaxKib: string;
    faviconMaxKib: string;
  };
  cache: { faviconCacheDirectory: string };
  configPath: string;
};

export type FileRequestSettingsSnapshot = {
  enabled: boolean;
  defaultExpirationDays: number;
  defaultMaxFileSizeGb: string;
  defaultMaxTotalGb: string;
  defaultMaxFiles: number;
  defaultUploaderNamePolicy: string;
  customTokenEnabled: boolean;
  customTokenMinLength: number;
  customTokenMaxLength: number;
  randomTokenBytes: number;
  maxConcurrentUploadsPerRequest: number;
  rateLimitEnabled: boolean;
  rateLimitMaxAdmissions: number;
  rateLimitWindowSeconds: number;
  accessLogDedupSeconds: number;
  configPath: string;
};

export type VpnSettingsSnapshot = {
  enabled: boolean;
  proxyHost: string;
  proxyPort: number;
  healthConnectTimeoutMs: number;
  tunnelHealthUrl: string;
  healthRequestTimeoutMs: number;
  healthCheckIntervalMs: number;
  healthConnectTimeoutSeconds: string;
  healthRequestTimeoutSeconds: string;
  healthCheckIntervalSeconds: string;
  configPath: string;
};

export type SessionSettingsSnapshot = {
  maxConcurrentSessions: number;
  sessionIdleTimeoutMinutes: number;
  activeSessions: number;
  configPath: string;
};

export type AccountSettingsSnapshot = {
  username: string;
  passwordLoginEnabled: boolean;
  passkeysEnabled: boolean;
  passkeyCount: number;
  configPath: string;
};

export type TelegramSettingsSnapshot = {
  enabled: boolean;
  botToken: string;
  chatId: string;
  groups: Array<{
    name: string;
    activities: Array<{ key: string; type: string; label: string; enabled: boolean }>;
  }>;
  configPath: string;
};

export type PasskeySettingsSnapshot = {
  enabled: boolean;
  passwordLoginEnabled: boolean;
  rpId: string;
  allowedOrigins: string[];
  credentials: Array<{
    id: string;
    label: string;
    credentialId: string;
    shortCredentialId: string;
    transports: string;
    backedUp: boolean;
    created: string;
    lastUsed: string;
  }>;
};

declare global {
  interface Window {
    EnderVault?: {
      askConfirmation?: (options: {
        title: string;
        message: string;
        confirmLabel: string;
        danger?: boolean;
      }) => Promise<boolean>;
    };
    EnderVaultToasts?: {
      show: (notification: NotificationPayload) => void;
    };
  }
}

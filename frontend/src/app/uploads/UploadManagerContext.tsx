import {
  createContext,
  type PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useAdminApp } from '../AdminAppContext';

type UploadStatus =
  | 'queued'
  | 'fingerprinting'
  | 'reserving'
  | 'uploading'
  | 'canceling'
  | 'conflict'
  | 'resolving'
  | 'complete'
  | 'failed'
  | 'canceled';

interface UploadConflict {
  id: string;
  fileName: string;
  directoryPath: string;
  defaultPolicy: string;
}

interface ManagedUpload {
  id: number;
  file: File;
  destinationPath: string;
  loaded: number;
  total: number;
  status: UploadStatus;
  message: string;
  cancelRequested: boolean;
  handle?: EnderVaultUploadHandle;
  conflict?: UploadConflict;
  redirectUrl?: string;
  removeTimer?: number;
}

interface UploadManagerValue {
  activeCount: number;
  startFiles: (files: File[], destinationPath: string) => void;
}

const activeStatuses = new Set<UploadStatus>([
  'queued', 'fingerprinting', 'reserving', 'uploading', 'canceling', 'conflict', 'resolving',
]);

const UploadManagerContext = createContext<UploadManagerValue | null>(null);

class AdminUploadManager {
  private readonly uploads = new Map<number, ManagedUpload>();
  private readonly queue: ManagedUpload[] = [];
  private readonly conflictQueue: ManagedUpload[] = [];
  private running = 0;
  private nextId = 1;
  private conflictDialogOpen = false;

  constructor(
    private maxConcurrentUploads: number,
    private readonly onActiveCount: (count: number) => void,
  ) {}

  setMaxConcurrentUploads(value: number) {
    this.maxConcurrentUploads = Math.max(1, value);
    this.startQueuedUploads();
  }

  hasActiveUploads() {
    return [...this.uploads.values()].some((upload) => activeStatuses.has(upload.status));
  }

  startFiles(files: File[], destinationPath: string) {
    files.forEach((file) => {
      const upload: ManagedUpload = {
        id: this.nextId++,
        file,
        destinationPath,
        loaded: 0,
        total: file.size,
        status: 'queued',
        message: '',
        cancelRequested: false,
      };
      this.uploads.set(upload.id, upload);
      this.queue.push(upload);
    });
    this.startQueuedUploads();
  }

  private activeUploads() {
    return [...this.uploads.values()].filter((upload) => activeStatuses.has(upload.status));
  }

  private render() {
    this.uploads.forEach((upload) => {
      window.EnderVaultActivity?.upsert({
        id: `upload-${upload.id}`,
        title: upload.file.name,
        type: 'UPLOAD',
        typeLabel: 'Upload',
        status: upload.status,
        percent: this.percent(upload),
        message: this.statusText(upload),
        cancelRequested: upload.cancelRequested,
        cancelable: ['queued', 'fingerprinting', 'reserving', 'uploading'].includes(upload.status),
        onCancel: () => this.cancel(upload),
      });
    });
    this.onActiveCount(this.activeUploads().length);
  }

  private percent(upload: ManagedUpload) {
    if (['complete', 'conflict', 'resolving'].includes(upload.status)) return 100;
    if (!upload.total) return 0;
    return Math.max(0, Math.min(100, Math.round((upload.loaded / upload.total) * 100)));
  }

  private statusText(upload: ManagedUpload) {
    if (upload.cancelRequested && upload.status === 'uploading') return 'Canceling...';
    if (upload.status === 'queued') return 'Waiting to upload';
    if (upload.status === 'complete') return 'Complete';
    if (upload.status === 'failed') return upload.message || 'Failed';
    if (upload.status === 'canceled') return 'Canceled';
    if (upload.status === 'conflict') return 'Waiting for conflict choice';
    if (upload.status === 'resolving') return 'Applying conflict choice...';
    const format = window.EnderVaultActivity?.formatBytes ?? ((bytes: number) => `${bytes} B`);
    return upload.message || `${format(upload.loaded)} / ${format(upload.total)}`;
  }

  private finish(upload: ManagedUpload, status: UploadStatus, message = '') {
    if (!activeStatuses.has(upload.status)) return;
    upload.status = status;
    upload.message = message;
    if (status === 'complete') {
      upload.loaded = upload.total;
      window.EnderVaultFileBrowser?.requestListingRefresh(upload.redirectUrl || window.location.href);
    }
    this.render();
    const delay = status === 'complete' ? 2600 : 6500;
    window.clearTimeout(upload.removeTimer);
    upload.removeTimer = window.setTimeout(() => {
      window.EnderVaultActivity?.remove(`upload-${upload.id}`);
      this.uploads.delete(upload.id);
      this.render();
    }, delay);
  }

  private async cancel(upload: ManagedUpload) {
    if (!['queued', 'fingerprinting', 'reserving', 'uploading'].includes(upload.status)
      || upload.cancelRequested) return;
    upload.cancelRequested = true;
    if (upload.status === 'queued') {
      this.finish(upload, 'canceled');
      return;
    }
    upload.status = 'canceling';
    this.render();
    try {
      await upload.handle?.abort();
      this.finish(upload, 'canceled');
    } catch (reason) {
      this.finish(upload, 'failed', reason instanceof Error ? reason.message : 'Cancel failed.');
    }
  }

  private startQueuedUploads() {
    while (this.running < this.maxConcurrentUploads && this.queue.length > 0) {
      const upload = this.queue.shift();
      if (!upload || upload.status !== 'queued') continue;
      this.running += 1;
      void this.send(upload).finally(() => {
        this.running = Math.max(0, this.running - 1);
        this.startQueuedUploads();
      });
    }
    this.render();
  }

  private async send(upload: ManagedUpload) {
    const client = window.EnderVaultResumableUpload;
    if (!client) {
      this.finish(upload, 'failed', 'Resumable upload support is unavailable.');
      return;
    }
    const admissionUrl = `/api/v1/files/upload-sessions?path=${encodeURIComponent(upload.destinationPath)}`;
    try {
      upload.handle = client.create({
        file: upload.file,
        context: admissionUrl,
        admissionUrl,
        onState: (state: string, message?: string) => {
          upload.status = state as UploadStatus;
          upload.message = message || '';
          this.render();
        },
        onProgress: (sent: number, total: number) => {
          upload.loaded = sent;
          upload.total = total;
          this.render();
        },
      });
      const result = await upload.handle.start();
      if (!result || result.status === 'CANCELED') {
        this.finish(upload, 'canceled');
      } else if (result.status === 'PENDING' && result.pendingDecisionId) {
        this.queueConflict(upload, {
          id: result.pendingDecisionId,
          fileName: upload.file.name,
          directoryPath: upload.destinationPath,
          defaultPolicy: result.defaultConflictPolicy || 'cancel',
        });
      } else if (result.status === 'COMPLETED') {
        upload.redirectUrl = result.redirectUrl;
        this.finish(upload, 'complete', result.message || 'Upload complete');
      } else {
        this.finish(upload, 'failed', result.message || 'Upload could not be finalized.');
      }
    } catch (reason) {
      this.finish(upload, 'failed', reason instanceof Error ? reason.message : 'Upload failed.');
    }
  }

  private queueConflict(upload: ManagedUpload, conflict: UploadConflict) {
    upload.conflict = conflict;
    upload.status = 'conflict';
    upload.loaded = upload.total;
    this.conflictQueue.push(upload);
    this.render();
    this.showNextConflict();
  }

  private showNextConflict() {
    if (this.conflictDialogOpen) return;
    const upload = this.conflictQueue.shift();
    if (!upload || upload.status !== 'conflict' || !upload.conflict) return;
    this.conflictDialogOpen = true;
    const ask = window.EnderVault?.askFileConflictPolicy?.({
      ...upload.conflict,
      message: `"${upload.conflict.fileName}" already exists. Choose how to finish this upload.`,
    }) ?? Promise.resolve('default');
    void ask
      .then((policy) => this.resolveConflict(upload, policy))
      .catch(() => this.resolveConflict(upload, 'default'))
      .finally(() => {
        this.conflictDialogOpen = false;
        this.showNextConflict();
      });
  }

  private async resolveConflict(upload: ManagedUpload, policy: string) {
    if (!upload.conflict || upload.status !== 'conflict') return;
    upload.status = 'resolving';
    this.render();
    const body = new FormData();
    const csrf = window.EnderVault?.csrfPair();
    if (csrf) body.append(csrf.name, csrf.value);
    body.append('id', upload.conflict.id);
    body.append('conflictPolicy', policy);
    body.append('path', upload.destinationPath);
    try {
      const response = await window.EnderVault!.requestJson('/api/v1/files/upload-conflicts/resolve', {
        method: 'POST', body,
      });
      upload.redirectUrl = response.redirectUrl;
      window.EnderVault?.showNotification(response.notification);
      this.finish(upload, response.uploadedFile ? 'complete' : 'canceled');
    } catch (reason) {
      this.finish(upload, 'failed', reason instanceof Error ? reason.message : 'Conflict resolution failed.');
    }
  }
}

export function UploadManagerProvider({ children }: PropsWithChildren) {
  const { bootstrap } = useAdminApp();
  const [activeCount, setActiveCount] = useState(0);
  const managerRef = useRef<AdminUploadManager | null>(null);
  if (!managerRef.current) {
    managerRef.current = new AdminUploadManager(bootstrap.uploads.maxConcurrentUploads, setActiveCount);
  }
  const manager = managerRef.current;

  useEffect(() => {
    manager.setMaxConcurrentUploads(bootstrap.uploads.maxConcurrentUploads);
  }, [bootstrap.uploads.maxConcurrentUploads, manager]);

  useEffect(() => {
    const warnBeforeLeaving = (event: BeforeUnloadEvent) => {
      if (!manager.hasActiveUploads()) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warnBeforeLeaving);
    return () => window.removeEventListener('beforeunload', warnBeforeLeaving);
  }, [manager]);

  const startFiles = useCallback((files: File[], destinationPath: string) => {
    manager.startFiles(files, destinationPath);
  }, [manager]);
  const value = useMemo(() => ({ activeCount, startFiles }), [activeCount, startFiles]);
  return <UploadManagerContext.Provider value={value}>{children}</UploadManagerContext.Provider>;
}

export function useOptionalUploadManager() {
  return useContext(UploadManagerContext);
}

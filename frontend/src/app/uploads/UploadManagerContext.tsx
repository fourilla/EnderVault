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
import { type DirectoryRoot, type UploadSelection, rootSignature, validateRoot } from './collect-uploads';
import { DirectoryUpload } from './directory-upload';

type UploadStatus =
  | 'queued'
  | 'fingerprinting'
  | 'reserving'
  | 'uploading'
  | 'canceling'
  | 'conflict'
  | 'resolving'
  | 'pending'
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
  root?: DirectoryRoot;
  completedCount?: number;
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
  startSelection: (selection: UploadSelection, destinationPath: string) => void;
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
    const activityIds: string[] = [];
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
      activityIds.push(`upload-${upload.id}`);
    });
    this.startQueuedUploads();
    window.EnderVaultActivity?.announceStarted(activityIds);
  }

  startSelection(selection: UploadSelection, destinationPath: string) {
    selection.roots.forEach(validateRoot);
    const ids: string[] = [];
    for (const root of selection.roots) {
      const signature = rootSignature(root, destinationPath);
      if ([...this.uploads.values()].some((item) => item.root && activeStatuses.has(item.status)
        && rootSignature(item.root, item.destinationPath) === signature)) {
        window.EnderVault?.showToast('info', `"${root.name}" already has an active directory upload.`);
        continue;
      }
      const upload: ManagedUpload = {
        id: this.nextId++, file: new File([], root.name), root, destinationPath,
        loaded: 0, total: root.files.reduce((sum, item) => sum + item.file.size, 0),
        completedCount: 0, status: 'queued', message: '', cancelRequested: false,
      };
      this.uploads.set(upload.id, upload);
      this.queue.push(upload);
      ids.push(`upload-${upload.id}`);
    }
    this.startFiles(selection.files, destinationPath);
    window.EnderVaultActivity?.announceStarted(ids);
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
    if (['complete', 'conflict', 'resolving', 'pending'].includes(upload.status)) return 100;
    if (!upload.total) return 0;
    return Math.max(0, Math.min(100, Math.round((upload.loaded / upload.total) * 100)));
  }

  private statusText(upload: ManagedUpload) {
    const message = this.statusMessage(upload);
    if (!upload.root) return message;
    const format = window.EnderVaultActivity?.formatBytes ?? ((bytes: number) => `${bytes} B`);
    return `${upload.completedCount} / ${upload.root.files.length} files; ${format(upload.loaded)} / ${format(upload.total)}; ${message}`;
  }

  private statusMessage(upload: ManagedUpload) {
    if (upload.status === 'canceling') return 'Canceling...';
    if (upload.cancelRequested && upload.status === 'uploading') return 'Canceling...';
    if (upload.status === 'queued') return 'Waiting to upload';
    if (upload.status === 'complete') return 'Complete';
    if (upload.status === 'failed') return upload.message || 'Failed';
    if (upload.status === 'canceled') return 'Canceled';
    if (upload.status === 'conflict') return 'Waiting for conflict choice';
    if (upload.status === 'resolving') return 'Applying conflict choice...';
    if (upload.status === 'pending') return upload.message || 'Waiting in Pending Decisions';
    const format = window.EnderVaultActivity?.formatBytes ?? ((bytes: number) => `${bytes} B`);
    const progress = `${format(upload.loaded)} / ${format(upload.total)}`;
    return upload.message || (upload.root ? 'Uploading directory' : progress);
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
      const result = await upload.handle?.abort();
      if (upload.root && result) {
        upload.cancelRequested = result.status === 'CANCELED';
        this.finishDirectory(upload, result);
      } else this.finish(upload, 'canceled');
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
    if (upload.root) return this.sendDirectory(upload);
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
          if (upload.cancelRequested) return;
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

  private async sendDirectory(upload: ManagedUpload) {
    upload.status = 'uploading';
    try {
      upload.handle = new DirectoryUpload(upload.root!, upload.destinationPath, (bytes, count) => {
        upload.loaded = bytes;
        upload.completedCount = count;
        this.render();
      }, (message) => { upload.message = message; this.render(); });
      const result = await upload.handle.start();
      if (upload.cancelRequested) return;
      this.finishDirectory(upload, result);
    } catch (reason) {
      if (upload.cancelRequested) return;
      const retained = (reason as { stagingRetained?: boolean } | null)?.stagingRetained;
      const message = (reason instanceof Error ? reason.message : 'Directory upload failed.')
        + (retained ? ' Staged files are retained; reselect the same directory to retry before expiry.' : '');
      window.EnderVault?.showToast('error', message);
      this.finish(upload, 'failed', message);
    }
  }

  private finishDirectory(upload: ManagedUpload, result: EnderVaultUploadResult | null) {
    if (result && ['COMPLETED', 'PENDING'].includes(result.status)) {
      upload.completedCount = upload.root!.files.length;
      upload.loaded = upload.total;
    }
    if (result?.status === 'COMPLETED') this.finish(upload, 'complete');
    else if (result?.status === 'PENDING') {
      window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
      window.EnderVault?.showToast('info', `"${upload.file.name}" is waiting in Pending Decisions.`);
      this.finish(upload, 'pending', 'Waiting in Pending Decisions');
    } else if (!result || result.status === 'CANCELED') this.finish(upload, 'canceled');
    else throw new Error('Directory could not be finalized.');
  }

  private queueConflict(upload: ManagedUpload, conflict: UploadConflict) {
    upload.conflict = conflict;
    upload.status = 'conflict';
    upload.loaded = upload.total;
    this.conflictQueue.push(upload);
    this.render();
    window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
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
      closeValue: 'defer',
    }) ?? Promise.resolve('defer');
    void ask
      .then((policy) => policy === 'defer'
        ? this.deferConflict(upload)
        : this.resolveConflict(upload, policy))
      .catch(() => this.deferConflict(upload))
      .finally(() => {
        this.conflictDialogOpen = false;
        this.showNextConflict();
      });
  }

  private deferConflict(upload: ManagedUpload) {
    if (!upload.conflict || upload.status !== 'conflict') return;
    window.EnderVault?.showToast('info', `"${upload.file.name}" is waiting in Pending Decisions.`);
    this.finish(upload, 'pending', 'Waiting in Pending Decisions');
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
      window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
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
  const startSelection = useCallback((selection: UploadSelection, destinationPath: string) => {
    manager.startSelection(selection, destinationPath);
  }, [manager]);
  const value = useMemo(() => ({ activeCount, startFiles, startSelection }), [activeCount, startFiles, startSelection]);
  return <UploadManagerContext.Provider value={value}>{children}</UploadManagerContext.Provider>;
}

export function useUploadManager() {
  const value = useContext(UploadManagerContext);
  if (!value) throw new Error('useUploadManager must be used inside UploadManagerProvider.');
  return value;
}

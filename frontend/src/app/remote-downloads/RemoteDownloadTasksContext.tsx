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
import { useLocation } from 'react-router-dom';
import {
  cancelRemoteDownload,
  loadRemoteDownloadTasks,
} from '../../remote-download/remote-download-api';
import type { RemoteDownloadTask } from '../../remote-download/types';
import { useAdminApp } from '../AdminAppContext';

const POLL_INTERVAL_MS = 2_000;
const REMOTE_DOWNLOAD_PATH = '/admin/utils/remote-download';
const activityId = (task: RemoteDownloadTask) => `remote-${task.id}`;

interface RemoteDownloadTasksValue {
  tasks: RemoteDownloadTask[] | null;
  error: string;
  refresh: () => Promise<void>;
}

const RemoteDownloadTasksContext = createContext<RemoteDownloadTasksValue | null>(null);

export function RemoteDownloadTasksProvider({ children }: PropsWithChildren) {
  const { bootstrap } = useAdminApp();
  const location = useLocation();
  const [tasks, setTasks] = useState<RemoteDownloadTask[] | null>(null);
  const [error, setError] = useState('');
  const initialized = useRef(false);
  const knownIds = useRef(new Set<string>());
  const displayedIds = useRef(new Set<string>());
  const scheduledIds = useRef(new Set<string>());
  const pollTimer = useRef<number | null>(null);
  const running = useRef(false);
  const refreshRequested = useRef(false);
  const shouldPoll = useRef(false);
  const remoteEnabled = bootstrap.capabilities.remoteDownloads;
  const activityEnabled = bootstrap.tasks.activityPanelEnabled && Boolean(window.EnderVaultActivity);
  const routeNeedsTasks = location.pathname === REMOTE_DOWNLOAD_PATH || location.pathname === '/admin/dashboard';
  const synchronizationEnabled = remoteEnabled && (activityEnabled || routeNeedsTasks);
  const synchronizationEnabledRef = useRef(synchronizationEnabled);
  synchronizationEnabledRef.current = synchronizationEnabled;

  const clearPollTimer = useCallback(() => {
    if (pollTimer.current === null) return;
    window.clearTimeout(pollTimer.current);
    pollTimer.current = null;
  }, []);

  const clearRemoteActivity = useCallback(() => {
    displayedIds.current.forEach((id) => window.EnderVaultActivity?.remove(`remote-${id}`));
    displayedIds.current.clear();
    scheduledIds.current.clear();
  }, []);

  const publishActivity = useCallback((nextTasks: RemoteDownloadTask[]) => {
    const activity = window.EnderVaultActivity;
    if (!activityEnabled || !activity) return;
    const firstLoad = !initialized.current;
    const returnedIds = new Set(nextTasks.map((task) => task.id));

    nextTasks.forEach((task) => {
      const wasKnown = knownIds.current.has(task.id);
      if (!task.active && scheduledIds.current.has(task.id)) {
        knownIds.current.add(task.id);
        return;
      }
      const showTerminal = task.active
        || displayedIds.current.has(task.id)
        || (!firstLoad && !wasKnown);
      if (!task.active && !showTerminal) {
        knownIds.current.add(task.id);
        return;
      }

      displayedIds.current.add(task.id);
      activity.upsert({
        id: activityId(task),
        title: task.fileName || `Remote download #${task.shortId}`,
        type: 'REMOTE_DOWNLOAD',
        typeLabel: `Remote download - ${task.networkRouteLabel}`,
        status: task.status.toLowerCase(),
        percent: task.progressPercent,
        message: task.cancelRequested && task.active
          ? 'Canceling...'
          : task.message || task.progressLabel || task.statusLabel,
        cancelRequested: task.cancelRequested,
        cancelable: task.active,
        onCancel: async () => {
          const response = await cancelRemoteDownload(task.id);
          if (response.notification) window.EnderVault?.showNotification(response.notification);
        },
      });

      if (!task.active && !scheduledIds.current.has(task.id)) {
        scheduledIds.current.add(task.id);
        const delay = task.status === 'COMPLETE'
          ? bootstrap.tasks.completedDisplayMs
          : bootstrap.tasks.failedDisplayMs;
        activity.scheduleRemoval(activityId(task), delay);
      }
      knownIds.current.add(task.id);
    });

    displayedIds.current.forEach((id) => {
      if (returnedIds.has(id)) return;
      window.EnderVaultActivity?.remove(`remote-${id}`);
      displayedIds.current.delete(id);
      scheduledIds.current.delete(id);
      knownIds.current.delete(id);
    });
    initialized.current = true;
  }, [activityEnabled, bootstrap.tasks.completedDisplayMs, bootstrap.tasks.failedDisplayMs]);

  const acceptTasks = useCallback((nextTasks: RemoteDownloadTask[]) => {
    setTasks(nextTasks);
    setError('');
    shouldPoll.current = nextTasks.some((task) => task.active);
    publishActivity(nextTasks);
  }, [publishActivity]);

  const synchronize = useCallback(async (): Promise<void> => {
    if (!synchronizationEnabledRef.current || document.visibilityState !== 'visible') return;
    if (running.current) {
      refreshRequested.current = true;
      return;
    }

    running.current = true;
    try {
      acceptTasks(await loadRemoteDownloadTasks());
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Remote download tasks could not be loaded.');
    } finally {
      running.current = false;
      clearPollTimer();
      if (!synchronizationEnabledRef.current) {
        refreshRequested.current = false;
      } else if (refreshRequested.current) {
        refreshRequested.current = false;
        pollTimer.current = window.setTimeout(() => void synchronize(), 0);
      } else if (shouldPoll.current) {
        pollTimer.current = window.setTimeout(() => void synchronize(), POLL_INTERVAL_MS);
      }
    }
  }, [acceptTasks, clearPollTimer]);

  useEffect(() => {
    if (activityEnabled) return;
    clearRemoteActivity();
  }, [activityEnabled, clearRemoteActivity]);

  useEffect(() => {
    if (!synchronizationEnabled) {
      clearPollTimer();
      return undefined;
    }
    const refresh = () => void synchronize();
    const onVisibility = () => {
      if (document.visibilityState === 'visible') refresh();
      else clearPollTimer();
    };
    refresh();
    window.addEventListener('endervault:remote-downloads-changed', refresh);
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('focus', refresh);
    return () => {
      window.removeEventListener('endervault:remote-downloads-changed', refresh);
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('focus', refresh);
      clearPollTimer();
    };
  }, [clearPollTimer, synchronizationEnabled, synchronize]);

  const value = useMemo<RemoteDownloadTasksValue>(() => ({
    tasks,
    error,
    refresh: synchronize,
  }), [error, synchronize, tasks]);

  return (
    <RemoteDownloadTasksContext.Provider value={value}>
      {children}
    </RemoteDownloadTasksContext.Provider>
  );
}

export function useRemoteDownloadTasks() {
  const value = useContext(RemoteDownloadTasksContext);
  if (!value) throw new Error('useRemoteDownloadTasks must be used inside RemoteDownloadTasksProvider.');
  return value;
}

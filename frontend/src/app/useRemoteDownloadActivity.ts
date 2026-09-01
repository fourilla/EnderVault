import { useCallback, useEffect, useRef } from 'react';
import {
  cancelRemoteDownload,
  loadRemoteDownloadTasks,
} from '../remote-download/remote-download-api';
import type { RemoteDownloadTask } from '../remote-download/types';
import { useAdminApp } from './AdminAppContext';

const POLL_INTERVAL_MS = 2500;
const activityId = (task: RemoteDownloadTask) => `remote-${task.id}`;

export function useRemoteDownloadActivity() {
  const { bootstrap } = useAdminApp();
  const initialized = useRef(false);
  const knownIds = useRef(new Set<string>());
  const displayedIds = useRef(new Set<string>());
  const scheduledIds = useRef(new Set<string>());
  const pollTimer = useRef<number | null>(null);
  const running = useRef(false);
  const refreshRequested = useRef(false);
  const shouldPoll = useRef(false);

  const synchronize = useCallback(async (): Promise<void> => {
    if (!bootstrap.capabilities.remoteDownloads
      || !bootstrap.tasks.activityPanelEnabled || !window.EnderVaultActivity) return;
    if (document.visibilityState !== 'visible') return;
    if (running.current) {
      refreshRequested.current = true;
      return;
    }

    running.current = true;
    try {
      const tasks = await loadRemoteDownloadTasks();
      const firstLoad = !initialized.current;
      const returnedIds = new Set(tasks.map((task) => task.id));

      const publish = (task: RemoteDownloadTask, showTerminal: boolean): void => {
        if (!task.active && !showTerminal) return;
        const id = activityId(task);
        displayedIds.current.add(task.id);
        window.EnderVaultActivity?.upsert({
          id,
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
            if (response.task) publish(response.task, true);
          },
        });

        if (!task.active && !scheduledIds.current.has(task.id)) {
          scheduledIds.current.add(task.id);
          const delay = task.status === 'COMPLETE'
            ? bootstrap.tasks.completedDisplayMs
            : bootstrap.tasks.failedDisplayMs;
          window.EnderVaultActivity?.scheduleRemoval(id, delay);
        }
      };

      tasks.forEach((task) => {
        const wasKnown = knownIds.current.has(task.id);
        if (!task.active && scheduledIds.current.has(task.id)) {
          knownIds.current.add(task.id);
          return;
        }
        publish(task, task.active || displayedIds.current.has(task.id) || (!firstLoad && !wasKnown));
        knownIds.current.add(task.id);
      });

      displayedIds.current.forEach((id) => {
        if (!returnedIds.has(id)) {
          window.EnderVaultActivity?.remove(`remote-${id}`);
          displayedIds.current.delete(id);
          scheduledIds.current.delete(id);
          knownIds.current.delete(id);
        }
      });
      initialized.current = true;
      shouldPoll.current = tasks.some((task) => task.active);
    } finally {
      running.current = false;
      if (pollTimer.current !== null) window.clearTimeout(pollTimer.current);
      if (refreshRequested.current) {
        refreshRequested.current = false;
        pollTimer.current = window.setTimeout(() => void synchronize().catch(() => undefined), 0);
      } else {
        pollTimer.current = shouldPoll.current
          ? window.setTimeout(() => void synchronize().catch(() => undefined), POLL_INTERVAL_MS)
          : null;
      }
    }
  }, [bootstrap.capabilities.remoteDownloads, bootstrap.tasks.activityPanelEnabled,
    bootstrap.tasks.completedDisplayMs, bootstrap.tasks.failedDisplayMs]);

  useEffect(() => {
    if (!bootstrap.capabilities.remoteDownloads || !bootstrap.tasks.activityPanelEnabled) {
      displayedIds.current.forEach((id) => window.EnderVaultActivity?.remove(`remote-${id}`));
      displayedIds.current.clear();
      if (pollTimer.current !== null) window.clearTimeout(pollTimer.current);
      return undefined;
    }
    const refresh = () => void synchronize().catch(() => undefined);
    const onVisibility = () => {
      if (document.visibilityState === 'visible') refresh();
    };
    refresh();
    window.addEventListener('endervault:remote-downloads-changed', refresh);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('endervault:remote-downloads-changed', refresh);
      document.removeEventListener('visibilitychange', onVisibility);
      if (pollTimer.current !== null) window.clearTimeout(pollTimer.current);
    };
  }, [bootstrap.capabilities.remoteDownloads, bootstrap.tasks.activityPanelEnabled, synchronize]);
}

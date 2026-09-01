import { type FormEvent, useEffect, useRef, useState } from 'react';
import { useAdminApp } from '../app/AdminAppContext';
import { useRemoteDownloadTasks } from '../app/remote-downloads/RemoteDownloadTasksContext';
import { toastError } from '../shared/api/form-api';
import { PageHeader } from '../shared/layout/PageHeader';
import { CurlImportDialog, RemoteDownloadConfirmDialog, RemoteDownloadTaskDialog } from './RemoteDownloadDialogs';
import { RemoteDownloadTasks } from './RemoteDownloadTasks';
import {
  cancelRemoteDownload,
  deleteRemoteDownloadTask,
  discardRemoteDownloadInspection,
  inspectRemoteDownload,
  loadRemoteDownloadPage,
  startRemoteDownload,
} from './remote-download-api';
import type { RemoteDownloadInspection, RemoteDownloadTask, RemoteNetworkRoute } from './types';

const REMEMBER_DESTINATION_KEY = 'endervault.remoteDownload.rememberDestination';
const LAST_DESTINATION_KEY = 'endervault.remoteDownload.lastDestination';
const localValue = (key: string) => {
  try { return window.localStorage.getItem(key); } catch { return null; }
};

const rememberLocalValue = (key: string, value: string | null) => {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    // Browser privacy settings may disable localStorage.
  }
};

export function RemoteDownloadApp() {
  const { bootstrap } = useAdminApp();
  const { tasks, error: taskError } = useRemoteDownloadTasks();
  const [url, setUrl] = useState('');
  const [destination, setDestination] = useState('');
  const [networkRoute, setNetworkRoute] = useState<RemoteNetworkRoute>('global');
  const [connections, setConnections] = useState(1);
  const [skipInspection, setSkipInspection] = useState(false);
  const [skipInspectDefault, setSkipInspectDefault] = useState(false);
  const [rememberDestination, setRememberDestination] = useState(true);
  const [customHeaders, setCustomHeaders] = useState('');
  const [inspection, setInspection] = useState<RemoteDownloadInspection | null>(null);
  const [selectedTask, setSelectedTask] = useState<RemoteDownloadTask | null>(null);
  const [curlDialogOpen, setCurlDialogOpen] = useState(false);
  const [busy, setBusy] = useState<'inspect' | 'start' | ''>('');
  const [busyTaskId, setBusyTaskId] = useState('');
  const [error, setError] = useState('');
  const pendingRequestId = useRef<string | null>(null);
  const rememberedConnections = useRef(1);

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    void loadRemoteDownloadPage(controller.signal)
      .then((payload) => {
        const rememberedPreference = localValue(REMEMBER_DESTINATION_KEY);
        const remember = rememberedPreference === null ? true : rememberedPreference === 'true';
        const rememberedPath = remember ? localValue(LAST_DESTINATION_KEY) : null;
        setRememberDestination(remember);
        setDestination(rememberedPath ?? payload.defaultTargetDirectory ?? '');
        setSkipInspection(payload.skipInspectByDefault);
        setSkipInspectDefault(payload.skipInspectByDefault);
        setConnections(1);
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Remote downloads could not be loaded.');
      });
    return () => controller.abort();
  }, []);

  useEffect(() => () => {
    const requestId = pendingRequestId.current;
    pendingRequestId.current = null;
    if (requestId) void discardRemoteDownloadInspection(requestId).catch(() => undefined);
  }, []);

  useEffect(() => {
    setSelectedTask((current) => current
      ? tasks?.find((task) => task.id === current.id) ?? current
      : null);
  }, [tasks]);

  const toggleSkipInspection = (checked: boolean) => {
    if (checked) {
      rememberedConnections.current = connections;
      setConnections(1);
    } else {
      setConnections(rememberedConnections.current);
    }
    setSkipInspection(checked);
  };

  const toggleRememberDestination = (checked: boolean) => {
    setRememberDestination(checked);
    rememberLocalValue(REMEMBER_DESTINATION_KEY, String(checked));
    if (!checked) rememberLocalValue(LAST_DESTINATION_KEY, null);
  };

  const inspect = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy('inspect');
    try {
      const payload = await inspectRemoteDownload({
        url: url.trim(),
        path: destination.trim(),
        networkRoute,
        connections: skipInspection ? 1 : connections,
        skipInspection,
        customHeaders,
      });
      pendingRequestId.current = payload.requestId;
      setInspection(payload);
    } catch (reason) {
      toastError(reason, 'Remote file inspection failed.');
    } finally {
      setBusy('');
    }
  };

  const discardInspection = () => {
    const requestId = pendingRequestId.current;
    pendingRequestId.current = null;
    setInspection(null);
    if (requestId) void discardRemoteDownloadInspection(requestId).catch(() => undefined);
  };

  const start = async () => {
    const requestId = pendingRequestId.current;
    if (!requestId || busy) return;
    setBusy('start');
    try {
      const payload = await startRemoteDownload(requestId);
      pendingRequestId.current = null;
      setInspection(null);
      if (payload.notification) window.EnderVault?.showNotification(payload.notification);
      rememberLocalValue(REMEMBER_DESTINATION_KEY, String(rememberDestination));
      rememberLocalValue(LAST_DESTINATION_KEY, rememberDestination ? destination.trim() : null);
      setUrl('');
      setNetworkRoute('global');
      setConnections(1);
      rememberedConnections.current = 1;
      setSkipInspection(skipInspectDefault);
      setCustomHeaders('');
    } catch (reason) {
      toastError(reason, 'Remote download could not be started.');
    } finally {
      setBusy('');
    }
  };

  const runTaskAction = async (task: RemoteDownloadTask) => {
    setBusyTaskId(task.id);
    try {
      const payload = task.active
        ? await cancelRemoteDownload(task.id)
        : await deleteRemoteDownloadTask(task.id);
      if (payload.notification) window.EnderVault?.showNotification(payload.notification);
    } catch (reason) {
      toastError(reason, 'The remote download task action failed.');
    } finally {
      setBusyTaskId('');
    }
  };

  return (
    <div className="dashboard-workspace">
      <PageHeader title="Remote Download" />

      {(error || taskError) && (
        <section className="dashboard-panel browser-load-error" role="alert">{error || taskError}</section>
      )}
      {!tasks && !error && !taskError && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" /><span>Loading remote downloads...</span>
        </section>
      )}
      {tasks && (
        <>
          <section className="dashboard-panel remote-download-panel" aria-label="New remote download">
            <form className="remote-download-form" onSubmit={inspect}>
              <label>
                URL
                <input type="url" value={url} onChange={(event) => setUrl(event.currentTarget.value)}
                  placeholder="https://example.com/file.mp4" required disabled={Boolean(busy)} />
              </label>
              <label>
                Save to
                <div className="input-action-field">
                  <input id="remoteDownloadDestination" type="text" value={destination}
                    onInput={(event) => setDestination(event.currentTarget.value)}
                    onChange={(event) => setDestination(event.currentTarget.value)}
                    placeholder="Vault directory path, blank for root" disabled={Boolean(busy)} />
                  <button className="input-action-button" type="button" data-directory-picker-open
                    data-directory-picker-target="remoteDownloadDestination" title="Choose directory" aria-label="Choose directory"
                    disabled={Boolean(busy)}>
                    <i className="fas fa-folder-open" aria-hidden="true" />
                  </button>
                </div>
              </label>
              <button className="icon-button" type="submit" title="Inspect remote file" aria-label="Inspect remote file" disabled={Boolean(busy)}>
                <i className={`fas ${busy === 'inspect' ? 'fa-spinner fa-spin' : 'fa-cloud-arrow-down'}`} aria-hidden="true" />
              </button>
              <details className="remote-request-options">
                <summary>Advanced request options</summary>
                <div className="remote-request-options-body">
                  <label>
                    Network route
                    <select value={networkRoute} onChange={(event) => setNetworkRoute(event.currentTarget.value as RemoteNetworkRoute)} disabled={Boolean(busy)}>
                      <option value="global">Use global ({bootstrap.outboundRoute.label})</option>
                      <option value="direct">Direct for this download</option>
                      <option value="vpn-required">VPN for this download</option>
                    </select>
                  </label>
                  <label>
                    Connections
                    <input type="number" value={connections} min={1} max={8} required disabled={Boolean(busy) || skipInspection}
                      title={skipInspection ? 'Skipped inspection supports one connection only.' : undefined}
                      onChange={(event) => setConnections(Math.max(1, Math.min(8, Number(event.currentTarget.value) || 1)))} />
                  </label>
                  <label className="remote-request-toggle">
                    <span><strong>Remember last destination</strong><small>Keep the last queued destination in this browser.</small></span>
                    <input type="checkbox" checked={rememberDestination} disabled={Boolean(busy)}
                      onChange={(event) => toggleRememberDestination(event.currentTarget.checked)} />
                  </label>
                  <label className="remote-request-toggle">
                    <span><strong>Skip inspection</strong><small>No metadata request is sent before confirmation. Requires 1 connection.</small></span>
                    <input type="checkbox" checked={skipInspection} disabled={Boolean(busy)}
                      onChange={(event) => toggleSkipInspection(event.currentTarget.checked)} />
                  </label>
                  <div className="remote-request-field remote-custom-headers">
                    <div className="remote-request-field-heading">
                      <label htmlFor="remoteCustomHeaders">Custom headers</label>
                      <button className="ghost icon-text-button remote-import-button" type="button" disabled={Boolean(busy)}
                        onClick={() => setCurlDialogOpen(true)}>
                        <i className="fas fa-file-import" aria-hidden="true" /><span>Import cURL</span>
                      </button>
                    </div>
                    <textarea id="remoteCustomHeaders" className="remote-request-textarea" rows={6} spellCheck={false}
                      value={customHeaders} onChange={(event) => setCustomHeaders(event.currentTarget.value)}
                      placeholder="Header-Name: value" disabled={Boolean(busy)} />
                  </div>
                </div>
              </details>
            </form>
          </section>

          <RemoteDownloadTasks tasks={tasks} busyId={busyTaskId} details={setSelectedTask} action={(task) => void runTaskAction(task)} />
        </>
      )}

      <CurlImportDialog open={curlDialogOpen} close={() => setCurlDialogOpen(false)}
        apply={(nextUrl, nextHeaders) => { setUrl(nextUrl); setCustomHeaders(nextHeaders); }} />
      <RemoteDownloadConfirmDialog inspection={inspection} busy={busy === 'start'} cancel={discardInspection} start={() => void start()} />
      <RemoteDownloadTaskDialog task={selectedTask} close={() => setSelectedTask(null)} />
    </div>
  );
}

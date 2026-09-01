import { formData } from '../shared/api/form-api';
import type {
  RemoteDownloadActionResponse,
  RemoteDownloadInspection,
  RemoteDownloadPagePayload,
  RemoteDownloadTask,
  RemoteNetworkRoute,
} from './types';

const requestJson = <T>(url: string, options?: RequestInit) => {
  if (!window.EnderVault) throw new Error('EnderVault client services are unavailable.');
  return window.EnderVault.requestJson(url, options) as Promise<T>;
};

const notifyTasksChanged = <T>(result: T) => {
  window.dispatchEvent(new CustomEvent('endervault:remote-downloads-changed'));
  return result;
};

export const loadRemoteDownloadPage = (signal?: AbortSignal) => requestJson<RemoteDownloadPagePayload>(
  '/api/v1/remote-downloads',
  { signal },
);

export const loadRemoteDownloadTasks = () => requestJson<RemoteDownloadTask[]>('/api/v1/remote-downloads/tasks');

export const inspectRemoteDownload = (values: {
  url: string;
  path: string;
  networkRoute: RemoteNetworkRoute;
  connections: number;
  skipInspection: boolean;
  customHeaders: string;
}) => requestJson<RemoteDownloadInspection>('/api/v1/remote-downloads/inspect', {
  method: 'POST',
  body: formData({
    url: values.url,
    path: values.path,
    networkRoute: values.networkRoute,
    connections: values.connections,
    skipInspection: String(values.skipInspection),
    customHeaders: values.customHeaders,
  }),
});

export const startRemoteDownload = (requestId: string) => requestJson<RemoteDownloadActionResponse>(
  '/api/v1/remote-downloads',
  { method: 'POST', body: formData({ requestId }) },
).then(notifyTasksChanged);

export const discardRemoteDownloadInspection = (requestId: string) => requestJson<{ discarded: boolean }>(
  '/api/v1/remote-downloads/inspect/discard',
  { method: 'POST', body: formData({ requestId }) },
);

export const cancelRemoteDownload = (id: string) => requestJson<RemoteDownloadActionResponse>(
  '/api/v1/remote-downloads/cancel',
  { method: 'POST', body: formData({ id }) },
).then(notifyTasksChanged);

export const deleteRemoteDownloadTask = (id: string) => requestJson<RemoteDownloadActionResponse>(
  '/api/v1/remote-downloads/delete',
  { method: 'POST', body: formData({ id }) },
).then(notifyTasksChanged);

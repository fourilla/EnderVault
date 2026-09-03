import type { ActionResponse, FormValues, NotificationPayload } from './types';

const csrf = () => ({
  header: document.querySelector<HTMLMetaElement>('meta[name="_csrf_header"]')?.content ?? '',
  token: document.querySelector<HTMLMetaElement>('meta[name="_csrf"]')?.content ?? '',
});

const parseJson = async <T,>(response: Response): Promise<T | null> => {
  const contentType = response.headers.get('content-type') ?? '';
  return contentType.includes('application/json') ? (response.json() as Promise<T>) : null;
};

const sessionExpired = (response: Response, payload: unknown) =>
  response.status === 401
  || (response.status === 403 && payload === null)
  || (response.redirected && new URL(response.url).pathname === '/login');

export const showNotification = (notification?: NotificationPayload | null) => {
  if (notification) {
    window.EnderVaultToasts?.show(notification);
  }
};

export const getSettings = async <T,>(endpoint: string): Promise<T> => {
  const response = await fetch(endpoint, {
    headers: { Accept: 'application/json', 'X-Requested-With': 'fetch' },
    credentials: 'same-origin',
  });
  const payload = await parseJson<T>(response);
  if (!response.ok || payload === null) {
    throw Object.assign(new Error(sessionExpired(response, payload)
      ? 'Your session expired. Log in again before continuing.'
      : 'Settings could not be loaded.'), {
        status: response.status,
        sessionExpired: sessionExpired(response, payload),
      });
  }
  return payload;
};

export const saveSettings = async (endpoint: string, values: FormValues): Promise<ActionResponse> => {
  const body = new FormData();
  Object.entries(values).forEach(([name, value]) => {
    if (Array.isArray(value)) {
      value.forEach((item) => body.append(name, item));
      return;
    }
    if (typeof value === 'boolean') {
      if (value) {
        body.append(name, 'on');
      }
      return;
    }
    body.append(name, value);
  });

  const csrfValue = csrf();
  const response = await fetch(endpoint, {
    method: 'POST',
    body,
    headers: {
      Accept: 'application/json',
      'X-Requested-With': 'fetch',
      ...(csrfValue.header && csrfValue.token ? { [csrfValue.header]: csrfValue.token } : {}),
    },
    credentials: 'same-origin',
  });
  const payload = await parseJson<ActionResponse>(response);
  if (!response.ok || payload === null || !payload.ok) {
    throw new Error(sessionExpired(response, payload)
      ? 'Your session expired. Log in again before continuing.'
      : payload?.notification?.message ?? 'Settings could not be saved.');
  }
  showNotification(payload.notification);
  return payload;
};

export const csrfHeaders = () => {
  const value = csrf();
  return value.header && value.token ? { [value.header]: value.token } : {};
};

export const postJson = async <T,>(endpoint: string, body?: unknown): Promise<T> => {
  const response = await fetch(endpoint, {
    method: 'POST',
    body: body === undefined ? null : JSON.stringify(body),
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-Requested-With': 'fetch',
      ...csrfHeaders(),
    },
    credentials: 'same-origin',
  });
  const payload = await parseJson<T & { ok?: boolean; notification?: NotificationPayload }>(response);
  if (!response.ok || payload === null || payload.ok === false) {
    throw new Error(sessionExpired(response, payload)
      ? 'Your session expired. Log in again before continuing.'
      : payload?.notification?.message ?? 'The action failed.');
  }
  return payload;
};

export const showError = (error: unknown) => {
  showNotification({
    type: 'error',
    message: error instanceof Error ? error.message : 'The action failed.',
  });
};

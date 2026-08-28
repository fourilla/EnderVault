const enderVault = () => {
  if (!window.EnderVault) {
    throw new Error('EnderVault client services are unavailable.');
  }
  return window.EnderVault;
};

export const formData = (values: Record<string, string | string[] | number | undefined>) => {
  const body = new FormData();
  const csrf = enderVault().csrfPair();
  if (csrf) body.append(csrf.name, csrf.value);
  Object.entries(values).forEach(([name, value]) => {
    if (value == null) return;
    if (Array.isArray(value)) {
      value.forEach((item) => body.append(name, item));
    } else {
      body.append(name, String(value));
    }
  });
  return body;
};

export const postForm = async (
  url: string,
  values: Record<string, string | string[] | number | undefined>,
  resolveConflicts = false,
) => {
  const client = enderVault();
  const request = resolveConflicts
    ? client.requestJsonResolvingConflicts
    : client.requestJson;
  return request(url, { method: 'POST', body: formData(values) });
};

export const notify = (body: any) => {
  if (body?.notification) window.EnderVault?.showNotification(body.notification);
};

export const toastError = (reason: unknown, fallback: string) => {
  const message = reason instanceof Error ? reason.message : fallback;
  window.EnderVault?.showToast('error', message || fallback);
};

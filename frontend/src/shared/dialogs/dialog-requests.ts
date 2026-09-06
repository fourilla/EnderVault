export interface TextInputOptions {
  title?: string;
  message?: string;
  label?: string;
  placeholder?: string;
  initialValue?: string;
  confirmLabel?: string;
}
export interface ConfirmationOptions {
  title?: string;
  message?: string;
  confirmLabel?: string;
  danger?: boolean;
}
export interface ConflictOptions {
  message?: string;
  defaultPolicy?: string;
  closeValue?: string;
}
export type DialogRequest = { id: number } & (
  { kind: 'text'; options: TextInputOptions; resolve: (value: string | null) => void }
  | { kind: 'confirm'; options: ConfirmationOptions; resolve: (value: boolean) => void }
  | { kind: 'conflict'; options: ConflictOptions; resolve: (value: string) => void }
);

const cancelRequest = (request: DialogRequest) => {
  if (request.kind === 'text') request.resolve(null);
  else if (request.kind === 'confirm') request.resolve(false);
  else request.resolve(request.options.closeValue || 'default');
};

export function createDialogRequests() {
  let nextId = 0;
  let requests: DialogRequest[] = [];
  const listeners = new Set<() => void>();
  const notify = () => listeners.forEach((listener) => listener());
  const add = (request: DialogRequest) => { requests.push(request); notify(); };
  return {
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; },
    snapshot: () => requests[0] ?? null,
    askTextInput: (options: TextInputOptions = {}) => new Promise<string | null>((resolve) => {
      add({ id: ++nextId, kind: 'text', options, resolve });
    }),
    askConfirmation: (options: ConfirmationOptions = {}) => new Promise<boolean>((resolve) => {
      add({ id: ++nextId, kind: 'confirm', options, resolve });
    }),
    askFileConflictPolicy: (options: ConflictOptions = {}) => new Promise<string>((resolve) => {
      add({ id: ++nextId, kind: 'conflict', options, resolve });
    }),
    settle: (id: number, value: string | boolean | null) => {
      const request = requests[0];
      if (!request || request.id !== id) return;
      requests.shift();
      if (request.kind === 'text') request.resolve(typeof value === 'string' ? value : null);
      else if (request.kind === 'confirm') request.resolve(value === true);
      else request.resolve(typeof value === 'string' ? value : request.options.closeValue || 'default');
      notify();
    },
    cancelAll: () => {
      const canceled = requests;
      requests = [];
      canceled.forEach(cancelRequest);
      notify();
    },
    cancelPageRequests: () => {
      const canceled = requests.filter((request) => request.kind !== 'conflict');
      requests = requests.filter((request) => request.kind === 'conflict');
      canceled.forEach(cancelRequest);
      notify();
    },
  };
}

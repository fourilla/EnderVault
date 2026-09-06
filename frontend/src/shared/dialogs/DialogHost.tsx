import { useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { AppDialog } from './AppDialog';
import { createDialogRequests, type DialogRequest } from './dialog-requests';
import { createLegacyDialogBridge } from './legacy-dialogs';

function RequestDialog({ request, settle }: {
  request: DialogRequest;
  settle: (id: number, result: string | boolean | null) => void;
}) {
  const titleId = useId();
  const [value, setValue] = useState(request.kind === 'text' ? request.options.initialValue ?? '' : '');
  const [invalid, setInvalid] = useState(false);
  const cancel = () => settle(request.id, null);
  const text = request.kind === 'text' ? request.options : null;
  const danger = request.kind === 'confirm' && request.options.danger;
  if (request.kind === 'conflict') {
    const defer = request.options.closeValue === 'defer';
    const closeLabel = defer ? 'Decide later' : 'Use default policy';
    return <AppDialog open labelledBy={titleId} className="upload-conflict-dialog" onDismiss={cancel}>
      <div className="upload-conflict-card">
        <header className="upload-conflict-header">
          <div><h2 id={titleId}>File name conflict</h2>
            <p>{request.options.message || 'Choose how to handle this conflict.'} {defer
              ? 'Closing keeps the staged file in Pending Decisions.'
              : `Closing uses the default policy: ${request.options.defaultPolicy || 'cancel'}.`}</p></div>
          <button className="ghost icon-button action-icon" type="button" onClick={cancel} title={closeLabel} aria-label={closeLabel}>
            <i className="fas fa-xmark" aria-hidden="true" />
          </button>
        </header>
        <div className="upload-conflict-actions">
          <button className="ghost icon-text-button" type="button" onClick={() => settle(request.id, 'rename')}>Rename and Continue</button>
          <button className="danger icon-text-button" type="button" onClick={() => settle(request.id, 'overwrite')}>Overwrite</button>
          <button className="ghost icon-text-button" type="button" onClick={() => settle(request.id, 'cancel')}>Cancel</button>
        </div>
      </div>
    </AppDialog>;
  }
  return <AppDialog open labelledBy={titleId} className="text-input-dialog" onDismiss={cancel}>
    <form className="text-input-card" noValidate onSubmit={(event) => {
      event.preventDefault();
      if (text && !value.trim()) { setInvalid(true); return; }
      settle(request.id, text ? value.trim() : true);
    }}>
      <header className="text-input-header">
        <div><h2 id={titleId}>{request.options.title ?? (text ? 'Input' : 'Confirm action')}</h2>
          {request.options.message && <p>{request.options.message}</p>}</div>
        <button className="ghost icon-button action-icon" type="button" onClick={cancel} title="Cancel" aria-label="Cancel">
          <i className="fas fa-xmark" aria-hidden="true" />
        </button>
      </header>
      {text && <label className="text-input-field">{text.label ?? 'Name'}
        <input value={value} placeholder={text.placeholder} autoComplete="off" autoFocus
          aria-invalid={invalid || undefined} onChange={(event) => { setValue(event.target.value); setInvalid(false); }}
          onFocus={(event) => event.currentTarget.select()}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
          }} />
      </label>}
      {invalid && <p className="text-input-error" role="alert">A value is required.</p>}
      <div className="text-input-actions">
        <button className="ghost icon-text-button" type="button" onClick={cancel}>Cancel</button>
        <button className={`${danger ? 'danger' : 'primary'} icon-text-button`} type="submit">
          {request.options.confirmLabel ?? (text ? 'Create' : 'Confirm')}
        </button>
      </div>
    </form>
  </AppDialog>;
}

// Temporary bridge for existing React and legacy-JS callers; one renderer owns both.
export function DialogHost({ routeKey }: { routeKey: string }) {
  const [requests] = useState(createDialogRequests);
  const [legacy] = useState(createLegacyDialogBridge);
  const request = useSyncExternalStore(requests.subscribe, requests.snapshot);
  const previousRoute = useRef(routeKey);
  useLayoutEffect(() => {
    const core = window.EnderVault;
    if (!core) return;
    const previousText = core.askTextInput;
    const previousConfirm = core.askConfirmation;
    const previousConflict = core.askFileConflictPolicy;
    const previousOpen = core.openDialog;
    const previousClose = core.closeDialog;
    core.askTextInput = requests.askTextInput;
    core.askConfirmation = requests.askConfirmation;
    core.askFileConflictPolicy = requests.askFileConflictPolicy;
    core.openDialog = legacy.open;
    core.closeDialog = legacy.close;
    return () => {
      if (core.askTextInput === requests.askTextInput) core.askTextInput = previousText;
      if (core.askConfirmation === requests.askConfirmation) core.askConfirmation = previousConfirm;
      if (core.askFileConflictPolicy === requests.askFileConflictPolicy) core.askFileConflictPolicy = previousConflict;
      if (core.openDialog === legacy.open) core.openDialog = previousOpen;
      if (core.closeDialog === legacy.close) core.closeDialog = previousClose;
      legacy.closeAll();
      requests.cancelAll();
    };
  }, [legacy, requests]);
  useLayoutEffect(() => {
    if (previousRoute.current !== routeKey) { requests.cancelPageRequests(); legacy.closeAll(); }
    previousRoute.current = routeKey;
  }, [legacy, requests, routeKey]);
  return request ? <RequestDialog key={request.id} request={request} settle={requests.settle} /> : null;
}

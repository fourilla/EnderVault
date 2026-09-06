import { type FormEvent, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { parseCurl } from './curl-parser';
import type { RemoteDownloadInspection, RemoteDownloadTask } from './types';
import { AppDialog } from '../shared/dialogs/AppDialog';

export function CurlImportDialog({ open, close, apply }: {
  open: boolean;
  close: () => void;
  apply: (url: string, customHeaders: string) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [command, setCommand] = useState('');

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    try {
      const imported = parseCurl(command);
      apply(imported.url, imported.customHeaders);
      setCommand('');
      close();
      window.EnderVault?.showToast('success', imported.customHeaders
        ? 'URL and custom headers imported.'
        : 'URL imported.');
    } catch (reason) {
      window.EnderVault?.showToast('error', reason instanceof Error
        ? reason.message
        : 'The cURL command could not be imported.');
    }
  };

  const cancel = () => {
    setCommand('');
    close();
  };

  return (
    <dialog ref={dialogRef} className="text-input-dialog remote-curl-dialog" aria-labelledby="remoteCurlDialogTitle"
      onCancel={(event) => { event.preventDefault(); cancel(); }} onClose={close}>
      <form className="text-input-card" onSubmit={submit}>
        <header className="text-input-header">
          <div><h2 id="remoteCurlDialogTitle">Import cURL</h2><p>The command stays in this browser and replaces the current URL and headers.</p></div>
          <button className="ghost icon-button" type="button" onClick={cancel} title="Close" aria-label="Close">
            <i className="fas fa-xmark" aria-hidden="true" />
          </button>
        </header>
        <label className="text-input-field">
          cURL command
          <textarea className="remote-request-textarea" rows={10} maxLength={65_536} spellCheck={false}
            value={command} onChange={(event) => setCommand(event.currentTarget.value)}
            placeholder="curl 'https://example.com/file' -H 'Authorization: Bearer ...'" autoFocus />
        </label>
        <div className="remote-confirm-actions">
          <button className="ghost" type="button" onClick={cancel}>Cancel</button>
          <button type="submit">Import</button>
        </div>
      </form>
    </dialog>
  );
}

export function RemoteDownloadConfirmDialog({ inspection, busy, cancel, start }: {
  inspection: RemoteDownloadInspection | null;
  busy: boolean;
  cancel: () => void;
  start: () => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (inspection && !dialog.open) dialog.showModal();
    if (!inspection && dialog.open) dialog.close();
  }, [inspection]);

  const probe = inspection?.probe;
  return (
    <dialog ref={dialogRef} className="remote-confirm-dialog" aria-labelledby="remoteConfirmTitle"
      onCancel={(event) => { event.preventDefault(); if (!busy) cancel(); }} onClose={() => { if (inspection && !busy) cancel(); }}>
      <article className="remote-confirm-card">
        <header className="remote-confirm-header">
          <div><h2 id="remoteConfirmTitle">Confirm Download</h2><p>Check the remote file before the server starts downloading it.</p></div>
          <button className="ghost icon-button" type="button" disabled={busy} onClick={cancel} title="Cancel" aria-label="Cancel">
            <i className="fas fa-xmark" aria-hidden="true" />
          </button>
        </header>
        <dl className="remote-confirm-details">
          <div><dt>File name</dt><dd>{probe?.fileName || '-'}</dd></div>
          <div><dt>Save to</dt><dd>{probe?.targetPath || '-'}</dd></div>
          <div><dt>Size</dt><dd>{probe?.contentLengthLabel || '-'}</dd></div>
          <div><dt>Type</dt><dd>{probe?.contentTypeLabel || '-'}</dd></div>
          <div><dt>Final URL</dt><dd>{probe?.finalUrl || '-'}</dd></div>
          <div><dt>Network route</dt><dd>{probe?.networkRouteLabel || '-'}</dd></div>
          <div><dt>Inspection</dt><dd>{probe?.statusLabel || '-'}</dd></div>
          <div><dt>Range</dt><dd>{probe?.rangeCapabilityLabel || '-'}</dd></div>
          <div><dt>Request</dt><dd>{probe?.requestOptionsLabel || '-'}</dd></div>
          <div><dt>Detail</dt><dd>{probe?.detail || '-'}</dd></div>
        </dl>
        {probe?.warningLabel && <p className="remote-confirm-warning">{probe.warningLabel}</p>}
        <footer className="remote-confirm-actions">
          <button className="ghost" type="button" disabled={busy} onClick={cancel}>Cancel</button>
          <button className="icon-text-button" type="button" disabled={busy || !probe?.startAllowed} onClick={start}>
            <i className={`fas ${busy ? 'fa-spinner fa-spin' : 'fa-cloud-arrow-down'}`} aria-hidden="true" />
            <span>Start Download</span>
          </button>
        </footer>
      </article>
    </dialog>
  );
}

export function RemoteDownloadTaskDialog({ task, close }: {
  task: RemoteDownloadTask | null;
  close: () => void;
}) {
  const connections = task
    ? task.actualConnections > 0
      ? `${task.actualConnections} active / ${task.requestedConnections} requested`
      : `Pending / ${task.requestedConnections} requested`
    : '-';

  return (
    <AppDialog open={task !== null} className="remote-confirm-dialog" labelledBy="remoteTaskDialogTitle"
      onDismiss={close} dismissOnBackdrop>
      <article className="remote-confirm-card">
        <header className="remote-confirm-header">
          <div><h2 id="remoteTaskDialogTitle">Download Details</h2><p>{task ? `Task ${task.id}` : '-'}</p></div>
          <button className="ghost icon-button" type="button" onClick={close} title="Close" aria-label="Close">
            <i className="fas fa-xmark" aria-hidden="true" />
          </button>
        </header>
        <dl className="remote-confirm-details">
          <div><dt>Source</dt><dd>{task?.sourceUrl || '-'}</dd></div>
          <div><dt>Destination</dt><dd>{task?.targetPath || task?.targetDirectory || '/'}</dd></div>
          <div><dt>Route</dt><dd>{task?.networkRouteLabel || '-'}</dd></div>
          <div><dt>Connections</dt><dd>{connections}</dd></div>
          <div><dt>Pending decision</dt><dd>{task?.pendingDecisionId || '-'}</dd></div>
          <div><dt>Retries</dt><dd>{task?.retryCount ?? '-'}</dd></div>
          <div><dt>Created</dt><dd>{task?.createdLabel || '-'}</dd></div>
          <div><dt>Started</dt><dd>{task?.startedLabel || '-'}</dd></div>
          <div><dt>Finished</dt><dd>{task?.finishedLabel || '-'}</dd></div>
          <div><dt>Message</dt><dd>{task?.message || '-'}</dd></div>
        </dl>
      </article>
    </AppDialog>
  );
}

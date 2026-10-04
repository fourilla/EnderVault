import { useEffect, useState } from 'react';
import { AppDialog } from '../shared/dialogs/AppDialog';
import { loadPendingDecisions } from './pending-decision-api';
import { PendingDecisionActions } from './PendingDecisionActions';
import { usePendingDecisionActions } from './usePendingDecisionActions';
import type { PendingFileDecision } from './types';
import './pending-decision-dialog.css';

export function PendingDecisionDialog({ id, close, openMerge }: {
  id: string; close: () => void; openMerge: (id: string) => void;
}) {
  const [decision, setDecision] = useState<PendingFileDecision | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, reload] = useState(0);
  const actions = usePendingDecisionActions({ items: decision ? [decision] : [], enabled: Boolean(decision && !error),
    contextKey: id, nested: true, resolved: close, mergeStarted: close, busyChanged: setBusy });
  useEffect(() => {
    const controller = new AbortController();
    setError('');
    void loadPendingDecisions(controller.signal).then((result) => {
      if (controller.signal.aborted) return;
      const item = result.decisions.find((entry) => entry.id === id);
      if (!item) close();
      else if (item.mergeId) openMerge(item.mergeId);
      else setDecision(item);
    }).catch((reason: unknown) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Decision unavailable.');
    });
    return () => controller.abort();
  }, [id, revision, close, openMerge]);
  return <AppDialog open busy={busy} onDismiss={close} labelledBy="pendingDecisionTitle" className="text-input-dialog pending-decision-dialog">
    <article className="text-input-card">
      <header className="text-input-header"><h2 id="pendingDecisionTitle">{decision?.directory ? 'Directory conflict' : 'File conflict'}</h2>
        <button type="button" className="ghost icon-button" aria-label="Close" title="Close" disabled={busy} onClick={close}>
          <i className="fas fa-xmark" aria-hidden="true" /></button></header>
      {error ? <p role="alert">{error} <button type="button" onClick={() => reload((value) => value + 1)}>Retry</button></p>
        : !decision ? <p role="status">Loading decision...</p> : <>
          <div className="pending-decision-summary">
            <div className="pending-decision-item">
              <i className={`fas ${decision.directory ? 'fa-folder' : 'fa-file'}`} aria-hidden="true" />
              <strong>{decision.originalFilename}</strong>
            </div>
            <dl className="pending-decision-location">
              <dt>Destination</dt><dd>{decision.destinationLabel}</dd>
            </dl>
          </div>
          <div className="pending-decision-controls">
            <PendingDecisionActions decision={decision} actions={actions} labelled />
          </div>
        </>}
      <p className="pending-decision-hint">You can close this window or leave the page and resolve this later in Pending Decisions.</p>
    </article>
  </AppDialog>;
}

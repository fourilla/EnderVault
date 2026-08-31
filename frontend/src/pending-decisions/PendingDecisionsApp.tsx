import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { loadPendingDecisions, resolvePendingDecision } from './pending-decision-api';
import type { PendingFileDecision, PendingFileDecisionAction } from './types';

export function PendingDecisionsApp() {
  const location = useLocation();
  const [decisions, setDecisions] = useState<PendingFileDecision[] | null>(null);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    void loadPendingDecisions(controller.signal)
      .then((payload) => setDecisions(payload.decisions))
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Pending decisions could not be loaded.');
        }
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!decisions || !location.hash.startsWith('#decision-')) return;
    const target = document.getElementById(location.hash.slice(1));
    target?.scrollIntoView({ block: 'center' });
    target?.focus({ preventScroll: true });
  }, [decisions, location.hash]);

  const resolve = async (
    decision: PendingFileDecision,
    action: PendingFileDecisionAction,
    filename?: string,
  ) => {
    if (action === 'REPLACE' || action === 'DISCARD') {
      const confirmed = await window.EnderVault!.askConfirmation({
        title: action === 'REPLACE' ? 'Replace existing file' : 'Discard pending file',
        message: action === 'REPLACE'
          ? 'Replace the existing destination file with this staged file?'
          : 'Permanently discard this staged file?',
        confirmLabel: action === 'REPLACE' ? 'Replace' : 'Discard',
        danger: true,
      });
      if (!confirmed) return;
    }

    setBusyId(decision.id);
    try {
      const result = await resolvePendingDecision(decision.id, action, {
        filename,
        replaceConfirmed: action === 'REPLACE',
      });
      setDecisions((current) => current?.filter((item) => item.id !== result.removedId) ?? []);
    } catch (reason) {
      toastError(reason, 'Pending file resolution failed.');
    } finally {
      setBusyId('');
    }
  };

  const saveAs = async (decision: PendingFileDecision) => {
    const filename = await window.EnderVault!.askTextInput({
      title: 'Save pending file as',
      message: 'Choose a new name in the requested destination.',
      label: 'File name',
      initialValue: decision.originalFilename,
      confirmLabel: 'Save',
    });
    if (filename) await resolve(decision, 'SAVE_AS', filename);
  };

  return (
    <>
      <section className="pathbar">
        <div className="pathbar-title-group"><h1>Pending Decisions</h1></div>
      </section>

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {!decisions && !error && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" />
          <span>Loading pending decisions...</span>
        </section>
      )}
      {decisions && (
        <section className="dashboard-panel" aria-label="Pending file decisions">
          <header className="section-heading">
            <div>
              <h2>Files Awaiting Review</h2>
              <p>Resolve staged files that could not be placed at their requested destination.</p>
            </div>
            <span className="status-badge warning">{decisions.length} pending</span>
          </header>
          <p className="pending-decision-note">
            Replace is rejected if the existing target changed after this decision was created.
          </p>
          <div className="table-wrap compact-table">
            <table className="pending-decisions-table">
              <thead>
                <tr><th>File</th><th>Source</th><th>Destination</th><th>Size</th><th>Received</th><th>Actions</th></tr>
              </thead>
              <tbody>
                {decisions.map((decision) => (
                  <tr id={`decision-${decision.id}`} key={decision.id} tabIndex={-1}>
                    <td><span className="table-primary-text" title={decision.originalFilename}>{decision.originalFilename}</span></td>
                    <td>
                      <span>{decision.sourceLabel}</span>
                      {decision.submittedBy && <small title={decision.submittedBy}>From {decision.submittedBy}</small>}
                    </td>
                    <td><span className="table-primary-text" title={decision.destinationLabel}>{decision.destinationLabel}</span></td>
                    <td>{decision.sizeLabel}</td>
                    <td title={decision.createdAt}>{decision.createdLabel}</td>
                    <td>
                      <div className="table-actions">
                        <button className="ghost icon-button action-icon" type="button" title="Keep both" aria-label="Keep both"
                          disabled={Boolean(busyId)} onClick={() => void resolve(decision, 'KEEP_BOTH')}>
                          {icon('fas fa-copy')}
                        </button>
                        <button className="ghost icon-button action-icon" type="button" title="Save as" aria-label="Save as"
                          disabled={Boolean(busyId)} onClick={() => void saveAs(decision)}>
                          {icon('fas fa-pen')}
                        </button>
                        <button className="danger icon-button action-icon" type="button" title="Replace existing file"
                          aria-label="Replace existing file" disabled={Boolean(busyId)}
                          onClick={() => void resolve(decision, 'REPLACE')}>
                          {icon('fas fa-file-arrow-down')}
                        </button>
                        <button className="danger icon-button action-icon" type="button" title="Discard staged file"
                          aria-label="Discard staged file" disabled={Boolean(busyId)}
                          onClick={() => void resolve(decision, 'DISCARD')}>
                          {icon('fas fa-trash-can')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {decisions.length === 0 && (
                  <tr className="empty-row"><td colSpan={6} className="empty">No files are awaiting review.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </>
  );
}

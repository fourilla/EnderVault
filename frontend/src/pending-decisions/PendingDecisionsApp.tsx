import { LoadingState } from '../shared/layout/LoadingState';
import { useEffect, useState } from 'react';
import { useHashTarget } from '../shared/browser/useHashTarget';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { loadPendingDecisions } from './pending-decision-api';
import type { PendingFileDecision } from './types';
import { PendingDecisionActions } from './PendingDecisionActions';

export function PendingDecisionsApp() {
  const [decisions, setDecisions] = useState<PendingFileDecision[] | null>(null);
  const [error, setError] = useState('');
  const [refresh, reload] = useState(0);

  useEffect(() => {
    const update = () => reload((value) => value + 1);
    document.addEventListener('endervault:task-terminal', update);
    window.addEventListener('endervault:notifications-changed', update);
    return () => {
      document.removeEventListener('endervault:task-terminal', update);
      window.removeEventListener('endervault:notifications-changed', update);
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    setError('');
    const load = () => loadPendingDecisions(controller.signal)
      .then((payload) => {
        if (!controller.signal.aborted) { setDecisions(payload.decisions); setError(''); }
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Pending decisions could not be loaded.');
        }
      }).finally(() => {
        if (!controller.signal.aborted) timer = setTimeout(() => void load(), 5000);
      });
    void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [refresh]);

  useHashTarget(decisions, '#decision-', true);

  return (
    <>
      <PageHeader title="Pending Decisions" />

      <div className="page-feedback-layout">
      {error && <PageErrorPanel title="Pending decisions unavailable" message={error} stale={decisions !== null}
        actions={<button className="icon-text-button" type="button" onClick={() => reload((value) => value + 1)}>
          <i className="fas fa-arrows-rotate" aria-hidden="true" /><span>Retry</span>
        </button>} />}
      {!decisions && !error && (
        <LoadingState label="Loading pending decisions..." />
      )}
      {decisions && (
        <section className="dashboard-panel" aria-label="Pending decisions">
          <header className="section-heading">
            <div>
              <h2>Items Awaiting Review</h2>
              <p>Review pending uploads and directory transfers.</p>
            </div>
            <span className="status-badge warning">{decisions.length} pending</span>
          </header>
          <p className="pending-decision-note">
            Replace is rejected if the existing target changed after this decision was created.
          </p>
          <div className="table-wrap compact-table">
            <table className="pending-decisions-table">
              <thead>
                <tr><th>Item</th><th>Source</th><th>Destination</th><th>Size</th><th>Created</th><th>Status</th><th>Actions</th></tr>
              </thead>
              <tbody>
                {decisions.map((decision) => (
                  <tr id={`decision-${decision.id}`} key={decision.id} tabIndex={-1}>
                    <td><span className="table-primary-text" title={decision.originalFilename}>
                      {decision.directory && icon('fas fa-folder item-icon')}{decision.originalFilename}
                    </span></td>
                    <td>
                      <span>{decision.sourceLabel}</span>
                      {decision.submittedBy && <small title={decision.submittedBy}>From {decision.submittedBy}</small>}
                    </td>
                    <td><span className="table-primary-text" title={decision.destinationLabel}>{decision.destinationLabel}</span></td>
                    <td>{decision.sizeLabel}</td>
                    <td title={decision.createdAt}>{decision.createdLabel}</td>
                    <td>{decision.statusLabel}</td>
                    <td>
                      <PendingDecisionActions decision={decision}
                        resolved={(id) => setDecisions((current) => current?.filter((item) => item.id !== id) ?? [])} />
                    </td>
                  </tr>
                ))}
                {decisions.length === 0 && (
                  <tr className="empty-row"><td colSpan={7} className="empty">No items are awaiting review.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}
      </div>
    </>
  );
}

import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { toastError } from '../shared/api/form-api';
import { loadMetadataInspector, repairMetadataIssues, startMetadataScan } from './metadata-api';
import { MetadataIssueTable } from './MetadataIssueTable';
import type { MetadataPagePayload, MetadataTask } from './types';

export function MetadataInspectorApp() {
  const location = useLocation();
  const [payload, setPayload] = useState<MetadataPagePayload | null>(null);
  const [selectedAreas, setSelectedAreas] = useState<Set<string> | null>(null);
  const [selectedIssues, setSelectedIssues] = useState(new Set<string>());
  const [repairMessages, setRepairMessages] = useState<string[]>([]);
  const [refreshToken, setRefreshToken] = useState(0);
  const [busy, setBusy] = useState<'scan' | 'repair' | ''>('');
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    void loadMetadataInspector(controller.signal)
      .then((next) => {
        setPayload(next);
        setSelectedAreas((current) => current ?? new Set(next.areas.map((area) => area.name)));
        setSelectedIssues(new Set());
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Metadata inspector could not be loaded.');
        }
      });
    return () => controller.abort();
  }, [refreshToken]);

  useEffect(() => {
    const task = payload?.activeInspectionTask;
    if (task) window.EnderVaultServerTasks?.track(task);
  }, [payload?.activeInspectionTask?.id]);

  useEffect(() => {
    const onTaskTerminal = (event: Event) => {
      const task = (event as CustomEvent<MetadataTask>).detail;
      if (task?.type === 'METADATA_INSPECTION') setRefreshToken((value) => value + 1);
    };
    document.addEventListener('endervault:task-terminal', onTaskTerminal);
    return () => document.removeEventListener('endervault:task-terminal', onTaskTerminal);
  }, []);

  useEffect(() => {
    if (!payload || !location.hash.startsWith('#metadata-')) return;
    document.getElementById(location.hash.slice(1))?.scrollIntoView({ block: 'center' });
  }, [location.hash, payload]);

  const toggleArea = (name: string, checked: boolean) => {
    setSelectedAreas((current) => {
      const next = new Set(current ?? []);
      if (checked) next.add(name);
      else next.delete(name);
      return next;
    });
  };

  const scan = async () => {
    if (!selectedAreas?.size) return;
    setBusy('scan');
    try {
      const result = await startMetadataScan([...selectedAreas]);
      setPayload((current) => current ? { ...current, activeInspectionTask: result.task } : current);
      window.EnderVaultServerTasks?.track(result.task);
    } catch (reason) {
      toastError(reason, 'Metadata inspection could not be started.');
    } finally {
      setBusy('');
    }
  };

  const repair = async (repairAll: boolean) => {
    if (!repairAll && selectedIssues.size === 0) return;
    setBusy('repair');
    try {
      const result = await repairMetadataIssues([...selectedIssues], repairAll);
      setRepairMessages(result.messages ?? []);
      setSelectedIssues(new Set());
      setRefreshToken((value) => value + 1);
    } catch (reason) {
      toastError(reason, 'Metadata repair failed.');
    } finally {
      setBusy('');
    }
  };

  const report = payload?.report;
  const activeTask = payload?.activeInspectionTask;
  const controlsDisabled = Boolean(busy);

  return (
    <div className="dashboard-workspace settings-workspace metadata-workspace metadata-selection-enhanced">
      <section className="pathbar">
        <div className="pathbar-title-group"><h1>Metadata Inspector</h1></div>
      </section>

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {!payload && !error && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" />
          <span>Loading metadata inspector...</span>
        </section>
      )}
      {payload && (
        <>
          <section className="dashboard-panel settings-detail-panel">
            <header className="section-heading">
              <div>
                <h2>Scan Areas</h2>
                <p>Choose metadata stores to inspect. Repair actions only run after you select issues from the result.</p>
              </div>
              {activeTask && <span className="status-badge warning">Inspection running</span>}
            </header>

            <div className="metadata-scan-form">
              {activeTask && (
                <div className="metadata-running-note">
                  <i className={activeTask.iconClass} aria-hidden="true" />
                  <span>
                    <strong>{activeTask.title}</strong>
                    <small>{activeTask.statusLabel} - {activeTask.progressLabel} - {activeTask.message}</small>
                    <small>Starting a new scan will cancel the running inspection and replace it with the new selection.</small>
                  </span>
                </div>
              )}
              <div className="metadata-area-list">
                {payload.areas.map((area) => (
                  <label className="metadata-area-row" id={`metadata-scan-area-${area.name}`} key={area.name}>
                    <input type="checkbox" checked={selectedAreas?.has(area.name) ?? false} disabled={controlsDisabled}
                      onChange={(event) => toggleArea(area.name, event.currentTarget.checked)} />
                    <i className={area.iconClass} aria-hidden="true" />
                    <span><strong>{area.label}</strong><small>{area.description}</small></span>
                  </label>
                ))}
              </div>
              <div className="settings-actions-top metadata-actions-top">
                <button className="primary icon-text-button" type="button"
                  disabled={controlsDisabled || !selectedAreas?.size} onClick={() => void scan()}>
                  <i className="fas fa-magnifying-glass-chart" aria-hidden="true" />
                  <span>Scan Selected Areas</span>
                </button>
              </div>
            </div>
          </section>

          {!report ? (
            <section className="dashboard-panel settings-detail-panel">
              <header className="section-heading">
                <div><h2>Latest Report</h2><p>No metadata inspection report has been created yet.</p></div>
                <span className="status-badge expired">No report</span>
              </header>
              <p className="dashboard-empty">Select scan areas above and start an inspection task.</p>
            </section>
          ) : (
            <section className="dashboard-panel settings-detail-panel">
              <header className="section-heading">
                <div><h2>Latest Report</h2><p>Created at {report.createdAtLabel} / scanned at {report.scannedAtLabel}</p></div>
                <span className={`status-badge ${report.healthy ? 'active' : 'warning'}`}>
                  {report.healthy ? 'Healthy' : `${report.issueCount} issues`}
                </span>
              </header>

              <div className="metadata-repair-form">
                <div className="metadata-result-summary">
                  <span><strong>{report.issueCount}</strong> issues</span>
                  <span><strong>{report.repairableCount}</strong> repairable</span>
                  <span><strong>{report.selectedAreaCount}</strong> scanned areas</span>
                </div>

                {report.areaReports.map((areaReport) => (
                  <article className="metadata-area-result" id={`metadata-report-area-${areaReport.area.name}`} key={areaReport.area.name}>
                    <header className="settings-subsection-heading">
                      <div>
                        <h3><i className={areaReport.area.iconClass} aria-hidden="true" /><span>{areaReport.area.label}</span></h3>
                        <p>{areaReport.area.description}</p>
                      </div>
                      <span className={`status-badge ${areaReport.healthy ? 'active' : 'warning'}`}>
                        {areaReport.healthy ? 'Clean' : `${areaReport.issueCount} issues`}
                      </span>
                    </header>
                    {areaReport.healthy ? (
                      <p className="dashboard-empty">No consistency issues found in this area.</p>
                    ) : (
                      <MetadataIssueTable report={areaReport} selected={selectedIssues}
                        onSelectionChange={setSelectedIssues} disabled={controlsDisabled} />
                    )}
                  </article>
                ))}

                {repairMessages.length > 0 && (
                  <div className="metadata-repair-messages">
                    <h3>Repair Messages</h3>
                    <ul>{repairMessages.map((message, index) => <li key={`${index}:${message}`}>{message}</li>)}</ul>
                  </div>
                )}

                {report.repairableCount > 0 && (
                  <div className="settings-actions-top metadata-actions-top">
                    <button className="danger icon-text-button metadata-repair-selected-button" type="button"
                      disabled={controlsDisabled || selectedIssues.size === 0} onClick={() => void repair(false)}>
                      <i className="fas fa-screwdriver-wrench" aria-hidden="true" />
                      <span>Repair Selected Issues</span>
                    </button>
                    <button className="danger icon-text-button" type="button" disabled={controlsDisabled}
                      title="Run every repairable action in this report" onClick={() => void repair(true)}>
                      <i className="fas fa-broom" aria-hidden="true" /><span>Repair All</span>
                    </button>
                  </div>
                )}
              </div>
            </section>
          )}
        </>
      )}
    </div>
  );
}

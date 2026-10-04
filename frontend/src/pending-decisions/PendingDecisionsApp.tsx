import { LoadingState } from '../shared/layout/LoadingState';
import { type FormEvent, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useRouteSearch } from '../app/RouteSearch';
import { useHashTarget } from '../shared/browser/useHashTarget';
import { icon } from '../shared/browser/BrowserEntries';
import { StableTable } from '../shared/browser/StableTable';
import { SelectionHeader } from '../shared/browser/SelectionHeader';
import { useItemSelection } from '../shared/browser/useItemSelection';
import { useSelectionShortcuts } from '../shared/browser/useSelectionShortcuts';
import { useBrowserContextMenu } from '../shared/browser/useBrowserContextMenu';
import { useLocationGuard } from '../shared/browser/ListingHistoryContext';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { loadPendingDecisions, PendingDecisionLoadError } from './pending-decision-api';
import type { PendingBulkItemResult, PendingFileDecision } from './types';
import { PendingDecisionActions } from './PendingDecisionActions';
import { usePendingDecisionActions } from './usePendingDecisionActions';
import { keepPendingMenuContext, pendingDecisionMenuActions } from './pending-decision-menu-actions';
import { PendingDecisionSelectionActions } from './PendingDecisionSelectionActions';

const emptyDecisions: PendingFileDecision[] = [];
const itemKey = (decision: PendingFileDecision) => decision.id;
const keepRowClick = () => {};

export function PendingDecisionsApp() {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeQuery = searchParams.get('q') ?? '';
  const [query, setQuery] = useState(activeQuery);
  const [snapshot, setSnapshot] = useState<{ query: string; decisions: PendingFileDecision[] } | null>(null);
  const [feedback, setFeedback] = useState<{ query: string; message: string } | null>(null);
  const [refresh, reload] = useState(0);
  const [bulkFeedback, setBulkFeedback] = useState<{ query: string; failures: PendingBulkItemResult[] } | null>(null);
  const decisions = snapshot?.query === activeQuery ? snapshot.decisions : null;
  const error = feedback?.query === activeQuery ? feedback.message : '';
  const items = decisions ?? emptyDecisions;
  const selectable = decisions !== null && !error;
  const isCurrent = useLocationGuard();
  const selection = useItemSelection({ items, enabled: selectable, locationKey: activeQuery, itemKey,
    openItem: keepRowClick });
  const actions = usePendingDecisionActions({ items, enabled: selectable, contextKey: activeQuery,
    isCurrent, selectedIds: () => [...selection.selectedRef.current],
    bulkResolved: (result) => {
      const removed = new Set(result.results.filter((item) => item.status === 'RESOLVED' && item.removedId)
        .map((item) => item.removedId!));
      setSnapshot((current) => current ? { ...current,
        decisions: current.decisions.filter((item) => !removed.has(item.id)) } : current);
      setBulkFeedback({ query: activeQuery, failures: result.results.filter((item) => item.status !== 'RESOLVED') });
    },
    resolved: (id) => setSnapshot((current) => current ? {
      ...current, decisions: current.decisions.filter((item) => item.id !== id),
    } : current) });
  const menuContent = useMemo(() => ({ items, activeQuery, error, selected: selection.selected }),
    [items, activeQuery, error, selection.selected]);
  useBrowserContextMenu({
    menuId: 'pendingContextMenu', pageScope: 'pending-react', entries: () => items,
    itemKey, keyAttribute: 'data-decision-id', selectedRef: selection.selectedRef, setSelected: selection.setSelected,
    actions: () => pendingDecisionMenuActions(actions), contentKey: menuContent,
    contextKey: activeQuery,
    keepOnRefresh: (context) => selectable && keepPendingMenuContext(context, items, selection.selectedRef.current),
    errorMessage: 'Pending item resolution failed.',
  });
  useSelectionShortcuts({
    enabled: selectable && items.length > 0,
    contextKey: activeQuery,
    selectedCount: selection.selectedItems.length,
    selectAll: selection.selectAll,
    clearSelection: selection.clearSelection,
    scope: () => document.querySelector<HTMLElement>('.app-main'),
  });

  useEffect(() => setQuery(activeQuery), [activeQuery]);

  const search = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = query.trim();
    setSearchParams(trimmed ? { q: trimmed } : {});
  };

  useRouteSearch({ label: 'Search pending decisions', placeholder: 'Search items or destinations',
    appliedQuery: activeQuery,
    value: query, onChange: setQuery, onSubmit: search, schemaScope: 'pending-decisions', suggestionHidden: 'show',
    onReset: () => {
      if (!activeQuery) return;
      const next = new URLSearchParams(searchParams);
      next.delete('q');
      setSearchParams(next);
    } });

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
    let timer: ReturnType<typeof setTimeout> | undefined;
    let retry = true;
    setFeedback(null);
    const load = () => loadPendingDecisions(controller.signal, activeQuery)
      .then((payload) => {
        if (!controller.signal.aborted) {
          setSnapshot({ query: activeQuery, decisions: payload.decisions });
          setFeedback(null);
        }
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setFeedback({ query: activeQuery,
            message: reason instanceof Error ? reason.message : 'Pending decisions could not be loaded.' });
          // An invalid query will not recover by polling the same expression.
          if (reason instanceof PendingDecisionLoadError && reason.status === 400) retry = false;
        }
      }).finally(() => {
        if (!controller.signal.aborted && retry) timer = setTimeout(() => void load(), 5000);
      });
    void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [activeQuery, refresh]);

  useHashTarget(decisions, '#decision-', true);
  const failures = new Map(bulkFeedback?.query === activeQuery ? bulkFeedback.failures.map((item) => [item.id, item.message]) : []);

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
            <span className="status-badge warning">{decisions.length} {activeQuery ? 'matching' : 'pending'}</span>
          </header>
          <p className="pending-decision-note">
            Replace is rejected if the existing target changed after this decision was created.
          </p>
          <div className="table-wrap compact-table">
            <StableTable columns={['select', 'text', 'type', 'text', 'size', 'date', 'status', 'actions']}
              actionCount={4} className="pending-decisions-table">
              <thead>
                <tr>
                  <SelectionHeader total={items.length} selected={selection.selectedItems.length} disabled={!selectable}
                    onChange={(checked) => checked ? selection.selectAll() : selection.clearSelection()}
                    label="Select all pending decisions in this result" />
                  <th>Item</th><th>Source</th><th>Destination</th><th>Size</th><th>Created</th><th>Status</th><th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {decisions.map((decision) => (
                  <tr id={`decision-${decision.id}`} key={decision.id} tabIndex={-1}
                    className={selection.selected.has(decision.id) ? 'is-selected' : undefined}
                    data-context-item="true" data-decision-id={decision.id} {...selection.itemInteractionProps(decision)}>
                    <td className="select-cell">
                      <input type="checkbox" className="row-select-checkbox" checked={selection.selected.has(decision.id)}
                        disabled={!selectable} aria-label={`Select ${decision.originalFilename}`}
                        onChange={(event) => selection.selectItem(decision, event.currentTarget.checked)} />
                    </td>
                    <td><div className="table-item-label">
                      {decision.directory && icon('fas fa-folder item-icon')}
                      <span className="item-name"><OverflowMarquee text={decision.originalFilename} /></span>
                    </div></td>
                    <td>
                      <div className="table-cell-stack">
                        <OverflowMarquee text={decision.sourceLabel} />
                        {decision.submittedBy && <small><OverflowMarquee text={`From ${decision.submittedBy}`} /></small>}
                      </div>
                    </td>
                    <td><span className="table-primary-text"><OverflowMarquee text={decision.destinationLabel} /></span></td>
                    <td>{decision.sizeLabel}</td>
                    <td title={decision.createdAt}>{decision.createdLabel}</td>
                    <td><div className="table-cell-stack">
                      <span className="table-primary-text"><OverflowMarquee text={decision.statusLabel} /></span>
                      {failures.has(decision.id) && <small title={failures.get(decision.id)}>
                        <OverflowMarquee text={failures.get(decision.id)!} />
                      </small>}
                    </div></td>
                    <td>
                      <PendingDecisionActions decision={decision} actions={actions} />
                    </td>
                  </tr>
                ))}
                {decisions.length === 0 && (
                  <tr className="empty-row"><td colSpan={8} className="empty">
                    {activeQuery ? 'No pending decisions match this search.' : 'No items are awaiting review.'}
                  </td></tr>
                )}
              </tbody>
            </StableTable>
          </div>
        </section>
      )}
      </div>
      <PendingDecisionSelectionActions items={selection.selectedItems} actions={actions} />
    </>
  );
}

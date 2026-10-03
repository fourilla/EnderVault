import { LoadingState } from '../shared/layout/LoadingState';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { StableTable } from '../shared/browser/StableTable';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useRouteSearch } from '../app/RouteSearch';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { FloatingPageActions } from '../app/FloatingPageActions';
import { deleteTrashItem, emptyTrash, loadTrash, restoreTrashItem } from './trash-api';
import type { TrashItem, TrashPayload } from './types';
import './trash-app.css';

export function TrashApp() {
  const [params, setParams] = useSearchParams();
  const activeQuery = params.get('q') ?? '';
  const [snapshot, setSnapshot] = useState<{ query: string; payload: TrashPayload } | null>(null);
  const [loading, setLoading] = useState(true);
  const [feedback, setFeedback] = useState<{ query: string; message: string } | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [busyAction, setBusyAction] = useState('');
  const [query, setQuery] = useState(activeQuery);
  const payload = snapshot?.query === activeQuery ? snapshot.payload : null;
  const error = feedback?.query === activeQuery ? feedback.message : '';

  useEffect(() => setQuery(activeQuery), [activeQuery]);

  useRouteSearch({ label: 'Search trash', placeholder: 'Search trash...', value: query,
    appliedQuery: activeQuery, onChange: setQuery, schemaScope: 'trash',
    onSubmit: event => {
      event.preventDefault();
      const next = new URLSearchParams(params);
      if (query.trim()) next.set('q', query.trim()); else next.delete('q');
      setParams(next);
    },
    onReset: () => {
      if (!activeQuery) return;
      const next = new URLSearchParams(params);
      next.delete('q');
      setParams(next);
    },
  });

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setFeedback(null);
    void loadTrash(controller.signal, activeQuery)
      .then(data => {
        if (!controller.signal.aborted) setSnapshot({ query: activeQuery, payload: data });
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setFeedback({ query: activeQuery,
            message: reason instanceof Error ? reason.message : 'Trash items could not be loaded.' });
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [activeQuery, refreshToken]);

  const reload = () => setRefreshToken((value) => value + 1);

  const restore = async (item: TrashItem) => {
    setBusyAction(`restore:${item.id}`);
    try {
      await restoreTrashItem(item.id);
      reload();
    } catch (reason) {
      toastError(reason, 'Trash item could not be restored.');
    } finally {
      setBusyAction('');
    }
  };

  const deletePermanently = async (item: TrashItem) => {
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Permanently delete item',
      message: `Permanently delete ${item.originalName}? This cannot be undone.`,
      confirmLabel: 'Delete permanently',
      danger: true,
    });
    if (!confirmed) return;
    setBusyAction(`delete:${item.id}`);
    try {
      await deleteTrashItem(item.id);
      reload();
    } catch (reason) {
      toastError(reason, 'Trash item could not be permanently deleted.');
    } finally {
      setBusyAction('');
    }
  };

  const empty = async () => {
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Empty trash',
      message: 'Permanently delete every item in trash? This cannot be undone.',
      confirmLabel: 'Empty trash',
      danger: true,
    });
    if (!confirmed) return;
    setBusyAction('empty');
    try {
      await emptyTrash();
      reload();
    } catch (reason) {
      toastError(reason, 'Trash could not be emptied.');
    } finally {
      setBusyAction('');
    }
  };

  const items = payload?.items ?? [];
  return (
    <>
      <PageHeader title="Trash" />
      {payload && (items.length > 0 || activeQuery) && <FloatingPageActions mode="single" label="Empty trash" icon="fas fa-broom"
        danger disabled={Boolean(busyAction)} onAction={() => void empty()} />}

      <div className="page-feedback-layout">
      {error && <PageErrorPanel title="Trash unavailable" message={error} stale={payload !== null}
        actions={<button type="button" className="icon-text-button" disabled={loading} onClick={reload}>
          {icon('fas fa-arrows-rotate')}<span>Retry</span>
        </button>} />}
      {!payload && !error && (
        <LoadingState label="Loading trash..." />
      )}
      {payload && items.length > 0 && (
        <section className="table-wrap" aria-label="Trash items">
            <StableTable columns={['text', 'text', 'type', 'size', 'date', 'date', 'actions']} actionCount={2}>
              <thead>
                <tr>
                  <th>Name</th><th>Original path</th><th>Type</th><th>Size</th>
                  <th>Deleted</th><th>Expires</th><th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <span className="item-name" title={item.originalName}>
                        {icon(item.directory ? 'fas fa-folder item-icon' : 'fas fa-file item-icon')}
                        <OverflowMarquee text={item.originalName} />
                      </span>
                    </td>
                    <td><span className="path-cell"><OverflowMarquee text={item.originalPath} /></span></td>
                    <td>{item.typeLabel}</td>
                    <td>{item.sizeLabel}</td>
                    <td>{item.deletedLabel}</td>
                    <td>{item.expiresLabel}</td>
                    <td>
                      <div className="table-actions">
                        <button className="icon-button action-icon" type="button"
                          disabled={Boolean(busyAction)} title="Restore" aria-label="Restore"
                          onClick={() => void restore(item)}>
                          {icon('fas fa-rotate-left')}
                        </button>
                        <button className="danger icon-button action-icon" type="button"
                          disabled={Boolean(busyAction)} title="Permanently delete" aria-label="Permanently delete"
                          onClick={() => void deletePermanently(item)}>
                          {icon('fas fa-trash-can')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </StableTable>
        </section>
      )}
      {payload && items.length === 0 && <p className="empty browser-grid-empty">
        {activeQuery ? 'No trash items match this search.' : 'No trash items.'}
      </p>}
      </div>
    </>
  );
}

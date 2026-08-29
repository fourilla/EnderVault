import { useEffect, useState } from 'react';
import { toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { deleteTrashItem, emptyTrash, loadTrash, restoreTrashItem } from './trash-api';
import type { TrashItem, TrashPayload } from './types';

export function TrashApp() {
  const [payload, setPayload] = useState<TrashPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const [busyAction, setBusyAction] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadTrash(controller.signal)
      .then(setPayload)
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Trash items could not be loaded.');
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [refreshToken]);

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
      <section className="pathbar">
        <a className="ghost icon-button" href="/admin/dashboard"
          title="Back to dashboard" aria-label="Back to dashboard">
          {icon('fas fa-arrow-left')}
        </a>
        <div className="pathbar-title-group">
          <h1>Trash</h1>
          {items.length > 0 && (
            <button className="danger icon-button" type="button" disabled={Boolean(busyAction)}
              title="Empty trash" aria-label="Empty trash" onClick={() => void empty()}>
              {icon('fas fa-broom')}
            </button>
          )}
        </div>
      </section>

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {loading && !payload && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" />
          <span>Loading trash...</span>
        </section>
      )}
      {payload && items.length > 0 && (
        <section className="browser-section" aria-label="Trash items">
          <header className="section-heading"><h2>Items ({items.length})</h2></header>
          <div className="table-wrap">
            <table>
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
                        <span>{item.originalName}</span>
                      </span>
                    </td>
                    <td><span className="path-cell" title={item.originalPath}>{item.originalPath}</span></td>
                    <td>{item.typeLabel}</td>
                    <td>{item.sizeLabel}</td>
                    <td>{item.deletedLabel}</td>
                    <td>{item.expiresLabel}</td>
                    <td>
                      <div className="table-actions trash-row-actions">
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
            </table>
          </div>
        </section>
      )}
      {payload && items.length === 0 && <p className="empty browser-grid-empty">No trash items.</p>}
    </>
  );
}

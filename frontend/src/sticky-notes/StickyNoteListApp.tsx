import { type FormEvent, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useRouteSearch } from '../app/RouteSearch';
import { AppNavigationLink } from '../app/AppNavigationLink';
import { toastError } from '../shared/api/form-api';
import { PageHeader } from '../shared/layout/PageHeader';
import { deleteStickyNote, loadStickyNoteCatalog } from './sticky-note-catalog-api';
import type { StickyNoteCatalogItem } from './types';

export function StickyNoteListApp() {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeQuery = searchParams.get('q')?.trim() ?? '';
  const [query, setQuery] = useState(activeQuery);
  const [notes, setNotes] = useState<StickyNoteCatalogItem[] | null>(null);
  const [busyId, setBusyId] = useState('');
  const [error, setError] = useState('');

  useEffect(() => setQuery(activeQuery), [activeQuery]);

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    setNotes(null);
    void loadStickyNoteCatalog(activeQuery, controller.signal)
      .then((payload) => setNotes(payload.notes))
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'Sticky notes could not be loaded.');
        }
      });
    return () => controller.abort();
  }, [activeQuery]);

  useEffect(() => {
    const onDeleted = (event: Event) => {
      const id = String((event as CustomEvent<{ id?: string }>).detail?.id ?? '');
      if (id) setNotes((current) => current?.filter((item) => item.id !== id) ?? current);
    };
    document.addEventListener('endervault:sticky-note-deleted', onDeleted);
    return () => document.removeEventListener('endervault:sticky-note-deleted', onDeleted);
  }, []);

  const search = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = query.trim();
    setSearchParams(trimmed ? { q: trimmed } : {});
  };

  useRouteSearch({ label: 'Search sticky notes', placeholder: 'Search notes or contexts',
    value: query, onChange: setQuery, onSubmit: search,
    onReset: activeQuery ? () => setSearchParams({}) : undefined });

  const remove = async (note: StickyNoteCatalogItem) => {
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Delete sticky note',
      message: 'Delete this sticky note? This cannot be undone.',
      confirmLabel: 'Delete',
      danger: true,
    });
    if (!confirmed) return;

    setBusyId(note.id);
    try {
      const body = await deleteStickyNote(note.id);
      setNotes((current) => current?.filter((item) => item.id !== body.deletedId) ?? []);
      document.dispatchEvent(new CustomEvent('endervault:sticky-note-deleted', {
        detail: { id: body.deletedId },
      }));
    } catch (reason) {
      toastError(reason, 'Sticky note could not be deleted.');
    } finally {
      setBusyId('');
    }
  };

  return (
    <div className="dashboard-workspace">
      <PageHeader title="Sticky Notes" />

      <section className="dashboard-panel" aria-label="Sticky note manager">
        <header className="section-heading">
          <div><h2>All Notes</h2><p>Review notes attached to pages, files, directories, and bookmarks.</p></div>
          <span className="status-badge info">{notes?.length ?? 0} note(s)</span>
        </header>

        {error && <div className="browser-load-error" role="alert">{error}</div>}
        {!notes && !error && (
          <div className="browser-load-progress" role="status" aria-live="polite">
            <i className="fas fa-spinner fa-spin" aria-hidden="true" /><span>Loading sticky notes...</span>
          </div>
        )}
        {notes && (
          <div className="table-wrap compact-table">
            <table>
              <thead><tr><th>Note</th><th>Context</th><th>Surface</th><th>Updated</th><th>Status</th><th>Actions</th></tr></thead>
              <tbody>
                {notes.map((note) => (
                  <tr key={note.id}>
                    <td><span className="table-primary-text" title={note.content}>{note.summary}</span></td>
                    <td><span className="table-primary-text" title={note.contextLabel}>{note.contextLabel}</span><small>{note.targetType}</small></td>
                    <td>{note.surfaceLabel}</td>
                    <td title={note.updatedLabel}>{note.updatedLabel}</td>
                    <td><span className={`status-badge ${note.targetExists ? 'active' : 'expired'}`}>{note.targetExists ? 'Available' : 'Orphan'}</span></td>
                    <td>
                      <div className="table-actions">
                        {note.openUrl && (
                          <AppNavigationLink className="button-link ghost icon-button action-icon" href={note.openUrl}
                            title="Open target" aria-label="Open target">
                            <i className="fas fa-arrow-up-right-from-square" aria-hidden="true" />
                          </AppNavigationLink>
                        )}
                        <button className="danger icon-button action-icon" type="button" disabled={busyId === note.id}
                          title="Delete sticky note" aria-label="Delete sticky note" onClick={() => void remove(note)}>
                          <i className="fas fa-trash-can" aria-hidden="true" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {notes.length === 0 && <tr className="empty-row"><td colSpan={6} className="empty">No sticky notes found.</td></tr>}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

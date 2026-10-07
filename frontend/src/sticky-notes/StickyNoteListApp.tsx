import { LoadingState } from '../shared/layout/LoadingState';
import { type FormEvent, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useRouteSearch } from '../app/RouteSearch';
import { PageHeader } from '../shared/layout/PageHeader';
import { PageErrorPanel } from '../shared/layout/PageErrorPanel';
import { StableTable } from '../shared/browser/StableTable';
import { useTableColumns } from '../shared/browser/useTableColumns';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { ListItemActions } from '../shared/browser/ListItemActions';
import { ListItemSelectionActions } from '../shared/browser/ListItemSelectionActions';
import { SelectionHeader } from '../shared/browser/SelectionHeader';
import { useSelectableActionList } from '../shared/browser/useSelectableActionList';
import { stickyNoteItemIdentity, stickyNoteItemKey, stickyNoteListActions } from './sticky-note-list-actions';
import { loadStickyNoteCatalog } from './sticky-note-catalog-api';
import type { StickyNoteCatalogItem } from './types';

const emptyNotes: StickyNoteCatalogItem[] = [];

export function StickyNoteListApp() {
  const table = useTableColumns(['select', 'text', 'text', 'type', 'date', 'status', 'actions']);
  const [searchParams, setSearchParams] = useSearchParams();
  const activeQuery = searchParams.get('q')?.trim() ?? '';
  const [query, setQuery] = useState(activeQuery);
  const [snapshot, setSnapshot] = useState<{ query: string; notes: StickyNoteCatalogItem[] } | null>(null);
  const [feedback, setFeedback] = useState<{ query: string; message: string } | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const notes = snapshot?.query === activeQuery ? snapshot.notes : null;
  const error = feedback?.query === activeQuery ? feedback.message : '';
  const items = notes ?? emptyNotes;
  const selectable = notes !== null && !error;
  const reload = () => setRefreshToken(value => value + 1);
  const { selection, actions, listRef, failures } = useSelectableActionList({ items, enabled: selectable,
    contextKey: activeQuery, itemKey: stickyNoteItemKey, itemIdentity: stickyNoteItemIdentity, definitions: stickyNoteListActions,
    reload, itemLabel: 'sticky notes', deleteActionId: 'note-delete', menuId: 'stickyNoteListContextMenu',
    pageScope: 'sticky-notes-react', keyAttribute: 'data-note-id',
    removeApplied: ids => setSnapshot(current => current && current.query === activeQuery
      ? { ...current, notes: current.notes.filter(item => !ids.has(item.id)) } : current) });

  useEffect(() => setQuery(activeQuery), [activeQuery]);

  useEffect(() => {
    const controller = new AbortController();
    setFeedback(null);
    void loadStickyNoteCatalog(activeQuery, controller.signal)
      .then((payload) => {
        if (!controller.signal.aborted) setSnapshot({ query: activeQuery, notes: payload.notes });
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setFeedback({ query: activeQuery, message: reason instanceof Error ? reason.message : 'Sticky notes could not be loaded.' });
        }
      });
    return () => controller.abort();
  }, [activeQuery, refreshToken]);

  useEffect(() => {
    const onDeleted = (event: Event) => {
      const id = String((event as CustomEvent<{ id?: string }>).detail?.id ?? '');
      if (id) setSnapshot(current => current ? { ...current, notes: current.notes.filter(item => item.id !== id) } : current);
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
    appliedQuery: activeQuery,
    value: query, onChange: setQuery, onSubmit: search, schemaScope: 'sticky-notes',
    onReset: () => {
      if (!activeQuery) return;
      const next = new URLSearchParams(searchParams);
      next.delete('q');
      setSearchParams(next);
    } });

  return (
    <div className="dashboard-workspace">
      <PageHeader title="Sticky Notes" />
      {notes && items.length > 0 && <ListItemSelectionActions items={selection.selectedItems}
        itemKey={stickyNoteItemKey} actions={actions} label="Sticky note actions" />}

      {error && <PageErrorPanel title="Sticky notes unavailable" message={error} stale={notes !== null}
        actions={<button type="button" className="icon-text-button"
          onClick={reload}>
          <i className="fas fa-arrows-rotate" aria-hidden="true" /><span>Retry</span>
        </button>} />}

      <section ref={listRef} className="dashboard-panel" aria-label="Sticky note manager">
        <header className="section-heading">
          <div><h2>All Notes</h2><p>Review notes attached to pages, files, directories, and bookmarks.</p></div>
          <span className="status-badge info">{notes?.length ?? 0} note(s)</span>
        </header>

        {!notes && !error && (
          <LoadingState label="Loading sticky notes..." />
        )}
        {notes && (
          <div className="table-wrap compact-table">
            <StableTable columns={table.columns} actionCount={2}>
              <thead><tr><SelectionHeader total={items.length} selected={selection.selectedItems.length} disabled={!selectable}
                onChange={checked => checked ? selection.selectAll() : selection.clearSelection()} label="Select all sticky notes in this result" />
                <th>Note</th><th>Context</th><th>Surface</th><th>Updated</th><th>Status</th>{table.showActions && <th>Actions</th>}</tr></thead>
              <tbody>
                {notes.map((note) => (
                  <tr key={note.id} data-context-item="true" data-note-id={note.id}
                    className={selection.selected.has(note.id) ? 'is-selected' : undefined} {...selection.itemInteractionProps(note)}>
                    <td className="select-cell"><input type="checkbox" className="row-select-checkbox" checked={selection.selected.has(note.id)}
                      disabled={!selectable} aria-label={`Select ${note.summary || 'sticky note'}`}
                      onChange={event => selection.selectItem(note, event.currentTarget.checked)} /></td>
                    <td><span className="table-primary-text" title={note.content}><OverflowMarquee text={note.summary} /></span>
                      {failures.has(note.id) && <small role="status">{failures.get(note.id)}</small>}</td>
                    <td><span className="table-primary-text"><OverflowMarquee text={note.contextLabel} /></span><small>{note.targetType}</small></td>
                    <td>{note.surfaceLabel}</td>
                    <td title={note.updatedLabel}>{note.updatedLabel}</td>
                    <td><span className={`status-badge ${note.targetExists ? 'active' : 'expired'}`}>{note.targetExists ? 'Available' : 'Orphan'}</span></td>
                    {table.showActions && <td>
                      <ListItemActions item={note} itemKey={stickyNoteItemKey} actions={actions} />
                    </td>}
                  </tr>
                ))}
                {notes.length === 0 && <tr className="empty-row"><td colSpan={table.columnCount} className="empty">No sticky notes found.</td></tr>}
              </tbody>
            </StableTable>
          </div>
        )}
      </section>
    </div>
  );
}

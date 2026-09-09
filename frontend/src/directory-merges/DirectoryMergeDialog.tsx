import { useEffect, useState } from 'react';
import { AppDialog } from '../shared/dialogs/AppDialog';
import { BrowserPagination } from '../shared/browser/BrowserPagination';
import { toastError } from '../shared/api/form-api';
import { mergeChoices, mergeGet, runMerge, saveMergeChoices, type MergeDetail, type MergeChoice } from './merge-api';
import './directory-merges.css';

const labels: Record<MergeChoice, string> = {
  OVERWRITE: 'Overwrite file', KEEP_BOTH: 'Keep both', SKIP: 'Skip', DISCARD_UPLOAD: 'Discard uploaded item',
};

export function DirectoryMergeDialog({ id, close, changed }: { id: string; close: () => void; changed: () => void }) {
  const [data, setData] = useState<MergeDetail | null>(null);
  const [page, setPage] = useState(0);
  const [revision, refresh] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setError('');
    void mergeGet<MergeDetail>(`/${encodeURIComponent(id)}?conflictsOnly=true&page=${page}&size=50`, controller.signal)
      .then(setData).catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Review unavailable.');
      });
    return () => controller.abort();
  }, [id, page, revision]);

  const save = async (choices: Record<string, MergeChoice>) => {
    if (!data || busy) return;
    setBusy(true);
    try { await saveMergeChoices(id, data.review.revision, choices); changed(); }
    catch (reason) { toastError(reason, 'Decisions could not be saved.'); }
    finally { setBusy(false); refresh((value) => value + 1); }
  };
  const start = async (replan: boolean) => {
    if (!data || busy) return;
    setBusy(true);
    try { await runMerge(data.review, replan); changed(); close(); }
    catch (reason) { toastError(reason, 'Merge could not be started.'); refresh((value) => value + 1); }
    finally { setBusy(false); }
  };
  const review = data?.review;
  const needsReview = review?.run?.phase === 'NEEDS_REVIEW';
  const pageChoices = data?.entries.items.length ? mergeChoices(data.review.operation, 'TYPE_CONFLICT') : [];
  return <AppDialog open busy={busy} onDismiss={close} labelledBy="directoryMergeTitle"
    className="text-input-dialog directory-merge-dialog">
    <article className="text-input-card">
      <header className="text-input-header">
        <div><h2 id="directoryMergeTitle">Directory Merge</h2><p>{review?.destinationPath}</p></div>
        <button className="ghost icon-button" type="button" disabled={busy} onClick={close} title="Close" aria-label="Close">
          <i className="fas fa-xmark" aria-hidden="true" />
        </button>
      </header>
      {error && <p role="alert">{error}<button type="button" className="ghost" onClick={() => refresh((value) => value + 1)}>Retry</button></p>}
      {!data && !error && <p role="status"><i className="fas fa-spinner fa-spin" aria-hidden="true" /> Loading review...</p>}
      {data && <>
        <p>{data.review.itemCount} items / {data.review.conflictCount} conflicts</p>
        {needsReview && <p role="status">Items changed after approval. A new review is required.</p>}
        {review?.editable && <label>Apply to this page
          <select value="" disabled={busy || !data.entries.items.length} onChange={(event) => {
            const choice = event.currentTarget.value as MergeChoice;
            if (choice) void save(Object.fromEntries(data.entries.items.map((entry) => [entry.id, choice])));
          }}>
            <option value="">Choose...</option>
            {data.entries.items.every((entry) => entry.conflict === 'FILE_CONFLICT') && <option value="OVERWRITE">Overwrite files</option>}
            {pageChoices.map((choice) => <option key={choice} value={choice}>{labels[choice]}</option>)}
          </select>
        </label>}
        <div className="table-wrap compact-table directory-merge-entries">
          <table><thead><tr><th>Source</th><th>Destination</th><th>Decision</th></tr></thead>
            <tbody>{data.entries.items.map((entry) => <tr key={entry.id}>
              <td><span className="table-primary-text" title={entry.relativePath}>{entry.relativePath || '/'}</span><small>{entry.sourceKind}</small></td>
              <td><span className="table-primary-text" title={entry.plannedTargetPath}>{entry.plannedTargetPath}</span><small>{entry.targetKind}</small></td>
              <td><select aria-label={`Decision for ${entry.relativePath || '/'}`} disabled={busy || !review?.editable}
                value={entry.choice || ''} onChange={(event) => { if (event.currentTarget.value) void save({ [entry.id]: event.currentTarget.value as MergeChoice }); }}>
                <option value="">Choose...</option>
                {mergeChoices(data.review.operation, entry.conflict).map((choice) => <option key={choice} value={choice}>{labels[choice]}</option>)}
              </select></td>
            </tr>)}{!data.entries.total && <tr><td colSpan={3}>No conflicts.</td></tr>}</tbody>
          </table>
        </div>
        <BrowserPagination page={{ number: page + 1, totalPages: Math.ceil(data.entries.total / data.entries.size) }}
          onPageChange={(value) => setPage(value - 1)} disabled={busy} ariaLabel="Merge conflict pages" />
        <footer className="directory-merge-actions">
          <button type="button" className="ghost" onClick={close} disabled={busy}>Close</button>
          {needsReview ? <button type="button" disabled={busy} onClick={() => void start(true)}>Review changed items</button>
            : <button type="button" disabled={busy || !review?.fullyReviewed || review?.run?.phase === 'COMPLETE'}
              onClick={() => void start(false)}>{review?.editable ? 'Apply merge' : 'Resume merge'}</button>}
        </footer>
      </>}
    </article>
  </AppDialog>;
}

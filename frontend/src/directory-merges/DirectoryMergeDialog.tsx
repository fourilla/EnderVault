import { useEffect, useState } from 'react';
import { AppDialog } from '../shared/dialogs/AppDialog';
import { BrowserPagination } from '../shared/browser/BrowserPagination';
import { toastError } from '../shared/api/form-api';
import { abandonMerge, abandonRemainingCopy, mergeChoices, mergeGet, runMerge, saveMergeChoices, saveAllMergeChoices, type MergeDetail, type MergeChoice } from './merge-api';
import './directory-merges.css';

const labels: Record<MergeChoice, string> = {
  OVERWRITE: 'Overwrite file', KEEP_BOTH: 'Keep both', SKIP: 'Skip', DISCARD_UPLOAD: 'Discard uploaded item',
};

const stages = {
  PUBLICATION_PENDING: 'Publication pending', FINALIZATION_PENDING: 'Finalization pending',
  NEEDS_REVIEW: 'Needs review', COMPLETE: 'Completed', RETAINED: 'Retained',
};

export function DirectoryMergeDialog({ id, close, changed }: { id: string; close: () => void; changed: () => void }) {
  const [data, setData] = useState<MergeDetail | null>(null);
  const [page, setPage] = useState(0);
  const [revision, refresh] = useState(0);
  const [error, setError] = useState('');
  const [missing, setMissing] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setError('');
    setMissing(false);
    void mergeGet<MergeDetail>(`/${encodeURIComponent(id)}?conflictsOnly=true&remainingOnly=true&page=${page}&size=50`, controller.signal)
      .then((result) => { if (!controller.signal.aborted) setData(result); }).catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          if ((reason as { status?: number } | null)?.status === 404) setMissing(true);
          else setError(reason instanceof Error ? reason.message : 'Review unavailable.');
        }
      });
    return () => controller.abort();
  }, [id, page, revision]);
  const finished = data?.review.run?.phase === 'COMPLETE' || data?.review.run?.phase === 'ABANDONED';
  useEffect(() => {
    if (finished || missing) { close(); changed(); }
  }, [finished, missing, close, changed]);

  const save = async (choices: Record<string, MergeChoice> | MergeChoice) => {
    if (!data || busy) return;
    setBusy(true);
    try {
      if (typeof choices === 'string') await saveAllMergeChoices(id, data.review.revision, choices);
      else await saveMergeChoices(id, data.review.revision, choices);
      changed();
    }
    catch (reason) { toastError(reason, 'Decisions could not be saved.'); }
    finally { setBusy(false); refresh((value) => value + 1); }
  };
  const start = async (replan: boolean) => {
    if (!data || busy) return;
    setBusy(true);
    try { await runMerge(data.review, replan); changed(); close(); }
    catch (reason) { toastError(reason, 'Transfer could not be started.'); refresh((value) => value + 1); }
    finally { setBusy(false); }
  };
  const review = data?.review;
  const abandon = async () => {
    if (!review?.canAbandon || busy) return;
    setBusy(true);
    try {
      const confirmed = await window.EnderVault?.askConfirmation({
        nested: true, title: 'Abandon directory transfer?', danger: true,
        message: review.operation === 'PENDING'
          ? 'This merge has not started. Uploaded files will stay in Pending decisions, where you can save or discard them. Destination files will not be changed.'
          : 'This transfer has not started. Source and destination files will not be changed. This review cannot be resumed.',
        confirmLabel: 'Abandon transfer',
      });
      if (!confirmed) return;
      await abandonMerge(review);
      changed(); close();
    } catch (reason) { toastError(reason, 'Transfer could not be abandoned.'); refresh((value) => value + 1); }
    finally { setBusy(false); }
  };
  const needsReview = review?.run?.phase === 'NEEDS_REVIEW';
  const abandoning = review?.run?.phase === 'ABANDONING';
  const stopCopy = async () => {
    if (!review?.canAbandonRemainingCopy || busy) return;
    setBusy(true);
    try {
      const confirmed = abandoning || await window.EnderVault?.askConfirmation({
        nested: true, title: 'Abandon remaining copy?', danger: true,
        message: 'Original files and already copied files will be kept. Remaining files will not be copied, and this transfer cannot be resumed.',
        confirmLabel: 'Abandon remaining copy',
      });
      if (!confirmed) return;
      await abandonRemainingCopy(review);
      changed(); close();
    } catch (reason) { toastError(reason, 'Remaining copy could not be abandoned.'); refresh((value) => value + 1); }
    finally { setBusy(false); }
  };
  const action = review?.operation === 'COPY' ? 'copy' : review?.operation === 'MOVE' ? 'move' : 'merge';
  const bulkChoices = data?.entries.total ? mergeChoices(data.review.operation, 'FILE_CONFLICT') : [];
  const reviewHint = review?.fullyReviewed ? undefined : 'Choose a decision for every conflict before applying the transfer.';
  if (finished || missing) return null;
  return <AppDialog open busy={busy} onDismiss={close} labelledBy="directoryMergeTitle"
    className="text-input-dialog directory-merge-dialog">
    <article className="text-input-card">
      <header className="text-input-header">
        <div><h2 id="directoryMergeTitle">{review?.title || 'Directory transfer'}</h2><p title={review?.destinationPath}>{review?.destinationPath}</p></div>
        <button className="ghost icon-button" type="button" disabled={busy} onClick={close} title="Close" aria-label="Close">
          <i className="fas fa-xmark" aria-hidden="true" />
        </button>
      </header>
      {error && <p role="alert">{error}<button type="button" className="ghost" onClick={() => refresh((value) => value + 1)}>Retry</button></p>}
      {!data && !error && <p role="status"><i className="fas fa-spinner fa-spin" aria-hidden="true" /> Loading review...</p>}
      {data && <>
        <p role="status">{review?.statusLabel} · {data.executionView
          ? `${data.entries.total} remaining items` : `${data.review.conflictCount} conflicts / ${data.review.itemCount} items`}</p>
        {needsReview && <p role="status">Items changed after approval. A new review is required.</p>}
        {review?.editable && bulkChoices.length > 0 && <div className="directory-merge-bulk" role="group" aria-label="Apply to all">
          <span>Apply to all</span>
          <div className="directory-merge-bulk-actions">
            {bulkChoices.map((choice) => <button key={choice} type="button" className="ghost icon-text-button"
              title={choice === 'OVERWRITE' ? 'Apply to all file conflicts. File/folder conflicts keep their current decisions.' : 'Apply to all conflicts across every page.'}
              disabled={busy} onClick={() => void save(choice)}>
              <i className={`fas ${choice === 'OVERWRITE' ? 'fa-file-arrow-down' : choice === 'KEEP_BOTH'
                ? 'fa-copy' : choice === 'SKIP' ? 'fa-forward-step' : 'fa-trash-can'}`} aria-hidden="true" />
              <span>{labels[choice]}</span>
            </button>)}
          </div>
        </div>}
        <div className="table-wrap compact-table directory-merge-entries">
          <table><thead><tr><th>Source</th><th>Destination</th><th>{data.executionView ? 'Remaining work' : 'Decision'}</th></tr></thead>
            <tbody>{data.entries.items.map((entry) => <tr key={entry.id}>
              <td><span className="table-primary-text" title={entry.relativePath}>{entry.relativePath || '/'}</span><small>{entry.sourceKind}</small></td>
              <td><span className="table-primary-text" title={entry.plannedTargetPath}>{entry.plannedTargetPath}</span><small>{entry.targetKind}</small></td>
              <td>{data.executionView ? <span title={entry.stage === 'PUBLICATION_PENDING'
                ? 'Publication is not confirmed in the execution record. Resume reconciles any existing journal before copying.'
                : entry.stage === 'FINALIZATION_PENDING' ? 'Result verification, source cleanup or completion records remain.'
                  : 'The approved operation could not finish. Review the changed items.'}>
                {entry.stage ? stages[entry.stage] : 'Pending'}</span> : <select aria-label={`Decision for ${entry.relativePath || '/'}`} disabled={busy || !review?.editable}
                value={entry.choice || ''} onChange={(event) => { if (event.currentTarget.value) void save({ [entry.id]: event.currentTarget.value as MergeChoice }); }}>
                <option value="">Choose...</option>
                {mergeChoices(data.review.operation, entry.conflict).map((choice) => <option key={choice} value={choice}>{labels[choice]}</option>)}
              </select>}</td>
            </tr>)}{!data.entries.total && <tr><td colSpan={3}>{data.executionView
              ? review?.run?.phase === 'OWNER_COMPLETING' ? 'File processing is complete. Pending record completion remains.'
                : 'No unfinished items. Transfer completion may still need to be recorded.'
              : 'No conflicts. Ready to apply.'}</td></tr>}</tbody>
          </table>
        </div>
        <BrowserPagination page={{ number: page + 1, totalPages: Math.ceil(data.entries.total / data.entries.size) }}
          onPageChange={(value) => setPage(value - 1)} disabled={busy} ariaLabel={data.executionView ? 'Remaining work pages' : 'Merge conflict pages'} />
        <footer className="directory-merge-actions">
          {review?.canAbandon && <button type="button" className="danger" disabled={busy}
            onClick={() => void abandon()}>Abandon transfer</button>}
          {review?.canAbandonRemainingCopy && <button type="button" className="danger" disabled={busy}
            onClick={() => void stopCopy()}>{abandoning ? 'Finish abandonment' : 'Abandon remaining copy'}</button>}
          <button type="button" className="ghost" onClick={close} disabled={busy}>Close</button>
          {abandoning ? null : needsReview ? <button type="button" disabled={busy} onClick={() => void start(true)}>Review changed items</button>
            : <span title={reviewHint}><button type="button" title={reviewHint} disabled={busy || !review?.fullyReviewed || review?.run?.phase === 'COMPLETE'}
              onClick={() => void start(false)}>{`${review?.editable ? 'Apply' : 'Resume'} ${action}`}</button></span>}
        </footer>
      </>}
    </article>
  </AppDialog>;
}

import { useId, useState } from 'react';
import { AppDialog } from '../dialogs/AppDialog';
import { icon } from './BrowserEntries';

interface ViewOptions<S extends string> {
  sort: S;
  direction: 'asc' | 'desc';
  hidden: 'show' | 'hide';
  pageSize: number;
}

export function ViewOptionsControl<S extends string>({ value, sorts, pageSizes, apply, reset }: {
  value: ViewOptions<S>;
  sorts: { value: S; label: string }[];
  pageSizes: number[];
  apply: (value: ViewOptions<S>) => void | Promise<void>;
  reset: () => Promise<void>;
}) {
  const titleId = useId();
  const [draft, setDraft] = useState<ViewOptions<S> | null>(null);
  const [resetPending, setResetPending] = useState(false);
  const [busy, setBusy] = useState(false);
  const close = () => setDraft(null);
  return <>
    <button className="icon-button" type="button" title="View options" aria-label="View options"
      onClick={() => { setDraft({ ...value }); setResetPending(false); }}>
      {icon('fas fa-ellipsis-vertical')}
    </button>
    <AppDialog open={draft !== null} onDismiss={close} labelledBy={titleId}
      className="text-input-dialog" dismissOnBackdrop busy={busy}>
      <form className="text-input-card" onSubmit={async (event) => {
        event.preventDefault();
        if (!draft || busy) return;
        setBusy(true);
        try { if (resetPending) await reset(); else await apply(draft); close(); }
        catch (reason) { window.EnderVault?.showToast('error', reason instanceof Error ? reason.message : 'View options could not be saved.'); }
        finally { setBusy(false); }
      }}>
        <header className="text-input-header"><h2 id={titleId}>View options</h2>
          <button className="ghost icon-button" type="button" onClick={close} disabled={busy} title="Close" aria-label="Close">{icon('fas fa-xmark')}</button>
        </header>
        {draft && <fieldset className="dialog-fields" disabled={busy || resetPending}>
          <label className="text-input-field">Sort<select value={draft.sort} onChange={(event) => setDraft({ ...draft, sort: event.target.value as S })}>
            {sorts.map((sort) => <option key={sort.value} value={sort.value}>{sort.label}</option>)}
          </select></label>
          <label className="text-input-field">Direction<select value={draft.direction} onChange={(event) => setDraft({ ...draft, direction: event.target.value as 'asc' | 'desc' })}>
            <option value="asc">Ascending</option><option value="desc">Descending</option>
          </select></label>
          <label className="text-input-field">Files/page<select value={draft.pageSize} onChange={(event) => setDraft({ ...draft, pageSize: Number(event.target.value) })}>
            {pageSizes.map((size) => <option key={size} value={size}>{size}</option>)}
          </select></label>
          <label className="text-input-field">Visibility<select value={draft.hidden} onChange={(event) => setDraft({ ...draft, hidden: event.target.value as 'show' | 'hide' })}>
            <option value="hide">Visible only</option><option value="show">Show hidden</option>
          </select></label>
        </fieldset>}
        {resetPending && <p role="status">Default view options will be restored when you apply.</p>}
        <button className="ghost icon-text-button" type="button" disabled={busy} onClick={() => setResetPending(!resetPending)}>
          {icon('fas fa-arrow-rotate-left')}<span>{resetPending ? 'Keep current options' : 'Reset view options'}</span>
        </button>
        <div className="text-input-actions">
          <button className="ghost" type="button" disabled={busy} onClick={close}>Cancel</button>
          <button className="primary" type="submit" disabled={busy}>Apply</button>
        </div>
      </form>
    </AppDialog>
  </>;
}

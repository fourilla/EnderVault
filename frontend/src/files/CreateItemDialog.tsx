import { useId, useState } from 'react';
import { AppDialog } from '../shared/dialogs/AppDialog';
import { icon } from '../shared/browser/BrowserEntries';

export function CreateItemDialog({ close, create }: {
  close: () => void;
  create: (directory: boolean, name: string) => Promise<void>;
}) {
  const titleId = useId();
  const [directory, setDirectory] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return <AppDialog open onDismiss={close} busy={busy} labelledBy={titleId} className="text-input-dialog">
    <form className="text-input-card" noValidate onSubmit={async (event) => {
      event.preventDefault();
      if (busy) return;
      if (!name.trim()) { setError('A name is required.'); return; }
      setBusy(true);
      try { await create(directory, name.trim()); close(); }
      catch (reason) { setError(reason instanceof Error ? reason.message : 'Creation failed.'); }
      finally { setBusy(false); }
    }}>
      <header className="text-input-header"><h2 id={titleId}>Create new item</h2>
        <button className="ghost icon-button" type="button" disabled={busy} onClick={close} title="Cancel" aria-label="Cancel">{icon('fas fa-xmark')}</button>
      </header>
      <div className="dialog-kind-options" role="group" aria-label="Item type">
        <button className="ghost icon-text-button" type="button" aria-pressed={!directory} disabled={busy} onClick={() => setDirectory(false)}>{icon('fas fa-file')}<span>File</span></button>
        <button className="ghost icon-text-button" type="button" aria-pressed={directory} disabled={busy} onClick={() => setDirectory(true)}>{icon('fas fa-folder')}<span>Directory</span></button>
      </div>
      <label className="text-input-field">{directory ? 'Directory name' : 'File name'}
        <input value={name} disabled={busy} autoFocus autoComplete="off" placeholder={directory ? 'New directory' : 'note.txt'}
          onChange={(event) => { setName(event.target.value); setError(''); }}
          onKeyDown={(event) => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault(); }} />
      </label>
      {error && <p className="text-input-error" role="alert">{error}</p>}
      <div className="text-input-actions"><button className="ghost" type="button" disabled={busy} onClick={close}>Cancel</button>
        <button className="primary" type="submit" disabled={busy}>Create</button></div>
    </form>
  </AppDialog>;
}

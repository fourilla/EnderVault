import { FormEvent, useEffect, useRef, useState } from 'react';

type DialogKind = 'link' | 'bulk' | null;

export function BookmarkDialogs({
  kind,
  close,
  createLink,
  bulkAdd,
}: {
  kind: DialogKind;
  close: () => void;
  createLink: (title: string, url: string) => Promise<void>;
  bulkAdd: (bulkText: string) => Promise<void>;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [title, setTitle] = useState('');
  const [url, setUrl] = useState('');
  const [bulkText, setBulkText] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (kind && !dialog.open) dialog.showModal();
    if (!kind && dialog.open) dialog.close();
  }, [kind]);

  useEffect(() => {
    if (kind === 'link') {
      setTitle('');
      setUrl('');
    } else if (kind === 'bulk') {
      setBulkText('');
    }
  }, [kind]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!kind || busy) return;
    setBusy(true);
    try {
      if (kind === 'link') await createLink(title.trim(), url.trim());
      else await bulkAdd(bulkText);
      close();
    } catch (reason) {
      const fallback = kind === 'link' ? 'Bookmark link creation failed.' : 'Bookmark bulk add failed.';
      window.EnderVault?.showToast('error', reason instanceof Error ? reason.message : fallback);
    } finally {
      setBusy(false);
    }
  };

  return (
    <dialog
      ref={dialogRef}
      className={'text-input-dialog bookmark-form-dialog' + (kind === 'bulk' ? ' bookmark-bulk-dialog' : '')}
      onCancel={(event) => { event.preventDefault(); close(); }}
      onClose={close}
    >
      <form className="text-input-card bookmark-dialog-card" onSubmit={submit}>
        <header className="text-input-header">
          <div>
            <h2>{kind === 'bulk' ? 'Bulk add links' : 'Add link'}</h2>
            <p>{kind === 'bulk'
              ? 'Enter URLs or title and URL pairs, one value per line.'
              : 'Save a URL in the current bookmark directory.'}</p>
          </div>
          <button className="ghost icon-button action-icon" type="button" onClick={close}
            title="Cancel" aria-label="Cancel">
            <i className="fas fa-xmark" aria-hidden="true" />
          </button>
        </header>
        {kind === 'bulk' ? (
          <label className="text-input-field">
            Links
            <textarea value={bulkText} onChange={(event) => setBulkText(event.target.value)} rows={14}
              placeholder={'https://example.com/auto-name\nTitle one\nhttps://example.com/one'} required autoFocus />
          </label>
        ) : (
          <div className="bookmark-dialog-fields">
            <label className="text-input-field">
              Title
              <input value={title} onChange={(event) => setTitle(event.target.value)} type="text"
                maxLength={200} placeholder="Leave blank to auto-name" autoFocus />
            </label>
            <label className="text-input-field">
              URL
              <input value={url} onChange={(event) => setUrl(event.target.value)} type="text"
                maxLength={4096} placeholder="https://example.com or /files" required />
            </label>
          </div>
        )}
        <div className="text-input-actions">
          <button className="ghost icon-text-button" type="button" onClick={close}>Cancel</button>
          <button className="primary icon-text-button" type="submit" disabled={busy}>
            {kind === 'bulk' ? 'Add links' : 'Add link'}
          </button>
        </div>
      </form>
    </dialog>
  );
}

export type { DialogKind };

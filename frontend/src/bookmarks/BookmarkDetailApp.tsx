import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AppNavigationLink } from '../app/AppNavigationLink';
import { toggleBookmarkFavorite } from '../shared/api/favorite-api';
import { notify, postForm, toastError } from '../shared/api/form-api';
import { loadBookmarkDetail } from './bookmark-api';
import type { BookmarkDetailPayload } from './types';

export function BookmarkDetailApp() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const id = searchParams.get('id')?.trim() ?? '';
  const [payload, setPayload] = useState<BookmarkDetailPayload | null>(null);
  const [title, setTitle] = useState('');
  const [url, setUrl] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState<'save' | 'metadata' | 'favorite' | 'delete' | ''>('');
  const [error, setError] = useState('');

  const load = useCallback(async (signal?: AbortSignal) => {
    if (!id) {
      setPayload(null);
      setError('Bookmark identifier is missing.');
      return;
    }
    const next = await loadBookmarkDetail(id, signal);
    setPayload(next);
    setTitle(next.title);
    setUrl(next.url === '-' ? '' : next.url);
    setNote(next.note);
    setError('');
  }, [id]);

  useEffect(() => {
    const controller = new AbortController();
    setPayload(null);
    setError('');
    void load(controller.signal).catch((reason: unknown) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Bookmark details could not be loaded.');
    });
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    if (!payload) return;
    document.title = `${payload.title} - EnderVault`;
    const context = { targetType: 'BOOKMARK', targetKey: payload.id, surface: 'DETAIL', label: payload.title };
    void window.EnderVaultStickyNotes?.setContext(context);
    document.dispatchEvent(new CustomEvent('endervault:sticky-context-changed', { detail: context }));
  }, [payload]);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!payload || busy) return;
    setBusy('save');
    try {
      const body = payload.directory
        ? await postForm('/api/v1/bookmarks/directories/update', {
          id: payload.id, parentId: payload.parentId ?? '', title: title.trim(), returnToDetail: 'true',
        })
        : await postForm('/api/v1/bookmarks/links/update', {
          id: payload.id, parentId: payload.parentId ?? '', title: title.trim(), url: url.trim(), note, returnToDetail: 'true',
        });
      notify(body);
      await load();
    } catch (reason) {
      toastError(reason, 'Bookmark update failed.');
    } finally {
      setBusy('');
    }
  };

  const toggleFavorite = async () => {
    if (!payload || busy) return;
    setBusy('favorite');
    try {
      const result = await toggleBookmarkFavorite(payload.id);
      setPayload((current) => current ? { ...current, favorite: Boolean(result.active) } : current);
    } catch (reason) {
      toastError(reason, 'Favorite could not be updated.');
    } finally {
      setBusy('');
    }
  };

  const refreshMetadata = async () => {
    if (!payload || busy) return;
    setBusy('metadata');
    try {
      const body = await postForm('/api/v1/bookmarks/metadata', {
        id: payload.id, parentId: payload.parentId ?? '', returnToDetail: 'true',
      });
      notify(body);
      await load();
    } catch (reason) {
      toastError(reason, 'Bookmark metadata refresh failed.');
    } finally {
      setBusy('');
    }
  };

  const remove = async () => {
    if (!payload || busy) return;
    const confirmed = await window.EnderVault?.askConfirmation({
      title: 'Delete bookmark',
      message: `Delete "${payload.title}"?`,
      confirmLabel: 'Delete',
      danger: true,
    });
    if (!confirmed) return;
    setBusy('delete');
    try {
      const body = await postForm('/api/v1/bookmarks/delete', {
        id: payload.id, parentId: payload.parentId ?? '',
      });
      notify(body);
      navigate(body.redirectUrl || payload.parentUrl, { replace: true });
    } catch (reason) {
      toastError(reason, 'Bookmark deletion failed.');
      setBusy('');
    }
  };

  if (!payload && !error) return (
    <main className="browser-load-state" aria-live="polite">
      <i className="fas fa-spinner fa-spin" aria-hidden="true" /><span>Loading bookmark details...</span>
    </main>
  );
  if (!payload) return (
    <main className="browser-load-state browser-load-error" role="alert">
      <strong>Bookmark details unavailable</strong><span>{error}</span>
      <AppNavigationLink className="button-link ghost" href="/files/bookmarks">Open Bookmarks</AppNavigationLink>
    </main>
  );

  return (
    <>
      <section className="pathbar" aria-label="Bookmark detail location">
        <AppNavigationLink className="button-link ghost icon-button" href={payload.parentUrl}
          title="Back to bookmarks" aria-label="Back to bookmarks">
          <i className="fas fa-arrow-left" aria-hidden="true" />
        </AppNavigationLink>
        <span className="muted">{payload.parentLabel}</span>
      </section>

      <section className="detail-layout bookmark-detail-layout">
        <article className="detail-panel detail-main">
          <p className="eyebrow">{payload.typeLabel}</p>
          <div className="bookmark-detail-title-row">
            {payload.faviconUrl
              ? <img className="bookmark-detail-favicon" src={payload.faviconUrl} alt="" />
              : <i className={`${payload.iconClass} bookmark-detail-icon`} aria-hidden="true" />}
            <h1>{payload.title}</h1>
          </div>

          <div className="detail-actions">
            <button className={`ghost icon-button action-icon favorite-toggle${payload.favorite ? ' is-favorite' : ''}`}
              type="button" disabled={Boolean(busy)} onClick={() => void toggleFavorite()}
              title={payload.favorite ? 'Remove from favorites' : 'Add to favorites'}
              aria-label={payload.favorite ? 'Remove from favorites' : 'Add to favorites'}>
              <i className="fas fa-star" aria-hidden="true" />
            </button>
            {payload.directoryUrl && (
              <AppNavigationLink className="button-link icon-button" href={payload.directoryUrl}
                title="Open directory" aria-label="Open directory">
                <i className="fas fa-folder-open" aria-hidden="true" />
              </AppNavigationLink>
            )}
            {payload.openUrl && (
              <a className="button-link icon-text-button" href={payload.openUrl} target="_blank" rel="noopener noreferrer">
                <i className="fas fa-arrow-up-right-from-square" aria-hidden="true" /><span>Open link</span>
              </a>
            )}
          </div>

          <dl className="details bookmark-detail-info">
            <div><dt>ID</dt><dd>{payload.id}</dd></div>
            <div><dt>Type</dt><dd>{payload.typeLabel}</dd></div>
            <div><dt>Title</dt><dd>{payload.title}</dd></div>
            {payload.link && <div><dt>URL</dt><dd><a href={payload.openUrl ?? '#'} target="_blank" rel="noopener noreferrer">{payload.url}</a></dd></div>}
            {payload.link && <div><dt>Note</dt><dd>{payload.note || '-'}</dd></div>}
            {payload.link && <div><dt>Title source</dt><dd>{payload.titleSource}</dd></div>}
            {payload.link && <div><dt>Metadata status</dt><dd>{payload.metadataFetchStatus}</dd></div>}
            {payload.link && <div><dt>Metadata fetched</dt><dd>{payload.metadataFetchedLabel}</dd></div>}
            {payload.link && <div><dt>Favicon</dt><dd>{payload.faviconContentType}</dd></div>}
            <div><dt>Created</dt><dd>{payload.createdLabel}</dd></div>
            <div><dt>Updated</dt><dd>{payload.updatedLabel}</dd></div>
            {payload.link && <div><dt>Last opened</dt><dd>{payload.lastOpenedLabel}</dd></div>}
          </dl>
        </article>

        <aside className="detail-panel detail-manage">
          <h2>Manage</h2>
          <div className="manage-stack">
            <form className="stack manage-form" onSubmit={(event) => void save(event)}>
              <label>{payload.directory ? 'Directory name' : 'Title'}
                <input type="text" maxLength={200} value={title} required={payload.directory}
                  placeholder={payload.link ? 'Leave blank to auto-name' : undefined}
                  disabled={Boolean(busy)} onChange={(event) => setTitle(event.currentTarget.value)} />
              </label>
              {payload.link && (
                <>
                  <label>URL<input type="text" maxLength={4096} value={url} required disabled={Boolean(busy)}
                    onChange={(event) => setUrl(event.currentTarget.value)} /></label>
                  <label>Note<textarea rows={5} maxLength={1000} value={note} disabled={Boolean(busy)}
                    onChange={(event) => setNote(event.currentTarget.value)} /></label>
                </>
              )}
              <button className="icon-text-button" type="submit" disabled={Boolean(busy)}>
                <i className="fas fa-floppy-disk" aria-hidden="true" /><span>Save</span>
              </button>
            </form>

            {payload.metadataRefreshable && (
              <button className="ghost icon-text-button" type="button" disabled={Boolean(busy)} onClick={() => void refreshMetadata()}>
                <i className="fas fa-wand-magic-sparkles" aria-hidden="true" /><span>Refresh metadata</span>
              </button>
            )}

            <div className="manage-delete">
              <button className="danger icon-text-button" type="button" disabled={Boolean(busy)} onClick={() => void remove()}>
                <i className="fas fa-trash-can" aria-hidden="true" /><span>Delete</span>
              </button>
            </div>
          </div>
        </aside>
      </section>
    </>
  );
}

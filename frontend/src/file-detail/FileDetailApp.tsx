import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { TransferBufferPanel } from '../files/TransferBufferPanel';
import type { TransferBufferPayload } from '../files/types';
import { togglePathFavorite } from '../shared/api/favorite-api';
import { notify, postForm, toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import { PageBreadcrumbs } from '../shared/layout/PageHeader';
import { FileTools } from './FileTools';
import type { FileDetailPayload, SharePayload } from './types';
import './file-detail-app.css';

function ShareSection({ payload, refresh }: { payload: FileDetailPayload; refresh: () => void }) {
  const [token, setToken] = useState('');
  const [days, setDays] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const mutate = async (action: 'revoke' | 'delete', share: SharePayload) => {
    try {
      const body = await postForm(`/api/v1/shares/${action}`, { token: share.token });
      notify(body);
      refresh();
    } catch (reason) {
      toastError(reason, `Share link could not be ${action}d.`);
    }
  };
  return <section className="detail-panel">
    <h2>Share</h2>
    <form className="tool-form share-create-form" onSubmit={async (event) => {
      event.preventDefault(); setSubmitting(true);
      try {
        const body = await postForm('/api/v1/shares', {
          path: payload.detail.path, customToken: token, expiresInDays: days,
        });
        notify(body); setToken(''); setDays(''); refresh();
      } catch (reason) {
        toastError(reason, 'Share link could not be created.');
      } finally { setSubmitting(false); }
    }}>
      <label><span className="visually-hidden">Custom token</span>
        <input type="text" value={token} onChange={(event) => setToken(event.target.value)} placeholder="Custom token" />
      </label>
      <label><span className="visually-hidden">Expiration days</span>
        <input type="text" inputMode="numeric" value={days} onChange={(event) => setDays(event.target.value)} placeholder="Days" />
      </label>
      <button type="submit" disabled={submitting}>Create link</button>
    </form>
    <div className="table-wrap compact-table" aria-label="Shared links for this item">
      <table><thead><tr><th>Link</th><th>Created</th><th>Expires</th><th>Status</th><th>Actions</th></tr></thead>
        <tbody>{payload.shares.length === 0 ? <tr className="empty-row"><td colSpan={5} className="empty">No shared links yet.</td></tr>
          : payload.shares.map((share) => <tr key={share.token}>
            <td><input readOnly value={share.url} /></td><td>{share.createdLabel}</td><td>{share.expiresLabel}</td>
            <td><span className={`status-badge ${share.statusClass}`}>{share.statusLabel}</span></td>
            <td><div className="table-actions">
              <button className="ghost icon-button action-icon" type="button" title="Copy link" aria-label="Copy link"
                onClick={async () => {
                  if (await window.EnderVault?.copyText(share.url)) window.EnderVault?.showToast('success', 'Share link copied.');
                }}>{icon('fas fa-link')}</button>
              {share.directDownloadUrl && <button className="ghost icon-button action-icon" type="button"
                title="Copy direct download link" aria-label="Copy direct download link" onClick={async () => {
                  if (await window.EnderVault?.copyText(share.directDownloadUrl || '')) {
                    window.EnderVault?.showToast('success', 'Direct download link copied.');
                  }
                }}>{icon('fas fa-file-arrow-down')}</button>}
              {share.active && <button className="danger icon-button action-icon" type="button" title="Revoke"
                aria-label="Revoke" onClick={() => void mutate('revoke', share)}>{icon('fas fa-link-slash')}</button>}
              <button className="ghost icon-button action-icon" type="button" title="Delete" aria-label="Delete"
                onClick={() => void mutate('delete', share)}>{icon('fas fa-trash-can')}</button>
            </div></td>
          </tr>)}</tbody></table>
    </div>
  </section>;
}

function DetailMetadata({ payload, onToggleFavorite }: {
  payload: FileDetailPayload;
  onToggleFavorite: () => void;
}) {
  const { detail, urls } = payload;
  const rows = [
    ['Name', detail.name], ['Path', detail.path], ['Type', detail.mediaType],
    detail.directory ? ['Items', String(detail.childCount)] : ['Extension', detail.extension || '-'],
    ...(detail.directory ? [] : [['Size', detail.sizeLabel]]),
    ['Created', detail.createdLabel], ['Modified', detail.modifiedLabel], ['Accessed', detail.accessedLabel],
  ];
  return <article className="detail-panel detail-main">
    <p className="eyebrow">{detail.directory ? 'Directory' : 'File'}</p><h1>{detail.name}</h1>
    <div className="detail-actions">
      {detail.directory && <Link className="button-link icon-button" to={urls.openDirectory || '/files'}
        title="Open directory" aria-label="Open directory">{icon('fas fa-folder-open')}</Link>}
      {detail.directory && <a className="button-link icon-button" href={urls.downloadZip || '#'}
        title="Download zip" aria-label="Download zip">{icon('fas fa-file-zipper')}</a>}
      {urls.actions.map((action) => <a key={action.id} className="button-link ghost icon-button action-icon"
        href={action.href} target={action.newTab ? '_blank' : undefined}
        rel={action.newTab ? 'noopener noreferrer' : undefined} title={action.label} aria-label={action.label}>
        {icon(action.icon)}
      </a>)}
      <button className={'ghost icon-button action-icon favorite-toggle' + (payload.favorite ? ' is-favorite' : '')}
        type="button" title={payload.favorite ? 'Remove from favorites' : 'Add to favorites'}
        aria-label={payload.favorite ? 'Remove from favorites' : 'Add to favorites'}
        onClick={onToggleFavorite}>{icon('fas fa-star')}</button>
    </div>
    <dl className="details">{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
      <div><dt>Hidden</dt><dd><span className={`status-badge ${detail.hidden ? 'expired' : 'active'}`}>
        {detail.hidden ? 'Hidden' : 'Visible'}</span></dd></div>
    </dl>
  </article>;
}

export function FileDetailApp() {
  const location = useLocation();
  const navigate = useNavigate();
  const query = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const path = query.get('path') || '';
  const [payload, setPayload] = useState<FileDetailPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [rename, setRename] = useState('');
  const detailRequestVersion = useRef(0);

  const refresh = useCallback((signal?: AbortSignal) => {
    if (!path) { setError('A file path is required.'); setLoading(false); return; }
    const requestVersion = ++detailRequestVersion.current;
    const url = new URL('/api/v1/fs/detail', window.location.origin);
    url.searchParams.set('path', path);
    const comicPage = query.get('comicPage');
    if (comicPage) url.searchParams.set('comicPage', comicPage);
    setPayload((current) => current?.detail.path === path ? current : null);
    setLoading(true); setError('');
    void window.EnderVault?.requestJson(url.toString(), { signal }).then((body) => {
      if (signal?.aborted || requestVersion !== detailRequestVersion.current) return;
      setPayload(body); setRename(body.detail.name);
    }).catch((reason) => {
      if (signal?.aborted || requestVersion !== detailRequestVersion.current
          || (reason instanceof DOMException && reason.name === 'AbortError')) return;
      setError(reason instanceof Error ? reason.message : 'File details could not be loaded.');
    }).finally(() => {
      if (!signal?.aborted && requestVersion === detailRequestVersion.current) setLoading(false);
    });
  }, [path, query]);

  useEffect(() => {
    const controller = new AbortController();
    refresh(controller.signal);
    return () => controller.abort();
  }, [refresh]);
  useEffect(() => {
    if (!payload) return;
    document.title = `${payload.detail.name} - EnderVault`;
    document.dispatchEvent(new CustomEvent('endervault:sticky-context-changed', { detail: {
      targetType: 'STORAGE', targetKey: payload.detail.path,
      surface: 'DETAIL', label: payload.detail.name,
    }}));
  }, [payload]);

  if (loading && !payload) return <main className="browser-load-state" aria-live="polite">
    {icon('fas fa-spinner fa-spin')}<span>Loading file details...</span></main>;
  if (error || !payload) return <main className="browser-load-state browser-load-error" role="alert">
    <strong>File details unavailable</strong><span>{error}</span></main>;

  const mutate = async (endpoint: string, values: Record<string, string>, conflict = false) => {
    const body = await postForm(endpoint, values, conflict);
    notify(body);
    if (body.redirectUrl) navigate(body.redirectUrl, { replace: true });
    else refresh();
  };
  const bufferActions = {
    updateTransferBuffer: async (action: 'clear' | 'remove' | 'paste', values: Record<string, string>) => {
      try {
        const body = await postForm(`/api/v1/files/transfer-buffer/${action}`, values, action === 'paste');
        notify(body);
        if (body.transferBuffer) setPayload((current) => current ? { ...current, transferBuffer: body.transferBuffer } : current);
        if (body.task) window.EnderVaultServerTasks?.track(body.task);
      } catch (reason) { toastError(reason, 'Transfer buffer action failed.'); }
    },
  };
  const toggleFavorite = async () => {
    try {
      const body = await togglePathFavorite(payload.detail.path);
      setPayload((current) => current ? { ...current, favorite: Boolean(body.active) } : current);
    } catch (reason) {
      toastError(reason, 'Favorite could not be updated.');
    }
  };
  return <>
    <PageBreadcrumbs
      label="Current file location"
      parent={<Link to={payload.urls.parentDirectory}>{payload.detail.parentPath || 'Files'}</Link>}
      current={payload.detail.name}
    />
    <FileTools payload={payload} />
    <section className="detail-layout">
      <DetailMetadata payload={payload} onToggleFavorite={() => void toggleFavorite()} />
      <aside className="detail-panel detail-manage"><h2>Manage</h2><div className="manage-stack">
        <form className="stack manage-form" onSubmit={async (event) => {
          event.preventDefault();
          try { await mutate('/api/v1/files/detail/rename', {
            path: payload.detail.path, newName: rename, conflictPolicy: 'ask',
          }, true); } catch (reason) { toastError(reason, 'Item could not be renamed.'); }
        }}><label>Rename<input value={rename} onChange={(event) => setRename(event.target.value)} required /></label>
          <button type="submit">{icon('fas fa-pen-to-square')} Rename</button></form>
        <div className="stack manage-form"><button className="ghost" type="button" onClick={async () => {
          try {
            const body = await postForm('/api/v1/files/transfer-buffer/detail', { path: payload.detail.path });
            notify(body);
            if (body.transferBuffer) setPayload((current) => current ? { ...current, transferBuffer: body.transferBuffer } : current);
          } catch (reason) { toastError(reason, 'Item could not be added to the transfer buffer.'); }
        }}>{icon('fas fa-layer-group')} Add to transfer buffer</button></div>
        <div className="stack manage-form"><button className="ghost" type="button" onClick={async () => {
          try { await mutate('/api/v1/files/detail/hidden', {
            path: payload.detail.path, hidden: String(!payload.detail.hidden), conflictPolicy: 'ask',
          }, true); } catch (reason) { toastError(reason, 'Visibility could not be changed.'); }
        }}>{icon(payload.detail.hidden ? 'fas fa-eye' : 'fas fa-eye-slash')}
          {payload.detail.hidden ? 'Make visible' : 'Hide item'}</button></div>
        <div className="manage-delete"><div className="stack manage-form"><button className="danger" type="button" onClick={async () => {
          const confirmed = await window.EnderVault?.askConfirmation({ title: 'Move to trash',
            message: `Move ${payload.detail.name} to trash?`, confirmLabel: 'Move to trash', danger: true });
          if (!confirmed) return;
          try { await mutate('/api/v1/files/detail/trash', { path: payload.detail.path }); }
          catch (reason) { toastError(reason, 'Item could not be moved to trash.'); }
        }}>{icon('fas fa-trash-can')} Move to trash</button></div></div>
      </div></aside>
    </section>
    <TransferBufferPanel transferBuffer={payload.transferBuffer}
      mode={payload.detail.directory ? 'browse' : undefined}
      path={payload.detail.directory ? payload.detail.path : payload.detail.parentPath}
      actions={bufferActions} />
    <ShareSection payload={payload} refresh={refresh} />
  </>;
}

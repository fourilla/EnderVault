import { useEffect, useRef, useState } from 'react';
import { notify, postForm, toastError } from '../shared/api/form-api';
import { icon } from '../shared/browser/BrowserEntries';
import type { ArchiveEntriesPayload, ArchiveEntryPayload, FileDetailPayload } from './types';

function ArchiveNode({ entry, entriesUrl }: { entry: ArchiveEntryPayload; entriesUrl: string }) {
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [children, setChildren] = useState<ArchiveEntryPayload[] | null>(null);
  const toggle = async () => {
    if (!entry.directory || loading) return;
    if (expanded) {
      setExpanded(false);
      return;
    }
    if (children == null) {
      setLoading(true);
      try {
        const url = new URL(entriesUrl, window.location.origin);
        url.searchParams.set('parent', entry.path);
        const body = await window.EnderVault?.requestJson(url.toString());
        setChildren(body?.entries || []);
      } catch (reason) {
        toastError(reason, 'Archive entries could not be loaded.');
        return;
      } finally {
        setLoading(false);
      }
    }
    setExpanded(true);
  };
  return <li className={'archive-tree-node' + (expanded ? ' expanded' : '')} role="treeitem"
    aria-expanded={entry.directory ? expanded : undefined}>
    <div className="archive-tree-row" title={entry.path} onDoubleClick={() => void toggle()}>
      {entry.directory ? <button className="archive-tree-toggle" type="button" onClick={() => void toggle()}
        title={`${expanded ? 'Collapse' : 'Expand'} ${entry.name}`} aria-label={`${expanded ? 'Collapse' : 'Expand'} ${entry.name}`}>
        {icon(loading ? 'fas fa-spinner fa-spin' : 'fas fa-chevron-right')}
      </button> : <span className="archive-tree-spacer" />}
      {icon(`archive-tree-icon fas ${entry.directory ? 'fa-folder' : 'fa-file'}`)}
      <span className="archive-tree-name">{entry.name}</span>
      <span className="archive-tree-size">{entry.sizeLabel}</span>
    </div>
    {entry.directory && expanded && children && <ul className="archive-tree-list" role="group">
      {children.map((child) => <ArchiveNode key={child.path} entry={child} entriesUrl={entriesUrl} />)}
    </ul>}
  </li>;
}

export function ArchiveTool({ payload }: { payload: FileDetailPayload }) {
  const archive = payload.archive;
  const [manifest, setManifest] = useState<ArchiveEntriesPayload | null>(null);
  const [error, setError] = useState('');
  const [createContainer, setCreateContainer] = useState(true);
  const layoutTouched = useRef(false);
  const [outputName, setOutputName] = useState(archive?.suggestedName || '');
  const [destination, setDestination] = useState(archive?.destinationPath || '');
  const [conflictPolicy, setConflictPolicy] = useState('cancel');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!archive) return;
    let active = true;
    void window.EnderVault?.requestJson(archive.entriesUrl).then((body) => {
      if (!active) return;
      setManifest(body);
      if (!layoutTouched.current) setCreateContainer(!body.browsable || body.entries.length !== 1);
    }).catch((reason) => active && setError(reason instanceof Error ? reason.message : 'Archive entries could not be loaded.'));
    return () => { active = false; };
  }, [archive?.entriesUrl]);

  if (!archive) return null;
  const extract = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!manifest?.extractable) {
      window.EnderVault?.showToast('warning', manifest?.message || 'This archive cannot be extracted safely.');
      return;
    }
    setSubmitting(true);
    try {
      const body = await postForm('/api/v1/files/archives/extract', {
        path: payload.detail.path,
        destinationPath: destination,
        createContainingDirectory: createContainer ? 'true' : undefined,
        outputName: createContainer ? outputName : undefined,
        conflictPolicy,
      });
      notify(body);
      if (body.task) window.EnderVaultServerTasks?.track(body.task, { announceStart: true });
    } catch (reason) {
      toastError(reason, 'Archive extraction could not be queued.');
    } finally {
      setSubmitting(false);
    }
  };
  const hint = !manifest ? 'Archive information is loading.'
    : !manifest.browsable ? 'Archive layout is unavailable because its entry metadata cannot be read.'
      : manifest.entries.length === 1 ? 'One top-level item detected. Direct extraction is recommended.'
        : `${manifest.entries.length} top-level items detected. A containing directory is recommended.`;
  return <div className="archive-tool" data-archive-tool>
    <div className="archive-summary" aria-live="polite">
      <span><strong>{manifest?.format || 'Archive'}</strong></span>
      <span><strong>{manifest?.browsable ? manifest.fileCount : '-'}</strong> files</span>
      <span><strong>{manifest?.browsable ? manifest.directoryCount : '-'}</strong> directories</span>
      <span><strong>{manifest?.browsable ? manifest.totalSizeLabel : '-'}</strong> unpacked</span>
    </div>
    {(error || (!manifest?.extractable && manifest?.message)) && <p className="archive-status muted is-warning">
      {error || manifest?.message}
    </p>}
    <div className="archive-tree">
      {!manifest && !error && <p className="archive-tree-message muted">Loading archive entries...</p>}
      {manifest && !manifest.browsable && <p className="archive-tree-message muted">
        Archive contents cannot be browsed because its entry metadata is unavailable.
      </p>}
      {manifest?.browsable && <ul className="archive-tree-list" role="tree" aria-label="Archive contents">
        {manifest.entries.map((entry) => <ArchiveNode key={entry.path} entry={entry} entriesUrl={archive.entriesUrl} />)}
      </ul>}
    </div>
    <form className="archive-extract-form" onSubmit={extract}>
      <div className="archive-layout-options">
        <label className="archive-container-option">
          <input type="checkbox" checked={createContainer} onChange={(event) => {
            layoutTouched.current = true; setCreateContainer(event.target.checked);
          }} />
          <span><strong>Create containing directory</strong><small className="muted">{hint}</small></span>
        </label>
        <label className={'archive-container-name-field' + (!createContainer ? ' is-disabled' : '')}
          aria-disabled={!createContainer}>
          <span>Output directory name</span>
          <input type="text" value={outputName} onChange={(event) => setOutputName(event.target.value)}
            required={createContainer} disabled={!createContainer} maxLength={255} />
        </label>
      </div>
      <label className="archive-destination-field"><span>Destination</span>
        <span className="input-action-field">
          <input id="archiveDestinationPath" type="text" value={destination}
            onChange={(event) => setDestination(event.target.value)} placeholder="Vault root" />
          <button className="input-action-button" type="button" data-directory-picker-open
            data-directory-picker-target="archiveDestinationPath" title="Browse directories" aria-label="Browse directories">
            {icon('fas fa-folder-open')}
          </button>
        </span>
      </label>
      <label className="archive-conflict-field"><span>If an output item exists</span>
        <select value={conflictPolicy} onChange={(event) => setConflictPolicy(event.target.value)}>
          <option value="cancel">Cancel</option><option value="rename">Rename and continue</option>
        </select>
      </label>
      <div className="archive-extract-actions"><button type="submit" disabled={submitting || !manifest}>
        {icon('fas fa-box-open')} Extract Archive
      </button></div>
    </form>
  </div>;
}

import { Fragment, useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { StableTable } from '../shared/browser/StableTable';
import { SelectionHeader } from '../shared/browser/SelectionHeader';
import { useItemSelection } from '../shared/browser/useItemSelection';
import { useSelectionShortcuts } from '../shared/browser/useSelectionShortcuts';
import { useBrowserContextMenu } from '../shared/browser/useBrowserContextMenu';
import { OverflowMarquee } from '../shared/layout/OverflowMarquee';
import { sharedDirectoryMenuActions, sharedZipUrl } from './directory-actions';
import { nativeLinkClick, nativeTextSelection } from './directory-interactions';
import type { SharedEntry, SharedListing } from './types';

const itemKey = (entry: SharedEntry) => entry.name;

export function SharedDirectoryPage({ listing, initialSelectedNames, onSelectionChange, disabled = false }: {
  listing: SharedListing;
  initialSelectedNames: string[];
  onSelectionChange: (names: string[]) => void;
  disabled?: boolean;
}) {
  const navigate = useNavigate();
  const root = useRef<HTMLElement>(null);
  const getRoot = useCallback(() => root.current, []);
  const openItem = useCallback((entry: SharedEntry) => { if (!disabled) void navigate(entry.openUrl); }, [navigate, disabled]);
  const selection = useItemSelection({ items: listing.entries, enabled: !disabled,
    locationKey: listing.rootUrl + ':' + listing.path, itemKey, openItem,
    toolbarSelectionSelector: '.shared-download-button' });
  const restoredNames = useRef(new Set(initialSelectedNames.filter(name => listing.entries.some(entry => entry.name === name))));
  const restoring = useRef(true);
  const reportedNames = useRef<string[] | null>(null);
  const changeSelection = useRef(onSelectionChange);
  changeSelection.current = onSelectionChange;
  useEffect(() => {
    // The common hook resets a new visit first; restore only names still present in this response.
    selection.selectedRef.current = new Set(restoredNames.current);
    selection.setSelected(new Set(restoredNames.current));
  }, []);
  useEffect(() => {
    if (restoring.current) {
      if (selection.selected.size !== restoredNames.current.size
        || [...restoredNames.current].some(name => !selection.selected.has(name))) return;
      restoring.current = false;
    }
    const names = selection.selectedItems.map(entry => entry.name);
    if (reportedNames.current?.length === names.length
      && reportedNames.current.every((name, index) => name === names[index])) return;
    reportedNames.current = names;
    changeSelection.current(names);
  }, [selection.selected, selection.selectedItems]);

  useSelectionShortcuts({ enabled: !disabled && listing.entries.length > 0, contextKey: listing.rootUrl + ':' + listing.path,
    selectedCount: selection.selectedItems.length, selectAll: selection.selectAll,
    clearSelection: selection.clearSelection, scope: getRoot });
  const download = (url: string) => { if (!disabled) window.location.assign(url); };
  const menu = useBrowserContextMenu({ menuId: 'sharedDirectoryContextMenu', pageScope: 'public-directory',
    getRoot, entries: () => listing.entries, itemKey, keyAttribute: 'data-entry-name',
    selectedRef: selection.selectedRef, setSelected: selection.setSelected,
    actions: () => disabled ? [] : sharedDirectoryMenuActions({ listing, selectedCount: selection.selectedItems.length,
      navigate: url => { void navigate(url); }, download,
      selectAll: selection.selectAll, clearSelection: selection.clearSelection }),
    contentKey: listing, contextKey: listing.path, errorMessage: 'The shared file action failed.' });

  useLayoutEffect(() => { if (disabled) menu.current?.close(); }, [disabled, menu]);

  const interactionProps = (entry: SharedEntry) => {
    const handlers = selection.itemInteractionProps(entry);
    return { ...handlers,
      onMouseDownCapture: (event: React.MouseEvent<HTMLElement>) => {
        if (!nativeLinkClick(event)) handlers.onMouseDownCapture(event);
      },
      onPointerDown: (event: React.PointerEvent<HTMLElement>) => {
        if (!nativeLinkClick(event)) handlers.onPointerDown(event);
      },
      onClickCapture: (event: React.MouseEvent<HTMLElement>) => {
        if (!nativeLinkClick(event)) handlers.onClickCapture(event);
      },
    };
  };
  return <section className="public-share-directory" ref={root}
    data-native-context-menu={listing.entries.length === 0 ? true : undefined}
    onContextMenuCapture={event => {
      if (!nativeTextSelection(window.getSelection())) return;
      menu.current?.close();
      event.stopPropagation();
    }}>
    <section className="breadcrumb-panel" aria-label="Current location">
      <div className="breadcrumb-main">
        <p className="breadcrumb-label">Location</p>
        <nav className="breadcrumbs" aria-label="Shared directory location">
          {listing.breadcrumbs.map((crumb, index) => <Fragment key={crumb.path}>
            <Link to={crumb.url} className={index === listing.breadcrumbs.length - 1 ? 'current' : undefined}
              aria-current={index === listing.breadcrumbs.length - 1 ? 'page' : undefined}>{crumb.label}</Link>
            {index < listing.breadcrumbs.length - 1 && <span className="breadcrumb-separator">/</span>}
          </Fragment>)}
        </nav>
      </div>
      {listing.upUrl && <Link to={listing.upUrl} className="button-link ghost breadcrumb-up" title="Up" aria-label="Up">
        <i className="fas fa-arrow-up" aria-hidden="true" />
      </Link>}
    </section>
    <section className="toolbar public-share-toolbar" aria-label="Shared directory actions">
      <span className="muted public-share-selection-count" aria-live="polite">
        {selection.selectedItems.length} selected
      </span>
      <button className="button-link shared-download-button" type="button" disabled={disabled || selection.selectedItems.length === 0}
        onClick={() => download(sharedZipUrl(listing.downloadZipUrl, selection.selectedItems.map(entry => entry.name)))}>
        <i className="fas fa-download" aria-hidden="true" /><span>Download selected ZIP</span>
      </button>
    </section>
    <section className="browser-section" aria-label="Shared directory">
      <div className="table-wrap">
        <StableTable columns={['select', 'text', 'type', 'size', 'date', 'actions']} actionCount={2}>
          <thead><tr>
            <SelectionHeader total={listing.entries.length} selected={selection.selectedItems.length}
              onChange={checked => checked ? selection.selectAll() : selection.clearSelection()}
              label="Select all items in this table" />
            <th>Name</th><th>Type</th><th>Size</th><th>Modified</th><th>Actions</th>
          </tr></thead>
          <tbody>
            {listing.entries.map(entry => <tr key={entry.name}
              className={(entry.hidden ? 'is-hidden-item ' : '') + (selection.selected.has(entry.name) ? 'is-selected' : '')}
              data-context-item="true" data-entry-name={entry.name} data-entry-path={entry.path}
              aria-selected={selection.selected.has(entry.name)} {...interactionProps(entry)}>
              <td className="select-cell">
                <input className="row-select-checkbox" type="checkbox" checked={selection.selected.has(entry.name)}
                  aria-label={'Select ' + entry.name} onChange={event => selection.selectItem(entry, event.currentTarget.checked)} />
              </td>
              <td><div className="table-item-label">
                <Link className={'item-name' + (entry.directory ? ' directory' : '')} to={entry.openUrl} title={entry.name}>
                  {entry.directory && <i className="fas fa-folder item-icon" aria-hidden="true" />}
                  <OverflowMarquee text={entry.name} />
                </Link>
                {entry.hidden && <span className="status-badge expired hidden-badge">Hidden</span>}
              </div></td>
              <td><OverflowMarquee text={entry.directory ? 'Directory' : entry.mediaType || '-'} /></td>
              <td>{entry.directory ? '-' : entry.sizeLabel}</td><td>{entry.modifiedLabel}</td>
              <td>{entry.directory ? <span className="muted">-</span> : <div className="table-actions">
                {entry.downloadUrl && <a className="button-link ghost icon-button action-icon" href={entry.downloadUrl}
                  title="Download" aria-label="Download"><i className="fas fa-download" aria-hidden="true" /></a>}
                {entry.previewLandingUrl && <Link className="button-link ghost icon-button action-icon" to={entry.previewLandingUrl}
                  title="Preview" aria-label="Preview"><i className="fas fa-eye" aria-hidden="true" /></Link>}
              </div>}</td>
            </tr>)}
            {listing.entries.length === 0 && <tr><td colSpan={6} className="empty">This shared directory is empty.</td></tr>}
          </tbody>
        </StableTable>
      </div>
    </section>
  </section>;
}

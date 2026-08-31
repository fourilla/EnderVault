import { Fragment, useEffect, useRef } from 'react';
import { icon } from '../shared/browser/BrowserEntries';
import type { BookmarkEntry } from './types';

function SelectionHeader({
  entries,
  selected,
  select,
  label,
}: {
  entries: BookmarkEntry[];
  selected: Set<string>;
  select: (entry: BookmarkEntry, checked: boolean) => void;
  label: string;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const selectedCount = entries.filter((entry) => selected.has(entry.id)).length;
  const allSelected = entries.length > 0 && selectedCount === entries.length;
  useEffect(() => {
    if (inputRef.current) inputRef.current.indeterminate = selectedCount > 0 && !allSelected;
  }, [allSelected, selectedCount]);
  return (
    <th className="select-col">
      <label className="select-all-label" title={label}>
        <input ref={inputRef} className="select-all-checkbox" type="checkbox" checked={allSelected}
          onChange={(event) => entries.forEach((entry) => select(entry, event.target.checked))}
          aria-label={label} />
      </label>
    </th>
  );
}

function BookmarkName({ entry, browse }: { entry: BookmarkEntry; browse: (id: string) => void }) {
  const directory = entry.type === 'directory';
  return (
    <a
      className={directory ? 'item-name directory' : 'item-name bookmark-link-name'}
      href={entry.primaryUrl}
      title={directory ? entry.title : entry.url || entry.title}
      target={entry.primaryNewTab ? '_blank' : undefined}
      rel={entry.primaryNewTab ? 'noopener noreferrer' : undefined}
      onClick={directory ? (event) => { event.preventDefault(); browse(entry.id); } : undefined}
    >
      {directory ? icon('fas fa-folder item-icon') : entry.faviconUrl ? (
        <img className="bookmark-favicon" src={entry.faviconUrl} alt="" loading="lazy" />
      ) : icon('fas fa-link item-icon')}
      <span className={directory ? undefined : 'bookmark-link-title'}>{entry.title}</span>
    </a>
  );
}

export function BookmarkBreadcrumbs({
  breadcrumbs,
  browse,
}: {
  breadcrumbs: Array<{ id: string | null; label: string }>;
  browse: (id: string) => void;
}) {
  return (
    <section className="breadcrumb-panel" aria-label="Current path">
      <div className="breadcrumb-main">
        <p className="breadcrumb-label">Virtual location</p>
        <nav className="breadcrumbs">
          {breadcrumbs.map((breadcrumb, index) => (
            <Fragment key={(breadcrumb.id || 'root') + ':' + breadcrumb.label}>
              <a className={index === breadcrumbs.length - 1 ? 'current' : undefined}
                href={breadcrumb.id
                  ? `/files/bookmarks?directory=${encodeURIComponent(breadcrumb.id)}`
                  : '/files/bookmarks'}
                onClick={(event) => { event.preventDefault(); browse(breadcrumb.id || ''); }}>
                {breadcrumb.label}
              </a>
              {index < breadcrumbs.length - 1 && <span className="breadcrumb-separator">/</span>}
            </Fragment>
          ))}
        </nav>
      </div>
    </section>
  );
}

export function BookmarkTable({
  entries,
  heading,
  browse,
  selected,
  select,
  itemInteractionProps,
  toggleFavorite,
  refreshMetadata,
}: {
  entries: BookmarkEntry[];
  heading: string;
  browse: (id: string) => void;
  selected: Set<string>;
  select: (entry: BookmarkEntry, checked: boolean) => void;
  itemInteractionProps: (entry: BookmarkEntry) => Record<string, unknown>;
  toggleFavorite: (entry: BookmarkEntry) => void;
  refreshMetadata: (entry: BookmarkEntry) => void;
}) {
  return (
    <section className="browser-section bookmarks-panel" aria-label={'Bookmark ' + heading.toLowerCase()}>
      <header className="section-heading"><h2>{heading} ({entries.length})</h2></header>
      <div className="table-wrap">
        <table>
          <thead><tr>
            <SelectionHeader entries={entries} selected={selected} select={select}
              label={'Select all bookmark ' + heading.toLowerCase() + ' in this table'} />
            <th>Name</th><th>Type</th><th>Updated</th><th>Actions</th>
          </tr></thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.id} className={selected.has(entry.id) ? 'is-selected' : undefined}
                data-context-item="true" data-bookmark-id={entry.id} {...itemInteractionProps(entry)}>
                <td className="select-cell">
                  <input className="row-select-checkbox" type="checkbox" checked={selected.has(entry.id)}
                    onChange={(event) => select(entry, event.target.checked)} aria-label={'Select ' + entry.title} />
                </td>
                <td><BookmarkName entry={entry} browse={browse} /></td>
                <td>{entry.type === 'directory' ? 'Directory' : 'Link'}</td>
                <td>{entry.updatedLabel}</td>
                <td><div className="table-actions">
                  <button className={'ghost icon-button action-icon favorite-toggle'
                    + (entry.favorite ? ' is-favorite' : '')} type="button"
                    title={entry.favorite ? 'Remove from favorites' : 'Add to favorites'}
                    aria-label={entry.favorite ? 'Remove from favorites' : 'Add to favorites'}
                    onClick={(event) => {
                      event.stopPropagation();
                      toggleFavorite(entry);
                    }}>{icon('fas fa-star')}</button>
                  {entry.type === 'directory' ? (
                    <button className="ghost icon-button action-icon" type="button" title="Open directory"
                      aria-label="Open directory" onClick={() => browse(entry.id)}>{icon('fas fa-folder-open')}</button>
                  ) : (
                    <a className="button-link ghost icon-button action-icon" href={entry.openUrl}
                      target="_blank" rel="noopener noreferrer" title="Open link" aria-label="Open link">
                      {icon('fas fa-arrow-up-right-from-square')}
                    </a>
                  )}
                  <a className="button-link ghost icon-button action-icon" href={entry.detailUrl}
                    title="Details" aria-label="Details">{icon('fas fa-circle-info')}</a>
                  {entry.metadataRefreshable && (
                    <button className="ghost icon-button action-icon" type="button"
                      title="Fetch title and favicon" aria-label="Fetch title and favicon"
                      onClick={(event) => {
                        event.stopPropagation();
                        refreshMetadata(entry);
                      }}>{icon('fas fa-wand-magic-sparkles')}</button>
                  )}
                </div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

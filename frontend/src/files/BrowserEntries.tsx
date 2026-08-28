import { MouseEvent, useEffect, useRef } from 'react';
import type { BrowserEntry } from './types';

export const icon = (className: string) => <i className={className} aria-hidden="true" />;

function EntryName({
  entry,
  onBrowse,
}: {
  entry: BrowserEntry;
  onBrowse: (path: string) => void;
}) {
  const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
    if (entry.type !== 'directory') return;
    event.preventDefault();
    onBrowse(entry.path);
  };
  const href = entry.type === 'directory' ? '/files' : entry.detailUrl;
  return (
    <a
      className={'item-name' + (entry.type === 'directory' ? ' directory' : '')}
      href={href}
      title={entry.name}
      onClick={handleClick}
    >
      {entry.type === 'directory' && icon('fas fa-folder item-icon')}
      <span>{entry.name}</span>
    </a>
  );
}

function EntryActions({ entry }: { entry: BrowserEntry }) {
  return (
    <>
      {entry.previewUrl && (
        <a
          className="ghost icon-button action-icon"
          href={entry.previewUrl}
          target="_blank"
          rel="noopener noreferrer"
          title="Preview"
          aria-label="Preview"
        >
          {icon('fas fa-eye')}
        </a>
      )}
      {entry.downloadUrl && (
        <a
          className="ghost icon-button action-icon"
          href={entry.downloadUrl}
          title="Download"
          aria-label="Download"
        >
          {icon('fas fa-download')}
        </a>
      )}
      <a
        className="ghost icon-button action-icon"
        href={entry.detailUrl}
        title="Details"
        aria-label="Details"
      >
        {icon('fas fa-circle-info')}
      </a>
    </>
  );
}

function SelectAllCheckbox({
  entries,
  selected,
  onSelect,
}: {
  entries: BrowserEntry[];
  selected: Set<string>;
  onSelect: (entry: BrowserEntry, checked: boolean) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const selectedCount = entries.filter((entry) => selected.has(entry.path)).length;
  const allSelected = entries.length > 0 && selectedCount === entries.length;

  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.indeterminate = selectedCount > 0 && !allSelected;
    }
  }, [allSelected, selectedCount]);

  return (
    <input
      ref={inputRef}
      type="checkbox"
      checked={allSelected}
      onChange={(event) => entries.forEach((entry) => onSelect(entry, event.target.checked))}
      aria-label="Select all items in this table"
    />
  );
}

export function EntryTable({
  entries,
  search,
  onBrowse,
  selected,
  onSelect,
  onFavorite,
  itemInteractionProps,
}: {
  entries: BrowserEntry[];
  search: boolean;
  onBrowse: (path: string) => void;
  selected: Set<string>;
  onSelect: (entry: BrowserEntry, checked: boolean) => void;
  onFavorite: (entry: BrowserEntry) => void;
  itemInteractionProps: (entry: BrowserEntry) => Record<string, unknown>;
}) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {!search && (
              <th className="select-cell">
                <SelectAllCheckbox entries={entries} selected={selected} onSelect={onSelect} />
              </th>
            )}
            <th>Name</th>
            {search && <th>Location</th>}
            <th>Type</th>
            <th>Size</th>
            <th>Modified</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr
              key={entry.path}
              className={(entry.hidden ? 'is-hidden-item ' : '')
                + (selected.has(entry.path) ? 'is-selected' : '')}
              data-context-item="true"
              data-entry-path={entry.path}
              data-entry-kind={entry.type}
              {...itemInteractionProps(entry)}
            >
              {!search && (
                <td className="select-cell">
                  <input
                    type="checkbox"
                    checked={selected.has(entry.path)}
                    onChange={(event) => onSelect(entry, event.target.checked)}
                    aria-label={'Select ' + entry.name}
                  />
                </td>
              )}
              <td>
                <EntryName entry={entry} onBrowse={onBrowse} />
                {entry.hidden && <span className="status-badge expired hidden-badge">Hidden</span>}
              </td>
              {search && (
                <td>
                  <button
                    className="muted files-location-link"
                    type="button"
                    onClick={() => onBrowse(entry.parentPath)}
                    title={entry.parentPath || 'Root'}
                  >
                    {entry.parentPath || 'Root'}
                  </button>
                </td>
              )}
              <td>{entry.typeLabel}</td>
              <td>{entry.sizeLabel}</td>
              <td>{entry.modifiedLabel}</td>
              <td>
                <div className="table-actions">
                  <button
                    className={'ghost icon-button action-icon favorite-toggle'
                      + (entry.favorite ? ' is-favorite' : '')}
                    type="button"
                    title={entry.favorite ? 'Remove from favorites' : 'Add to favorites'}
                    aria-label={entry.favorite ? 'Remove from favorites' : 'Add to favorites'}
                    onClick={() => onFavorite(entry)}
                  >
                    {icon('fas fa-star')}
                  </button>
                  <EntryActions entry={entry} />
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function EntryGrid({
  entries,
  onBrowse,
  selected,
  onSelect,
  itemInteractionProps,
}: {
  entries: BrowserEntry[];
  onBrowse: (path: string) => void;
  selected: Set<string>;
  onSelect: (entry: BrowserEntry, checked: boolean) => void;
  itemInteractionProps: (entry: BrowserEntry) => Record<string, unknown>;
}) {
  return (
    <div className="browser-grid">
      {entries.map((entry) => {
        const href = entry.type === 'directory' ? '/files' : entry.detailUrl;
        const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
          if (entry.type !== 'directory') return;
          event.preventDefault();
          onBrowse(entry.path);
        };
        return (
          <article
            className={'browser-card'
              + (entry.hidden ? ' is-hidden-item' : '')
              + (selected.has(entry.path) ? ' is-selected' : '')}
            key={entry.path}
            data-context-item="true"
            data-entry-path={entry.path}
            data-entry-kind={entry.type}
            {...itemInteractionProps(entry)}
          >
            <input
              className="card-check"
              type="checkbox"
              checked={selected.has(entry.path)}
              onChange={(event) => onSelect(entry, event.target.checked)}
              aria-label={'Select ' + entry.name}
            />
            {entry.hidden && (
              <span className="status-badge expired hidden-badge card-hidden-badge">Hidden</span>
            )}
            <a className="card-thumb" href={href} onClick={handleClick}>
              {entry.type === 'directory' ? (
                <span className="files-directory-thumb">{icon('fas fa-folder')}</span>
              ) : entry.thumbnailUrl ? (
                <img
                  className="thumb-media"
                  loading="lazy"
                  src={entry.thumbnailUrl}
                  alt={entry.name}
                />
              ) : null}
              {entry.type === 'file' && (
                <span
                  className={'thumb-extension '
                    + (entry.thumbnailUrl ? 'thumb-extension-overlay' : 'thumb-extension-center')}
                >
                  {entry.extensionLabel}
                </span>
              )}
            </a>
            <div className="card-body">
              <a className="card-name" href={href} onClick={handleClick} title={entry.name}>
                {entry.name}
              </a>
              <p className="card-meta">
                <span>{entry.typeLabel}</span>
                <span>{entry.sizeLabel}</span>
              </p>
              <p className="card-meta">{entry.modifiedLabel}</p>
            </div>
          </article>
        );
      })}
    </div>
  );
}

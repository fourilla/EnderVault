import { Fragment } from 'react';
import { icon } from './BrowserEntries';
import type { BrowserPayload } from './types';

export function BrowserBreadcrumbs({
  payload,
  browse,
}: {
  payload: BrowserPayload | null;
  browse: (path: string) => void;
}) {
  return (
    <section className="breadcrumb-panel" aria-label="Current location">
      <div className="breadcrumb-main">
        <p className="breadcrumb-label">
          {payload?.mode === 'search' ? 'Search in' : 'Location'}
        </p>
        <nav className="breadcrumbs">
          {(payload?.breadcrumbs || [{ label: 'Root', path: '' }]).map((breadcrumb, index, items) => (
            <Fragment key={breadcrumb.path + ':' + breadcrumb.label}>
              <a
                className={index === items.length - 1 ? 'current' : undefined}
                href="/files"
                onClick={(event) => {
                  event.preventDefault();
                  browse(breadcrumb.path);
                }}
              >
                {breadcrumb.label}
              </a>
              {index < items.length - 1 && <span className="breadcrumb-separator">/</span>}
            </Fragment>
          ))}
        </nav>
      </div>
      {payload?.mode === 'search' ? (
        <a
          className="button-link ghost icon-button"
          href="/files"
          title="Back to files"
          aria-label="Back to files"
          onClick={(event) => {
            event.preventDefault();
            browse(payload.path);
          }}
        >
          {icon('fas fa-folder-open')}
        </a>
      ) : payload?.parentPath != null ? (
        <a
          className="button-link ghost breadcrumb-up"
          href="/files"
          title="Up"
          aria-label="Up"
          onClick={(event) => {
            event.preventDefault();
            browse(payload.parentPath || '');
          }}
        >
          {icon('fas fa-arrow-up')}
        </a>
      ) : null}
    </section>
  );
}

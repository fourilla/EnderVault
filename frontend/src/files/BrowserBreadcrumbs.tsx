import { Fragment } from 'react';
import { icon } from '../shared/browser/BrowserEntries';
import type { BrowserHistoryState, BrowserPayload } from './types';

const breadcrumbsForPath = (path: string) => {
  const parts = path.split('/').filter(Boolean);
  return [
    { label: 'Root', path: '' },
    ...parts.map((label, index) => ({
      label,
      path: parts.slice(0, index + 1).join('/'),
    })),
  ];
};

export function BrowserBreadcrumbs({
  payload,
  currentState,
  browse,
}: {
  payload: BrowserPayload | null;
  currentState: BrowserHistoryState;
  browse: (path: string) => void;
}) {
  const mode = payload?.mode || currentState.mode;
  const path = payload?.path ?? currentState.path;
  const breadcrumbs = payload?.breadcrumbs || breadcrumbsForPath(path);
  return (
    <section className="breadcrumb-panel" aria-label="Current location">
      <div className="breadcrumb-main">
        <p className="breadcrumb-label">
          {mode === 'search' ? 'Search in' : 'Location'}
        </p>
        <nav className="breadcrumbs">
          {breadcrumbs.map((breadcrumb, index, items) => (
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
      {mode === 'search' ? (
        <a
          className="button-link ghost icon-button"
          href="/files"
          title="Back to files"
          aria-label="Back to files"
          onClick={(event) => {
            event.preventDefault();
            browse(path);
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

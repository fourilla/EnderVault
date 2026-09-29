import type { ReactNode } from 'react';
import { PageErrorPanel } from '../../shared/layout/PageErrorPanel';

export function SettingsLoadState({ loaded, error, refresh, loadingLabel, children }: {
  loaded: boolean;
  error: string;
  refresh: () => void;
  loadingLabel: string;
  children?: ReactNode;
}) {
  return <div className="page-feedback-layout settings-load-state">
    {error && <PageErrorPanel title="Settings unavailable" message={error} stale={loaded}
      actions={<button className="icon-text-button" type="button" onClick={refresh}>
        <i className="fas fa-arrows-rotate" aria-hidden="true" /><span>Retry</span>
      </button>} />}
    {!loaded && !error && <div className="settings-spa-loading" role="status">{loadingLabel}</div>}
    {children}
  </div>;
}

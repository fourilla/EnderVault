import { useId, type ReactNode } from 'react';
import './page-error-panel.css';

export function PageErrorPanel({ title = 'Unable to load data', message, stale = false, actions }: {
  title?: string;
  message: string;
  stale?: boolean;
  actions?: ReactNode;
}) {
  const titleId = useId();
  return <section className="page-error-panel" role="alert" aria-labelledby={titleId}>
    <i className="fas fa-triangle-exclamation page-error-icon" aria-hidden="true" />
    <div className="page-error-body">
      <h2 id={titleId}>{title}</h2>
      <p className="page-error-message">{message}</p>
      {stale && <p className="page-error-note">Showing the last loaded values.</p>}
      {actions && <div className="form-actions end page-error-actions">{actions}</div>}
    </div>
  </section>;
}

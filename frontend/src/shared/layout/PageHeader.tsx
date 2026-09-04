import type { ReactNode } from 'react';

const classNames = (...values: Array<string | undefined>) => values.filter(Boolean).join(' ');

export function PageHeader({
  title,
  description,
  actions,
  className,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <section className={classNames('pathbar', 'page-header', className)}>
      <div className="pathbar-title-group">
        <div className="page-header-copy">
          <h1>{title}</h1>
          {description && <p>{description}</p>}
        </div>
        {actions && <div className="page-header-actions">{actions}</div>}
      </div>
    </section>
  );
}

export function PageBreadcrumbs({
  parent,
  current,
  label,
}: {
  parent: ReactNode;
  current: ReactNode;
  label: string;
}) {
  return (
    <nav className="pathbar page-location" aria-label={label}>
      <div className="breadcrumbs">
        {parent}
        <span className="breadcrumb-separator" aria-hidden="true">/</span>
        <span className="breadcrumb-current" aria-current="page">{current}</span>
      </div>
    </nav>
  );
}

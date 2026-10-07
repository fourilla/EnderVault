import type { CSSProperties, ReactNode } from 'react';
import './stable-table.css';

export type StableTableColumn = 'select' | 'text' | 'type' | 'size' | 'date' | 'status' | 'usage' | 'restrictions' | 'actions';

export function StableTable({ columns, actionCount, children, className = '' }: {
  columns: StableTableColumn[];
  actionCount: number;
  children: ReactNode;
  className?: string;
}) {
  // Flexible columns share the remainder; minimum width depends on the schema, not row data.
  const minimum = columns.map(column => `var(--column-${column})`).join(' + ');
  const style = {
    '--stable-table-minimum': `calc(${minimum})`,
    '--column-actions': `calc(${actionCount} * var(--table-action-icon-size) + ${Math.max(0, actionCount - 1)} * var(--space-sm) + 2 * var(--stable-cell-padding))`,
  } as CSSProperties;
  return <table className={`stable-table ${className}`.trim()} style={style}>
    <colgroup>{columns.map((column, index) => <col key={index}
      style={column === 'text' ? undefined : { width: `var(--column-${column})` }} />)}</colgroup>
    {children}
  </table>;
}

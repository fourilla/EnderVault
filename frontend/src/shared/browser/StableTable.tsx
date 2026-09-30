import type { CSSProperties, ReactNode } from 'react';
import './stable-table.css';

type Column = 'select' | 'text' | 'type' | 'size' | 'date' | 'actions';

export function StableTable({ columns, actionCount, children }: {
  columns: Column[];
  actionCount: number;
  children: ReactNode;
}) {
  // Flexible columns share the remainder; minimum width depends on the schema, not row data.
  const minimum = columns.map(column => `var(--column-${column})`).join(' + ');
  const style = {
    '--stable-table-minimum': `calc(${minimum})`,
    '--column-actions': `calc(${actionCount} * var(--table-action-icon-size) + ${Math.max(0, actionCount - 1)} * var(--space-sm) + 2 * var(--stable-cell-padding))`,
  } as CSSProperties;
  return <table className="stable-table" style={style}>
    <colgroup>{columns.map((column, index) => <col key={index}
      style={column === 'text' ? undefined : { width: `var(--column-${column})` }} />)}</colgroup>
    {children}
  </table>;
}

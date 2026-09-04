import { useEffect, useRef, type MouseEvent } from 'react';
import type { MetadataAreaReport } from './types';

interface MetadataIssueTableProps {
  report: MetadataAreaReport;
  selected: Set<string>;
  onSelectionChange: (next: Set<string>) => void;
  disabled: boolean;
}

export function MetadataIssueTable({
  report,
  selected,
  onSelectionChange,
  disabled,
}: MetadataIssueTableProps) {
  const selectAll = useRef<HTMLInputElement>(null);
  const repairableTokens = report.issues.filter((issue) => issue.repairable).map((issue) => issue.token);
  const selectedCount = repairableTokens.filter((token) => selected.has(token)).length;
  const allSelected = repairableTokens.length > 0 && selectedCount === repairableTokens.length;

  useEffect(() => {
    if (selectAll.current) {
      selectAll.current.indeterminate = selectedCount > 0 && !allSelected;
    }
  }, [allSelected, selectedCount]);

  const setToken = (token: string, checked: boolean) => {
    const next = new Set(selected);
    if (checked) next.add(token);
    else next.delete(token);
    onSelectionChange(next);
  };

  const toggleRow = (event: MouseEvent<HTMLTableRowElement>, token: string, repairable: boolean) => {
    if (!repairable || disabled || (event.target as HTMLElement).closest('button, input, label, select, textarea, a')) return;
    setToken(token, !selected.has(token));
  };

  const toggleAll = (checked: boolean) => {
    const next = new Set(selected);
    repairableTokens.forEach((token) => {
      if (checked) next.add(token);
      else next.delete(token);
    });
    onSelectionChange(next);
  };

  return (
    <div className="table-wrap compact-table metadata-table-wrap">
      <table>
        <thead>
          <tr>
            <th className="select-cell">
              <input ref={selectAll} type="checkbox" checked={allSelected}
                disabled={disabled || repairableTokens.length === 0}
                aria-label="Select all repairable issues in this table"
                onChange={(event) => toggleAll(event.currentTarget.checked)} />
            </th>
            <th>Severity</th><th>Issue</th><th>Detail</th><th>Action</th>
          </tr>
        </thead>
        <tbody>
          {report.issues.map((issue) => (
            <tr key={issue.token} data-metadata-repairable={issue.repairable}
              className={selected.has(issue.token) ? 'is-selected' : undefined}
              aria-selected={selected.has(issue.token)}
              onClick={(event) => toggleRow(event, issue.token, issue.repairable)}>
              <td className="select-cell">
                {issue.repairable ? (
                  <input type="checkbox" checked={selected.has(issue.token)} disabled={disabled}
                    aria-label={`Select ${issue.title}`}
                    onChange={(event) => setToken(issue.token, event.currentTarget.checked)} />
                ) : <span>-</span>}
              </td>
              <td><span className={`status-badge ${issue.severityClass}`}>{issue.severityLabel}</span></td>
              <td><strong>{issue.title}</strong><small>{issue.recommendation}</small></td>
              <td>{issue.detail}</td>
              <td>{issue.actionLabel}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

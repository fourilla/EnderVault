import { useAdminApp } from '../../app/AdminAppContext';
import type { StableTableColumn } from './StableTable';

// Opt in only when every row action has a context-menu or click alternative.
export function useTableColumns(schema: StableTableColumn[]) {
  const { bootstrap } = useAdminApp();
  const showActions = bootstrap.browser.showTableActions;
  const columns = showActions ? schema : schema.filter(column => column !== 'actions');
  return { columns, showActions, columnCount: columns.length };
}

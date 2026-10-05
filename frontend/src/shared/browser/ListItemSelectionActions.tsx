import { FloatingPageActions } from '../../app/FloatingPageActions';
import type { ReactNode } from 'react';
import type { ListItemActionController } from './list-item-actions';

export function ListItemSelectionActions<T>({ items, itemKey, actions, label, pageActions }: {
  items: readonly T[]; itemKey: (item: T) => string; actions: ListItemActionController<T>; label: string;
  pageActions?: ReactNode;
}) {
  const definitions = actions.selectionDefinitions(items);
  const ids = items.map(itemKey);
  return <FloatingPageActions mode="menu" label={label} selectedCount={items.length}>
    <div className="toolbar-cluster">
      <div className="toolbar-actions file-actions" aria-label={label}>
        {definitions.map((action) => <button key={action.id} type="button"
          className={`icon-button${action.danger ? ' danger' : ''}`}
          disabled={Boolean(actions.unavailableReason(ids, action.id))} aria-label={action.label}
          title={actions.unavailableReason(ids, action.id) || action.label}
          onClick={() => void actions.runSelected(ids, action.id)}>
          <i className={action.icon} aria-hidden="true" />
        </button>)}
        {!definitions.length && <button type="button" className="icon-button" disabled
          aria-label={items.length ? `No common action for these ${actions.itemLabel()}` : `Select ${actions.itemLabel()} first`}
          title={items.length ? `Select ${actions.itemLabel()} with the same available actions. No subset will be processed.` : `Select ${actions.itemLabel()} first.`}>
          <i className="fas fa-circle-info" aria-hidden="true" />
        </button>}
      </div>
      {pageActions && <div className="toolbar-actions floating-actions-page-group" aria-label="Whole-list actions">
        {pageActions}
      </div>}
    </div>
  </FloatingPageActions>;
}

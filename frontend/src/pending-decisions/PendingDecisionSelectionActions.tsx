import { FloatingPageActions } from '../app/FloatingPageActions';
import { NO_COMMON_PENDING_ACTION, pendingDecisionActionsFor } from './pending-decision-actions';
import type { PendingDecisionController } from './pending-decision-controller';
import type { PendingFileDecision } from './types';

export function PendingDecisionSelectionActions({ items, actions }: {
  items: readonly PendingFileDecision[]; actions: PendingDecisionController;
}) {
  const definitions = pendingDecisionActionsFor(items);
  const ids = items.map((item) => item.id);
  return <FloatingPageActions mode="menu" label="Pending decision actions" selectedCount={items.length}>
    <div className="toolbar-cluster">
      <div className="toolbar-actions file-actions" aria-label="Selected pending decision actions">
        {definitions.map((action) => <button key={action.id} type="button"
          className={`icon-button${action.danger ? ' danger' : ''}`}
          disabled={Boolean(actions.unavailableReason(ids, action.id))} aria-label={action.label}
          title={actions.unavailableReason(ids, action.id) || action.description}
          onClick={() => void actions.runSelected(ids, action.id)}>
          <i className={action.icon} aria-hidden="true" />
        </button>)}
        {!definitions.length && <button type="button" className="icon-button" disabled
          aria-label={items.length ? 'No common action for these items' : 'Select pending items first'}
          title={items.length ? NO_COMMON_PENDING_ACTION : 'Select pending items first.'}>
          <i className="fas fa-circle-info" aria-hidden="true" />
        </button>}
      </div>
    </div>
  </FloatingPageActions>;
}

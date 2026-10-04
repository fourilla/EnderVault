import { pendingDecisionActionDefinitions, pendingDecisionActionsFor } from './pending-decision-actions';
import type { PendingDecisionController } from './pending-decision-controller';
import type { PendingFileDecision } from './types';

export function PendingDecisionActions({ decision, actions, labelled = false }: {
  decision: PendingFileDecision; actions: PendingDecisionController; labelled?: boolean;
}) {
  const preparing = actions.preparing(decision.id);
  const supported = pendingDecisionActionsFor([decision]);
  const definitions = preparing
    ? [...pendingDecisionActionDefinitions.filter((action) => action.id === 'MERGE'),
      ...supported.filter((action) => action.id !== 'MERGE' && action.id !== 'REVIEW')]
    : supported;
  const buttonClass = labelled ? 'icon-text-button' : 'icon-button action-icon';
  return <div className={labelled ? 'pending-decision-action-grid' : 'table-actions'}>
    {definitions.map((action) => <button key={action.id} type="button"
      className={`${action.danger ? 'danger' : 'ghost'} ${buttonClass}`}
      title={labelled ? action.description : action.label} aria-label={action.label}
      disabled={!actions.canRun(decision.id, action.id)} onClick={() => void actions.run(decision.id, action.id)}>
      <i className={preparing && action.id === 'MERGE' ? 'fas fa-spinner fa-spin' : action.icon} aria-hidden="true" />
      {labelled && <span>{action.label}</span>}
    </button>)}
  </div>;
}

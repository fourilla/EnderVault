import { useEffect, useRef, useState } from 'react';
import { useDecisionDialog } from './DecisionDialogContext';
import { createPendingDecisionController, type PendingDecisionControllerOptions } from './pending-decision-controller';
import type { PendingFileDecision } from './types';

export function usePendingDecisionActions(options: Omit<PendingDecisionControllerOptions, 'decision' | 'openMerge'> & {
  items: readonly PendingFileDecision[];
}) {
  const { openMerge } = useDecisionDialog();
  const [, redraw] = useState(0);
  const context = useRef({ key: options.contextKey, token: {} });
  if (context.current.key !== options.contextKey) context.current = { key: options.contextKey, token: {} };
  const currentOptions: PendingDecisionControllerOptions = { ...options, contextKey: context.current.token, openMerge,
    decision: (id) => options.items.find((item) => item.id === id) };
  const latest = useRef(currentOptions);
  latest.current = currentOptions;
  const controller = useRef<ReturnType<typeof createPendingDecisionController>>(null);
  if (!controller.current) {
    controller.current = createPendingDecisionController(() => latest.current, () => redraw((value) => value + 1));
  }
  const actions = controller.current;
  useEffect(() => { actions.activate(); return actions.dispose; }, [actions]);
  return actions;
}

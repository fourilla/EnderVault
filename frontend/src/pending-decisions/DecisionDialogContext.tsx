import { createContext, useCallback, useContext, useEffect, useRef, useState, type PropsWithChildren } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { DirectoryMergeDialog } from '../directory-merges/DirectoryMergeDialog';
import { useTopbarPopover } from '../app/TopbarPopoverContext';
import { PendingDecisionDialog } from './PendingDecisionDialog';
import { observeNewDecisions, type DecisionTarget } from './decision-auto-open';

const DecisionDialogContext = createContext<{ openMerge: (id: string) => boolean; openPending: (id: string) => boolean } | null>(null);

export function DecisionDialogProvider({ children }: PropsWithChildren) {
  const location = useLocation();
  const navigate = useNavigate();
  const { closeAll } = useTopbarPopover();
  const [selected, setSelected] = useState<DecisionTarget | null>(null);
  const current = useRef<DecisionTarget | null>(null);
  const open = useCallback((target: DecisionTarget) => {
    // Ignore competing requests rather than replacing a review with unsaved input.
    if (current.current !== null) return current.current.id === target.id && current.current.kind === target.kind;
    current.current = target;
    setSelected(target);
    closeAll();
    return true;
  }, [closeAll]);
  const openMerge = useCallback((id: string) => open({ kind: 'merge', id }), [open]);
  const openPending = useCallback((id: string) => open({ kind: 'pending', id }), [open]);
  const replaceWithMerge = useCallback((id: string) => {
    const target: DecisionTarget = { kind: 'merge', id };
    current.current = target;
    setSelected(target);
  }, []);
  useEffect(() => observeNewDecisions(open), [open]);
  const close = useCallback(() => {
    current.current = null;
    setSelected(null);
    if (location.pathname === '/admin/pending-decisions' && location.hash.startsWith('#merge-')) {
      navigate(location.pathname + location.search, { replace: true, preventScrollReset: true });
    }
  }, [location.pathname, location.search, location.hash, navigate]);
  const changed = useCallback(() => {
    window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
  }, []);

  useEffect(() => {
    // Navigation dismisses a view, never the durable decision; deep links are explicit requests.
    current.current = null;
    setSelected(null);
    if (location.pathname === '/admin/pending-decisions' && location.hash.startsWith('#merge-')) {
      openMerge(location.hash.slice(7));
    }
  }, [location.pathname, location.search, location.hash, openMerge]);

  return <DecisionDialogContext.Provider value={{ openMerge, openPending }}>
    {children}
    {selected?.kind === 'merge' && <DirectoryMergeDialog key={selected.id} id={selected.id} close={close} changed={changed} />}
    {selected?.kind === 'pending' && <PendingDecisionDialog key={selected.id} id={selected.id} close={close} openMerge={replaceWithMerge} />}
  </DecisionDialogContext.Provider>;
}

export function useDecisionDialog() {
  const context = useContext(DecisionDialogContext);
  if (!context) throw new Error('DecisionDialogProvider is missing.');
  return context;
}

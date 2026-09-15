import { createContext, useCallback, useContext, useEffect, useRef, useState, type PropsWithChildren } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { DirectoryMergeDialog } from '../directory-merges/DirectoryMergeDialog';
import { useTopbarPopover } from '../app/TopbarPopoverContext';

const DecisionDialogContext = createContext<{ openMerge: (id: string) => boolean } | null>(null);

export function DecisionDialogProvider({ children }: PropsWithChildren) {
  const location = useLocation();
  const navigate = useNavigate();
  const { closeAll } = useTopbarPopover();
  const [selected, setSelected] = useState<string | null>(null);
  const current = useRef<string | null>(null);
  const openMerge = useCallback((id: string) => {
    // Ignore competing requests rather than replacing a review with unsaved input.
    if (current.current !== null) return current.current === id;
    current.current = id;
    setSelected(id);
    closeAll();
    return true;
  }, [closeAll]);
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

  return <DecisionDialogContext.Provider value={{ openMerge }}>
    {children}
    {selected && <DirectoryMergeDialog key={selected} id={selected} close={close} changed={changed} />}
  </DecisionDialogContext.Provider>;
}

export function useDecisionDialog() {
  const context = useContext(DecisionDialogContext);
  if (!context) throw new Error('DecisionDialogProvider is missing.');
  return context;
}

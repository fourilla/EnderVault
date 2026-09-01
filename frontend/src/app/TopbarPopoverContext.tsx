import {
  createContext,
  type PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { useLocation } from 'react-router-dom';

interface TopbarPopoverContextValue {
  activeId: string | null;
  show: (id: string) => void;
  hide: (id: string) => void;
  closeAll: () => void;
}

const TopbarPopoverContext = createContext<TopbarPopoverContextValue | null>(null);

export function TopbarPopoverProvider({ children }: PropsWithChildren) {
  const [activeId, setActiveId] = useState<string | null>(null);
  const location = useLocation();

  const closeAll = useCallback(() => {
    setActiveId(null);
  }, []);

  useEffect(() => closeAll(), [closeAll, location.pathname, location.search]);

  const value = useMemo<TopbarPopoverContextValue>(() => ({
    activeId,
    show: (id) => setActiveId(id),
    hide: (id) => setActiveId((current) => current === id ? null : current),
    closeAll,
  }), [activeId, closeAll]);

  return <TopbarPopoverContext.Provider value={value}>{children}</TopbarPopoverContext.Provider>;
}

export function useTopbarPopover() {
  const value = useContext(TopbarPopoverContext);
  if (!value) throw new Error('TopbarPopoverProvider is missing.');
  return value;
}

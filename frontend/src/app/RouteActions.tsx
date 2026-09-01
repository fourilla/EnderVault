import {
  createContext,
  type PropsWithChildren,
  type ReactNode,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';

interface RouteActionsContextValue {
  actions: ReactNode;
  setActions: (actions: ReactNode) => void;
}

const RouteActionsContext = createContext<RouteActionsContextValue | null>(null);

export function RouteActionsProvider({ children }: PropsWithChildren) {
  const [actions, setActions] = useState<ReactNode>(null);
  const value = useMemo(() => ({ actions, setActions }), [actions]);
  return (
    <RouteActionsContext.Provider value={value}>
      {children}
    </RouteActionsContext.Provider>
  );
}

export function RouteActionSlot() {
  return <>{useContext(RouteActionsContext)?.actions}</>;
}

export function useRouteActions(actions: ReactNode) {
  const setActions = useContext(RouteActionsContext)?.setActions;
  useEffect(() => {
    setActions?.(actions);
    return () => setActions?.(null);
  }, [actions, setActions]);
}

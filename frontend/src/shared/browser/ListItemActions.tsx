import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { icon } from './BrowserEntries';
import { createListItemActions, type ListItemActionController, type ListItemActionOptions } from './list-item-actions';

export function useListItemActions<T>(options: ListItemActionOptions<T>) {
  const [, redraw] = useState(0);
  const context = useRef({ key: options.contextKey, token: {} });
  if (context.current.key !== options.contextKey) context.current = { key: options.contextKey, token: {} };
  const latest = useRef(options);
  latest.current = { ...options, contextKey: context.current.token };
  const controller = useRef<ListItemActionController<T> | null>(null);
  if (!controller.current) controller.current = createListItemActions(() => latest.current, () => redraw((value) => value + 1));
  const actions = controller.current;
  useEffect(() => { actions.activate(); return actions.dispose; }, [actions]);
  return actions;
}

export function ListItemActions<T>({ item, itemKey, actions }: {
  item: T; itemKey: (item: T) => string; actions: ListItemActionController<T>;
}) {
  return <div className="table-actions">
    {actions.definitions().filter((action) => action.supports(item)).map((action) => {
      const className = `${action.danger ? 'danger' : 'ghost'} icon-button action-icon`;
      return action.href && !actions.disabled()
        ? <Link key={action.id} className={`button-link ${className}`} title={action.label} aria-label={action.label}
          to={action.href(item)}>{icon(action.icon)}</Link>
        : <button key={action.id} type="button" className={className} title={action.label} aria-label={action.label}
          disabled={actions.disabled()} onClick={() => actions.run(itemKey(item), action.id)}>{icon(action.icon)}</button>;
    })}
  </div>;
}

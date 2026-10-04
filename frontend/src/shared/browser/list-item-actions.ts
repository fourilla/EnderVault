import { toastError } from '../api/form-api';
import type { BrowserMenuAction } from './browser-menu-context';

export interface ListItemAction<T> {
  id: string;
  label: string;
  icon: string;
  group: string;
  danger?: boolean;
  href?: (item: T) => string;
  supports: (item: T) => boolean;
  confirmation?: { title: string; message: string; confirmLabel: string; danger: boolean };
  changesList?: boolean;
  execute: (item: T) => unknown | Promise<unknown>;
}

export interface ListItemActionOptions<T> {
  items: readonly T[];
  definitions: readonly ListItemAction<T>[];
  enabled: boolean;
  blocked: () => boolean;
  contextKey: unknown;
  isCurrent: () => boolean;
  itemKey: (item: T) => string;
  itemIdentity: (item: T) => string;
  reload: () => void;
}

export function createListItemActions<T>(options: () => ListItemActionOptions<T>, changed: () => void) {
  let active = false;
  let lifetime = 0;
  let busy = false;
  const find = (id: string) => options().items.find((item) => options().itemKey(item) === id);
  const actionFor = (id: string) => options().definitions.find((action) => action.id === id);
  const disabled = () => busy || options().blocked() || !options().enabled;
  const run = async (id: string, actionId: string) => {
    const initial = options(), item = find(id), action = actionFor(actionId);
    if (!active || disabled() || !initial.isCurrent() || !item || !action?.supports(item)) return;
    const identity = initial.itemIdentity(item), context = initial.contextKey, started = lifetime;
    const current = () => active && lifetime === started && options().contextKey === context
      && initial.isCurrent() && options().isCurrent();
    busy = true;
    changed();
    try {
      if (action.confirmation && !await window.EnderVault?.askConfirmation(action.confirmation)) return;
      const latest = options(), target = find(id), latestAction = actionFor(actionId);
      if (!current() || !latest.enabled || latest.blocked() || !target || !latestAction?.supports(target)
        || latest.itemIdentity(target) !== identity) return;
      await latestAction.execute(target);
      if (current() && latestAction.changesList) options().reload();
    } catch (reason) {
      toastError(reason, 'The link action failed.');
    } finally {
      busy = false;
      if (active) changed();
    }
  };
  return {
    activate() { active = true; lifetime += 1; },
    dispose() { active = false; lifetime += 1; },
    isBusy: () => busy,
    disabled,
    definitions: () => options().definitions,
    run,
  };
}

export type ListItemActionController<T> = ReturnType<typeof createListItemActions<T>>;

export function listItemMenuActions<T>(controller: ListItemActionController<T>, itemKey: (item: T) => string): BrowserMenuAction<T>[] {
  return controller.definitions().map((action) => ({
    id: action.id, group: action.group, label: action.label, icon: action.icon, danger: action.danger,
    visible: ({ mode, item }) => mode === 'single' && item !== null && action.supports(item),
    disabled: () => controller.disabled(),
    run: ({ mode, item }) => mode === 'single' && item ? controller.run(itemKey(item), action.id) : undefined,
  }));
}

export async function copyListLink(value: string, message: string) {
  const client = window.EnderVault;
  if (!client) throw new Error('Clipboard services are unavailable.');
  if (await client.copyText(value)) client.showToast('success', message);
}

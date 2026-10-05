import { toastError } from '../api/form-api';
import type { BrowserMenuAction } from './browser-menu-context';

export const MAX_LIST_BULK_ITEMS = 200;
export interface ListItemBulkResult {
  ok: boolean;
  succeededCount: number;
  failedCount: number;
  results: { id: string; status: 'APPLIED' | 'NOT_FOUND' | 'REJECTED' | 'FAILED'; message: string }[];
}
type Confirmation = { title: string; message: string; confirmLabel: string; danger: boolean };

export interface ListItemAction<T> {
  id: string;
  label: string;
  icon: string;
  group: string;
  danger?: boolean;
  href?: (item: T) => string;
  supports: (item: T) => boolean;
  confirmation?: Confirmation;
  changesList?: boolean;
  execute: (item: T) => unknown | Promise<unknown>;
  bulk?: { confirmation: (count: number) => Confirmation; execute: (ids: readonly string[]) => Promise<ListItemBulkResult> };
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
  selectedIds?: () => readonly string[];
  bulkResolved?: (result: ListItemBulkResult, actionId: string) => void;
}

export function createListItemActions<T>(options: () => ListItemActionOptions<T>, changed: () => void) {
  let active = false;
  let lifetime = 0;
  let busy = false;
  const find = (id: string) => options().items.find((item) => options().itemKey(item) === id);
  const actionFor = (id: string) => options().definitions.find((action) => action.id === id);
  const disabled = () => busy || options().blocked() || !options().enabled;
  const selectionDefinitions = (items: readonly T[]) => items.length
    ? options().definitions.filter((action) => action.bulk && items.every(action.supports)) : [];
  const unavailableReason = (ids: readonly string[], actionId: string) => {
    if (!ids.length) return 'Select links first.';
    if (ids.length > MAX_LIST_BULK_ITEMS) return `Select no more than ${MAX_LIST_BULK_ITEMS} links per action.`;
    if (!active || !options().enabled || !options().isCurrent()) return 'Wait for the current list to load successfully.';
    if (busy || options().blocked()) return 'Another link action is in progress.';
    const action = actionFor(actionId);
    if (new Set(ids).size !== ids.length || !action?.bulk || ids.some((id) => {
      const item = find(id);
      return !item || !action.supports(item);
    })) return 'This action is not available for all selected links.';
    return '';
  };
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
  const runSelected = async (selected: readonly string[], actionId: string) => {
    const ids = [...selected];
    if (unavailableReason(ids, actionId)) return;
    const initial = options(), action = actionFor(actionId)!;
    const identities = ids.map((id) => initial.itemIdentity(find(id)!));
    const context = initial.contextKey, started = lifetime;
    const current = () => active && lifetime === started && options().contextKey === context
      && initial.isCurrent() && options().isCurrent();
    const selectionMatches = () => {
      const latest = options().selectedIds?.();
      return latest?.length === ids.length && ids.every((id) => latest.includes(id));
    };
    if (!selectionMatches()) return;
    let submitted = false;
    busy = true;
    changed();
    try {
      if (!await window.EnderVault?.askConfirmation(action.bulk!.confirmation(ids.length))) return;
      const latest = options(), latestAction = actionFor(actionId);
      if (!current() || !latest.enabled || latest.blocked()) return;
      if (!selectionMatches() || !latestAction?.bulk || !ids.every((id, index) => {
        const item = find(id);
        return item && latestAction.supports(item) && latest.itemIdentity(item) === identities[index];
      })) {
        throw new Error('Selected links changed. Review the selection before continuing.');
      }
      submitted = true;
      const result = await latestAction.bulk.execute(ids);
      if (current()) options().bulkResolved?.(result, actionId);
    } catch (reason) {
      toastError(reason, 'The selected link action failed. Refresh the list before retrying.');
    } finally {
      // A lost response may still follow successful server work. Read once; never replay the mutation.
      if (submitted && current()) options().reload();
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
    selectionDefinitions,
    unavailableReason,
    run,
    runSelected,
  };
}

export type ListItemActionController<T> = ReturnType<typeof createListItemActions<T>>;

export function listItemMenuActions<T>(controller: ListItemActionController<T>, itemKey: (item: T) => string): BrowserMenuAction<T>[] {
  return controller.definitions().map<BrowserMenuAction<T>>((action) => ({
    id: action.id, group: action.group, label: action.label, icon: action.icon, danger: action.danger,
    visible: ({ mode, item, items }) => mode === 'single' ? item !== null && action.supports(item)
      : mode === 'selection' && Boolean(action.bulk) && items.every(action.supports),
    disabled: ({ mode, items }) => mode === 'selection'
      ? Boolean(controller.unavailableReason(items.map(itemKey), action.id)) : controller.disabled(),
    title: ({ mode, items }) => mode === 'selection' ? controller.unavailableReason(items.map(itemKey), action.id) : '',
    run: ({ mode, item, items }) => mode === 'single' && item ? controller.run(itemKey(item), action.id)
      : mode === 'selection' ? controller.runSelected(items.map(itemKey), action.id) : undefined,
  })).concat([{
    id: 'no-common-link-action', group: 'mutate', label: 'No common action for these links', icon: 'fas fa-circle-info',
    visible: ({ mode, items }) => mode === 'selection' && controller.definitions().some(action => Boolean(action.bulk))
      && !controller.selectionDefinitions(items).length,
    disabled: () => true,
    title: () => 'Select links with the same available actions. No subset will be processed.',
    run: () => undefined,
  }]);
}

export function validateListBulkResult(value: unknown, ids: readonly string[]): ListItemBulkResult {
  const body = value as ListItemBulkResult | null;
  const statuses = new Set(['APPLIED', 'NOT_FOUND', 'REJECTED', 'FAILED']);
  if (!body || body.ok !== true || !Array.isArray(body.results) || body.results.length !== ids.length
    || !body.results.every((item, index) => item && item.id === ids[index] && statuses.has(item.status)
      && typeof item.message === 'string')
    || body.succeededCount !== body.results.filter((item) => item.status === 'APPLIED').length
    || body.failedCount !== body.results.length - body.succeededCount) {
    throw new Error('The action result could not be verified. Refresh the list before retrying.');
  }
  return body;
}

export async function copyListLink(value: string, message: string) {
  const client = window.EnderVault;
  if (!client) throw new Error('Clipboard services are unavailable.');
  if (await client.copyText(value)) client.showToast('success', message);
}

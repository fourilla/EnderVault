import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createMemoryRouter } from 'react-router-dom';
import { tableColumnsHarness } from './table-columns-harness.mjs';

export const flush = () => new Promise(setImmediate);
export async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../../src/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const modules = {};
for (const name of ['useItemSelection', 'useSelectionShortcuts', 'list-item-actions', 'ListItemActions',
  'ListItemSelectionActions', 'useSelectableActionList', 'browser-menu-context']) {
  modules[name] = await compile(`shared/browser/${name}.${name.startsWith('ListItem') ? 'tsx' : 'ts'}`);
}
export const domains = [
  { scope: 'trash', route: '/admin/trash', name: 'TrashApp', page: 'trash/TrashApp.tsx',
    actions: 'trash/trash-list-actions.ts', load: 'loadTrash', bulk: 'deleteSelectedTrashItems', deleteLabel: 'Permanently delete',
    item: id => ({ id, originalName: `${id}.pdf`, originalPath: `photos/${id}.pdf`, directory: false }),
    payload: items => ({ items }) },
  { scope: 'sticky', route: '/admin/sticky-notes', name: 'StickyNoteListApp', page: 'sticky-notes/StickyNoteListApp.tsx',
    actions: 'sticky-notes/sticky-note-list-actions.ts', load: 'loadStickyNoteCatalog', bulk: 'deleteSelectedStickyNotes', deleteLabel: 'Delete sticky note',
    item: id => ({ id, content: id, summary: id, contextLabel: 'Dashboard', targetType: 'PAGE', openUrl: '/admin/dashboard',
      targetExists: true, surfaceLabel: 'Page', updatedLabel: '2026-10-05' }),
    payload: notes => ({ notes }) },
  { scope: 'favorites', route: '/files/favorites', name: 'FavoritesApp', page: 'favorites/FavoritesApp.tsx',
    actions: 'favorites/favorite-list-actions.ts', load: 'loadFavorites', bulk: 'removeSelectedFavorites', deleteLabel: 'Remove from favorites',
    item: path => ({ path, name: path, targetLabel: path, iconClass: 'fas fa-file', typeLabel: 'File', createdLabel: '2026-10-05',
      openUrl: `/files?path=${path}`, detailUrl: `/files/detail?path=${path}`, openInNewTab: false, hidden: false }),
    payload: items => ({ items }) },
];
for (const domain of domains) {
  domain.code = await compile(domain.page);
  domain.actionsCode = await compile(domain.actions);
}
export function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  if (['ListItemActions', 'ListItemSelectionActions'].includes(tree.type?.name)) return nodes(tree.type(tree.props));
  return [tree, ...nodes(tree.props?.children)];
}
export const rows = tree => nodes(tree).filter(node => node.type === 'tr' && node.props['data-context-item'] === 'true');
export const checkbox = row => nodes(row).find(node => node.type === 'input' && node.props.type === 'checkbox');
export const selected = tree => rows(tree).filter(row => row.props.className?.includes('is-selected')).map(row => row.key);
export const button = (tree, id, label) => nodes(rows(tree).find(row => row.key === id))
  .find(node => node.type === 'button' && node.props['aria-label'] === label);

// Real selection, shortcut, action controller and composition hooks with deterministic Router/effects.
export function listHarness(t, domain, search = '') {
  const tableColumns = tableColumnsHarness();
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: [domain.route + search] });
  const slots = [], effects = [], requests = [], mutations = [], confirmations = [], errors = [], opened = [], listeners = new Map();
  const mutationOutcomes = new Map();
  let cursor = 0, dirty, registration, menuOptions, confirm = async () => true, bulkOutcome;
  const react = {
    useState(initial) {
      const index = cursor++;
      slots[index] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, next => {
        const value = typeof next === 'function' ? next(slots[index].value) : next;
        if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
      }];
    },
    useRef(initial) { return slots[cursor++] ??= { current: initial }; },
    useMemo: factory => factory(),
    useEffect(run, deps) {
      const index = cursor++, previous = slots[index];
      if (previous && deps.every((dep, i) => Object.is(dep, previous.deps[i]))) return;
      const slot = { deps, cleanup: previous?.cleanup };
      slots[index] = slot; effects.push(() => { slot.cleanup?.(); slot.cleanup = run(); });
    },
  };
  const body = { closest: () => null }, scope = { contains: element => element.inside !== false };
  const document = { body, documentElement: { closest: () => null }, activeElement: body,
    visibilityState: 'visible', fullscreenElement: null, querySelector: () => null,
    addEventListener(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); },
    dispatchEvent(event) { listeners.get(event.type)?.forEach(fn => fn(event)); },
  };
  const window = { clearTimeout() {}, setTimeout: () => assert.fail('No long press'), location: { assign: url => opened.push(url) },
    open: (...args) => opened.push(args), EnderVault: {
      askConfirmation: config => { confirmations.push(config); return confirm(config); },
      navigate: url => { opened.push(url); return true; },
    } };
  const api = new Proxy({}, { get(_target, name) {
    if (name === domain.load) return (...args) => new Promise((resolve, reject) => {
      const [signal, query] = domain.scope === 'sticky' ? [args[1], args[0]] : [args[0], args[1] ?? ''];
      requests.push({ signal, query, resolve, reject });
    });
    return async (...args) => {
      mutations.push({ name, args });
      if (mutationOutcomes.has(name)) return mutationOutcomes.get(name);
      if (name === domain.bulk) {
        if (bulkOutcome instanceof Error) throw bulkOutcome;
        return bulkOutcome ?? { ok: true, succeededCount: args[0].length, failedCount: 0,
          results: Array.from(args[0], id => ({ id, status: 'APPLIED', message: 'Done' })) };
      }
    };
  } });
  const routeHooks = { useLocation: () => router.state.location,
    useSearchParams: () => [new URLSearchParams(router.state.location.search), next => router.navigate({
      pathname: domain.route, search: new URLSearchParams(next).toString() })] };
  const evaluate = (code, require) => {
    const module = { exports: {} };
    vm.runInNewContext(code, { module, exports: module.exports, document, window, Error, AbortController, URLSearchParams, require });
    return module.exports;
  };
  const formApi = { toastError: (reason, fallback) => errors.push(reason?.message ?? fallback) };
  const controller = evaluate(modules['list-item-actions'], () => formApi);
  const actionView = evaluate(modules.ListItemActions, id => id === 'react' ? react : id === 'react/jsx-runtime' ? jsx
    : id.endsWith('/list-item-actions') ? controller : id.endsWith('/AppNavigationLink') ? { AppNavigationLink() {} } : { icon: () => null });
  const definitions = evaluate(domain.actionsCode, id => id.endsWith('/list-item-actions') ? controller : api);
  const selectionView = evaluate(modules.ListItemSelectionActions, id => id === 'react/jsx-runtime' ? jsx : { FloatingPageActions() {} });
  const selection = evaluate(modules.useItemSelection, () => react);
  const shortcuts = evaluate(modules.useSelectionShortcuts, id => id === 'react' ? react : formApi);
  const common = id => {
    if (id === 'react') return react;
    if (id === 'react/jsx-runtime') return jsx;
    if (id === 'react-router-dom') return routeHooks;
    if (id.endsWith('/useItemSelection')) return selection;
    if (id.endsWith('/useTableColumns')) return tableColumns;
    if (id.endsWith('/useSelectionShortcuts')) return shortcuts;
    if (id.endsWith('/ListItemActions')) return actionView;
    if (id.endsWith('/ListItemSelectionActions')) return selectionView;
    if (id.endsWith('/list-item-actions')) return controller;
    if (id.endsWith('/useBrowserContextMenu')) return { useBrowserContextMenu: options => { menuOptions = options; } };
    if (id.endsWith('/ListingHistoryContext')) return { useLocationGuard: () => {
      const visit = router.state.location; return () => router.state.location === visit;
    } };
    return null;
  };
  const composition = evaluate(modules.useSelectableActionList, common);
  const { browserMenuContext } = evaluate(modules['browser-menu-context'], () => ({}));
  const page = evaluate(domain.code, id => {
    const dependency = common(id); if (dependency) return dependency;
    if (id.endsWith('/useSelectableActionList')) return composition;
    if (id.endsWith('-list-actions')) return definitions;
    if (id.endsWith('/RouteSearch')) return { useRouteSearch: control => { registration = control; } };
    if (id.endsWith('/BrowserEntries')) return { icon: () => null };
    if (id.endsWith('/form-api')) return formApi;
    if (id.endsWith('-api')) return api;
    const name = id.split('/').at(-1); return { [name]: { [name]: () => null }[name] };
  });
  t.after(() => { slots.forEach(slot => slot.cleanup?.()); router.dispose(); });
  return { router, requests, mutations, confirmations, errors, opened, document,
    setTableActions: tableColumns.setShown,
    get search() { return registration; }, get menu() { return menuOptions; },
    setConfirmation(fn) { confirm = fn; }, setBulkOutcome(result) { bulkOutcome = result; },
    setMutationOutcome(name, result) { mutationOutcomes.set(name, result); },
    key(event) { document.dispatchEvent({ ...event, type: 'keydown' }); },
    event(type, detail) { document.dispatchEvent({ type, detail }); },
    context(id) {
      const entries = menuOptions.entries(), elements = entries.map(item => ({ getAttribute: () => menuOptions.itemKey(item) }));
      const row = elements.find(element => element.getAttribute() === id);
      return browserMenuContext({ event: { target: { closest: selector => selector === '[data-context-item="true"]' ? row : null } },
        workspace: { contains: () => true, querySelectorAll: () => elements }, entries, itemKey: menuOptions.itemKey,
        keyAttribute: menuOptions.keyAttribute, selected: menuOptions.selectedRef.current,
        clearSelection: () => menuOptions.setSelected(new Set()) });
    },
    render() {
      let tree, count = 0;
      do {
        assert.ok(++count < 20, 'render/effect loop'); dirty = false; cursor = 0; tree = page[domain.name]();
        nodes(tree).filter(node => node.props?.ref).forEach(node => { node.props.ref.current = scope; });
        effects.splice(0).forEach(run => run());
      } while (dirty);
      return tree;
    },
    async finish(index = requests.length - 1, ids = []) {
      requests[index].resolve(domain.payload(ids.map(domain.item))); await flush();
    },
  };
}

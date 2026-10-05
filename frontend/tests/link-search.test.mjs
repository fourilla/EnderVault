import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createMemoryRouter } from 'react-router-dom';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent',
    build: { write: false, minify: false,
      lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const domains = [
  { scope: 'shares', page: 'shares/SharedLinksApp.tsx', name: 'SharedLinksApp',
    api: 'shares/share-api.ts', load: 'loadShares' },
  { scope: 'file-requests', page: 'file-requests/FileRequestsApp.tsx', name: 'FileRequestsApp',
    api: 'file-requests/file-request-api.ts', load: 'loadFileRequests' },
];
for (const domain of domains) {
  domain.code = await compile(domain.page);
  domain.apiCode = await compile(domain.api);
  domain.actionsCode = await compile(domain.scope === 'shares' ? 'shares/share-list-actions.ts' : 'file-requests/file-request-list-actions.ts');
}
const selectionCode = await compile('shared/browser/useItemSelection.ts');
const shortcutsCode = await compile('shared/browser/useSelectionShortcuts.ts');
const controllerCode = await compile('shared/browser/list-item-actions.ts');
const actionViewCode = await compile('shared/browser/ListItemActions.tsx');
const selectionActionViewCode = await compile('shared/browser/ListItemSelectionActions.tsx');
const menuContextCode = await compile('shared/browser/browser-menu-context.ts');
const flush = () => new Promise(setImmediate);
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  if (['ListItemActions', 'ListItemSelectionActions'].includes(tree.type?.name)) return nodes(tree.type(tree.props));
  return [tree, ...nodes(tree.props?.children)];
}
const share = id => ({ token: id, path: id, url: `/s/${id}`, type: 'FILE', active: true, directDownloadUrl: null });
const request = id => ({ id, title: id, url: `/r/${id}`, active: false, destinationPath: 'photos', allowedExtensions: [],
  acceptedBytes: 0, maxTotalBytes: 1000, createdLabel: '2026-10-04', expiresLabel: 'Never' });
const payload = (domain, ids = []) => domain.scope === 'shares' ? ids.map(share) : {
  requests: ids.map(request), enabled: true, uploaderNamePolicies: [{ value: 'OPTIONAL', label: 'Optional' }],
  defaults: { title: 'Initial title', description: 'Initial description', destinationPath: 'photos',
    uploaderNamePolicy: 'OPTIONAL', expirationDays: 7, maxFileSizeGb: '1', maxTotalGb: '2', maxFiles: 10,
    allowedExtensions: '', duplicating: false },
};

// Deterministic page effects + actual Router history; no backend or browser layout is involved.
function harness(t, domain, search = '') {
  const route = `/admin/${domain.scope}`;
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: [route + search] });
  const slots = [], effects = [], requests = [], mutations = [], confirmations = [], errors = [], copies = [], listeners = new Map();
  let cursor = 0, dirty, registration, menuOptions;
  let confirm = async () => true;
  let bulkOutcome;
  const react = {
    useState(initial) {
      const index = cursor++;
      if (!slots[index]) slots[index] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, next => {
        const value = typeof next === 'function' ? next(slots[index].value) : next;
        if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
      }];
    },
    useRef(initial) { const index = cursor++; return slots[index] ??= { current: initial }; },
    useMemo: factory => factory(),
    useEffect(run, deps) {
      const index = cursor++, previous = slots[index];
      if (previous && deps.every((dep, i) => Object.is(dep, previous.deps[i]))) return;
      const slot = { deps, cleanup: previous?.cleanup };
      slots[index] = slot;
      effects.push(() => { slot.cleanup?.(); slot.cleanup = run(); });
    },
  };
  const body = { closest: () => null }, scope = { contains: element => element.inside !== false };
  const document = { body, documentElement: { closest: () => null }, activeElement: body,
    visibilityState: 'visible', fullscreenElement: null, querySelector: () => null,
    addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name) };
  const window = { clearTimeout() {}, setTimeout: () => assert.fail('No long press started'), EnderVault: {
    askConfirmation: options => { confirmations.push(options); return confirm(options); },
    copyText: async value => { copies.push(value); return true; }, showToast: (...args) => {},
  } };
  const api = new Proxy({}, { get(_target, name) {
    if (name === domain.load) return (...args) => new Promise((resolve, reject) => {
      const [signal, query] = domain.scope === 'shares' ? args : [args[1], new URLSearchParams(args[0]).get('q') ?? ''];
      requests.push({ signal, query, args, resolve, reject });
    });
    return async (...args) => {
      mutations.push({ name, args });
      if (name === 'resolveFileRequestSelection' || name === 'resolveShareSelection') {
        if (bulkOutcome instanceof Error) throw bulkOutcome;
        return bulkOutcome ?? { ok: true, succeededCount: args[0].length, failedCount: 0,
          results: Array.from(args[0], id => ({ id, status: 'APPLIED', message: 'Done' })) };
      }
    };
  } });
  const routeHooks = { Link() {}, useLocation: () => router.state.location,
    useNavigate: () => router.navigate,
    useSearchParams: () => [new URLSearchParams(router.state.location.search), next => router.navigate({
      pathname: route, search: new URLSearchParams(next).toString() })] };
  const evaluate = (code, require) => {
    const module = { exports: {} };
    vm.runInNewContext(code, { module, exports: module.exports, document, window, Error, require });
    return module.exports;
  };
  const formApi = { toastError: (reason, fallback) => errors.push(reason?.message ?? fallback) };
  const controller = evaluate(controllerCode, () => formApi);
  const actionView = evaluate(actionViewCode, id => id === 'react' ? react : id === 'react/jsx-runtime' ? jsx
    : id === 'react-router-dom' ? routeHooks : id.endsWith('/list-item-actions') || id === './list-item-actions' ? controller
    : { icon: () => null });
  const actionDefinitions = evaluate(domain.actionsCode, id => id.endsWith('/list-item-actions') ? controller : api);
  const selectionActionView = evaluate(selectionActionViewCode, id => id === 'react/jsx-runtime' ? jsx
    : { FloatingPageActions() {} });
  const selection = evaluate(selectionCode, () => react);
  const shortcuts = evaluate(shortcutsCode, id => id === 'react' ? react : formApi);
  const { browserMenuContext } = evaluate(menuContextCode, () => ({}));
  const module = { exports: {} };
  vm.runInNewContext(domain.code, { module, exports: module.exports, AbortController, URLSearchParams, Error,
    document, window,
    require(id) {
      if (id === 'react') return react;
      if (id === 'react/jsx-runtime') return jsx;
      if (id === 'react-router-dom') return routeHooks;
      if (id.endsWith('/RouteSearch')) return { useRouteSearch: control => { registration = control; } };
      if (id.endsWith('/BrowserEntries')) return { icon: () => null };
      if (id.endsWith('/form-api')) return formApi;
      if (id.endsWith('/useItemSelection')) return selection;
      if (id.endsWith('/useSelectionShortcuts')) return shortcuts;
      if (id.endsWith('/useBrowserContextMenu')) return { useBrowserContextMenu: options => { menuOptions = options; } };
      if (id.endsWith('/ListingHistoryContext')) return { useLocationGuard: () => {
        const visit = router.state.location;
        return () => router.state.location === visit;
      } };
      if (id.endsWith('/ListItemActions')) return actionView;
      if (id.endsWith('/ListItemSelectionActions')) return selectionActionView;
      if (id.endsWith('/list-item-actions')) return controller;
      if (id.endsWith('-list-actions')) return actionDefinitions;
      if (id.endsWith('/format-bytes')) return { formatBytes: String };
      if (id.endsWith('-api')) return api;
      const name = id.split('/').at(-1);
      return { [name]: { [name]: () => null }[name] };
    },
  });
  t.after(() => { slots.forEach(slot => slot.cleanup?.()); router.dispose(); });
  return { router, requests, mutations, confirmations, errors, copies, document,
    get menu() { return menuOptions; },
    setConfirmation(fn) { confirm = fn; },
    setBulkOutcome(result) { bulkOutcome = result; },
    key(event) { listeners.get('keydown')?.(event); },
    context(id, native = false) {
      const entries = menuOptions.entries();
      const elements = entries.map(item => ({ getAttribute: () => menuOptions.itemKey(item) }));
      const row = elements.find(element => element.getAttribute() === id);
      const target = { closest(selector) {
        if (native && selector.startsWith('input,')) return this;
        return selector === '[data-context-item="true"]' ? row : null;
      } };
      return browserMenuContext({ event: { target }, workspace: { contains: () => true, querySelectorAll: () => elements },
        entries, itemKey: menuOptions.itemKey, keyAttribute: menuOptions.keyAttribute, selected: menuOptions.selectedRef.current,
        clearSelection: () => menuOptions.setSelected(new Set()) });
    },
    get search() { return registration; },
    render() {
      let tree, count = 0;
      do {
        assert.ok(++count < 20, 'render/effect loop');
        dirty = false; cursor = 0; tree = module.exports[domain.name]();
        nodes(tree).filter(node => node.props?.ref).forEach(node => { node.props.ref.current = scope; });
        effects.splice(0).forEach(run => run());
      } while (dirty);
      return tree;
    },
    async finish(index = requests.length - 1, ids = []) {
      requests[index].resolve(payload(domain, ids)); await flush();
    },
  };
}

const listRows = tree => nodes(tree).filter(node => node.type === 'tr' && node.props['data-context-item'] === 'true');
const selectedKeys = tree => listRows(tree).filter(node => node.props.className === 'is-selected').map(node => node.key);
const rowCheckbox = row => nodes(row).find(node => node.type === 'input' && node.props.type === 'checkbox');
const click = (row, modifiers = {}) => {
  const event = { target: { closest: () => null }, preventDefault() {}, stopPropagation() {}, ...modifiers };
  row.props.onClickCapture(event);
};
const actionButton = (tree, id, label) => nodes(listRows(tree).find(row => row.key === id))
  .find(node => node.type === 'button' && node.props['aria-label'] === label);

for (const domain of domains) {
  test(`${domain.scope} uses shared selection, Shift range, header and opt-in shortcuts`, async t => {
    const h = harness(t, domain);
    h.render(); await h.finish(0, ['one', 'two', 'three']);
    let tree = h.render(), rows = listRows(tree);
    assert.equal(nodes(tree).find(node => node.type?.name === 'StableTable').props.columns[0], 'select');
    click(rows[0], { ctrlKey: true });
    tree = h.render(); rows = listRows(tree);
    click(rows[2], { shiftKey: true });
    assert.deepEqual(selectedKeys(h.render()), ['one', 'two', 'three']);
    const key = (value, ctrlKey = false) => {
      let prevented = false;
      h.key({ key: value, ctrlKey, target: h.document.body, preventDefault() { prevented = true; } });
      return prevented;
    };
    if (domain.scope === 'file-requests') assert.equal(key('Delete'), false);
    assert.equal(h.mutations.length, 0);
    assert.equal(key('Escape'), true);
    assert.deepEqual(selectedKeys(h.render()), []);
    assert.equal(key('a', true), true);
    tree = h.render();
    assert.deepEqual(selectedKeys(tree), ['one', 'two', 'three']);
    const header = nodes(tree).find(node => node.type?.name === 'SelectionHeader');
    assert.equal(header.props.total, 3); assert.equal(header.props.selected, 3);
    header.props.onChange(false);
    assert.deepEqual(selectedKeys(h.render()), []);
    rowCheckbox(listRows(h.render())[1]).props.onChange({ currentTarget: { checked: true } });
    assert.deepEqual(selectedKeys(h.render()), ['two']);
  });

  test(`${domain.scope} same-query refresh preserves surviving IDs, new rows are not selected and query changes clear selection`, async t => {
    const h = harness(t, domain, '?q=status:active');
    h.render(); await h.finish(0, ['one', 'two', 'three']);
    let tree = h.render();
    for (const row of listRows(tree).slice(0, 2)) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
    tree = h.render();
    h.search.onChange('name:draft');
    assert.deepEqual(selectedKeys(h.render()), ['one', 'two']);
    await actionButton(tree, 'three', 'Revoke')?.props.onClick();
    if (domain.scope === 'file-requests') {
      // Fixture requests are inactive; Delete uses the same shared execution/reload path.
      await actionButton(tree, 'three', 'Delete').props.onClick();
    }
    await flush(); h.render();
    await h.finish(1, ['one', 'new', 'three']);
    assert.deepEqual(selectedKeys(h.render()), ['one']);
    h.search.onSubmit({ preventDefault() {} }); await flush(); h.render();
    await h.finish(2, ['one', 'new']);
    assert.deepEqual(selectedKeys(h.render()), []);
  });

  test(`${domain.scope} row buttons and single menu share actions; selected and background contexts never run single actions`, async t => {
    const h = harness(t, domain);
    h.render(); await h.finish(0, ['one', 'two']);
    let tree = h.render(), context = h.context('one');
    const menu = h.menu.actions().filter(action => action.visible(context));
    const rowLabels = nodes(listRows(tree)[0]).filter(node => node.props?.['aria-label']
      && (node.type === 'button' || node.type?.name === 'Link')).map(node => node.props['aria-label']);
    assert.deepEqual(Array.from(menu, action => action.label), rowLabels);
    assert.equal(menu.some(action => action.label === 'Copy direct download link'), false);
    const label = domain.scope === 'shares' ? 'Copy link' : 'Copy request link';
    await actionButton(tree, 'one', label).props.onClick();
    await menu.find(action => action.label === label).run(context);
    const url = domain.scope === 'shares' ? '/s/one' : '/r/one';
    assert.deepEqual(h.copies, [url, url]);
    assert.equal(h.requests.length, 1, 'Copy does not reload');
    assert.equal(h.context('one', true), null, 'URL inputs keep their native context menu');
    for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
    h.render(); context = h.context('one');
    assert.equal(context.mode, 'selection');
    const selectedActions = h.menu.actions().filter(action => action.visible(context));
    assert.deepEqual(Array.from(selectedActions, action => action.label),
      domain.scope === 'shares' ? ['Revoke', 'Delete'] : ['Delete']);
    for (const action of h.menu.actions().filter(action => !action.visible(context))) await action.run(context);
    assert.equal(h.mutations.length, 0);
    assert.equal(h.menu.actions().filter(action => action.visible(h.context(null))).length, 0);
    context = h.context('unselected');
    assert.equal(context.mode, 'background');
  });

  test(`${domain.scope} read errors disable selection and menu execution while preserving the existing selection`, async t => {
    const h = harness(t, domain);
    h.render(); await h.finish(0, ['one']);
    let tree = h.render(); rowCheckbox(listRows(tree)[0]).props.onChange({ currentTarget: { checked: true } });
    tree = h.render();
    const command = nodes(tree).find(node => node.type === 'button' && (domain.scope === 'shares'
      ? node.props['aria-label'] === 'Delete expired links' : node.props.children === 'Delete expired'));
    await command.props.onClick();
    await flush(); h.render();
    h.requests[1].reject(new Error('Offline')); await flush(); tree = h.render();
    assert.deepEqual(selectedKeys(tree), ['one']);
    assert.equal(rowCheckbox(listRows(tree)[0]).props.disabled, true);
    assert.equal(nodes(tree).find(node => node.type?.name === 'SelectionHeader').props.disabled, true);
    assert.ok(h.menu.actions().every(action => action.disabled(h.context('one'))));
    let prevented = false;
    h.key({ key: 'a', ctrlKey: true, target: h.document.body, preventDefault() { prevented = true; } });
    assert.equal(prevented, false);
  });
}

test('request single confirmation is locked, revalidated and cannot submit after route navigation', async t => {
  const h = harness(t, domains[1]);
  h.render(); await h.finish(0, ['one']);
  let resolve;
  h.setConfirmation(() => new Promise(done => { resolve = done; }));
  let tree = h.render();
  const button = actionButton(tree, 'one', 'Delete');
  const submitted = button.props.onClick();
  await button.props.onClick();
  assert.equal(h.confirmations.length, 1);
  assert.equal(actionButton(h.render(), 'one', 'Delete').props.disabled, true);
  await h.router.navigate('/admin/file-requests?q=name:other');
  h.render();
  await h.router.navigate(-1); h.render(); await h.finish(2, ['one']); h.render();
  resolve(true); await submitted;
  assert.equal(h.mutations.length, 0);
  assert.equal(h.errors.length, 0);
});

test('request creation fields retain native Ctrl+A and do not enter the list shortcut scope', async t => {
  const h = harness(t, domains[1]);
  h.render(); await h.finish(0, ['one']); h.render();
  const input = { inside: false, closest: selector => selector.includes('input') ? input : null,
    matches: () => false };
  h.document.activeElement = input;
  let prevented = false;
  h.key({ key: 'a', ctrlKey: true, target: input, preventDefault() { prevented = true; } });
  assert.equal(prevented, false);
  assert.deepEqual(selectedKeys(h.render()), []);
});

test('request bulk delete preserves failed rows and selection, removes successful rows and keeps creation draft', async t => {
  const h = harness(t, domains[1], '?q=status:revoked&destinationPath=photos');
  h.render(); await h.finish(0, ['one', 'two', 'three']);
  let tree = h.render();
  nodes(tree).find(node => node.type === 'input' && node.props.maxLength === 120)
    .props.onChange({ target: { value: 'Draft title' } });
  for (const row of listRows(tree)) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  h.render();
  h.setBulkOutcome({ ok: true, succeededCount: 1, failedCount: 2, results: [
    { id: 'one', status: 'APPLIED', message: 'Deleted' },
    { id: 'two', status: 'REJECTED', message: 'Resolve pending files first.' },
    { id: 'three', status: 'FAILED', message: 'Refresh before retrying.' },
  ] });
  const context = h.context('one'), command = h.menu.actions().find(action => action.visible(context) && action.label === 'Delete');
  await command.run(context); await flush(); tree = h.render();
  assert.deepEqual(rowKeys(tree), ['two', 'three']);
  assert.deepEqual(selectedKeys(tree), ['two', 'three']);
  assert.equal(h.mutations.length, 1);
  assert.equal(h.mutations[0].name, 'resolveFileRequestSelection');
  assert.deepEqual(Array.from(h.mutations[0].args[0]), ['one', 'two', 'three']);
  assert.equal(h.mutations[0].args[1], 'DELETE');
  assert.equal(h.requests.length, 2, 'Exactly one canonical reload');
  assert.equal(h.requests[1].query, 'status:revoked');
  assert.ok(nodes(tree).some(node => node.props?.title === 'Resolve pending files first.'));
  assert.equal(nodes(tree).find(node => node.type === 'input' && node.props.maxLength === 120).props.value, 'Draft title');
  await h.finish(1, ['two', 'three']);
  assert.deepEqual(selectedKeys(h.render()), ['two', 'three']);
});

test('request bulk revoke clears successful selection without removing records and reloads applied query', async t => {
  const h = harness(t, domains[1], '?q=status:active');
  h.render();
  const data = payload(domains[1], ['one', 'two']);
  data.requests.forEach(item => { item.active = true; });
  h.requests[0].resolve(data); await flush();
  let tree = h.render();
  for (const row of listRows(tree)) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  tree = h.render();
  const floating = nodes(tree).find(node => node.type?.name === 'FloatingPageActions');
  assert.equal(floating.props.selectedCount, 2);
  const button = nodes(floating).find(node => node.type === 'button' && node.props['aria-label'] === 'Revoke');
  assert.equal(button.props.disabled, false);
  await button.props.onClick(); await flush(); tree = h.render();
  assert.deepEqual(rowKeys(tree), ['one', 'two']);
  assert.deepEqual(selectedKeys(tree), []);
  assert.equal(h.mutations[0].name, 'resolveFileRequestSelection');
  assert.equal(h.mutations[0].args[1], 'REVOKE');
  assert.equal(h.requests.length, 2);
  assert.equal(h.requests[1].query, 'status:active');
});

test('request mixed selection exposes no subset operation in context menu or floating actions', async t => {
  const h = harness(t, domains[1]);
  h.render(); const data = payload(domains[1], ['one', 'two']); data.requests[0].active = true;
  h.requests[0].resolve(data); await flush();
  for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  const tree = h.render(), context = h.context('one');
  const commands = h.menu.actions().filter(action => action.visible(context));
  assert.equal(commands.length, 1);
  assert.equal(commands[0].id, 'no-common-link-action');
  assert.equal(commands[0].disabled(context), true);
  assert.ok(nodes(tree).find(node => node.type === 'button' && node.props['aria-label'] === 'No common action for these links')?.props.disabled);
  for (const command of h.menu.actions()) await command.run(context);
  assert.equal(h.mutations.length, 0);
  assert.equal(h.confirmations.length, 0);
});

test('request lost bulk response preserves uncertain selection and performs one reload without replay', async t => {
  const h = harness(t, domains[1]);
  h.render(); await h.finish(0, ['one', 'two']);
  for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  h.render(); h.setBulkOutcome(new Error('Lost response'));
  const context = h.context('one');
  await h.menu.actions().find(action => action.visible(context) && action.label === 'Delete').run(context);
  await flush(); const tree = h.render();
  assert.deepEqual(rowKeys(tree), ['one', 'two']);
  assert.deepEqual(selectedKeys(tree), ['one', 'two']);
  assert.equal(h.requests.length, 2);
  assert.equal(h.mutations.length, 1);
  assert.deepEqual(h.errors, ['Lost response']);
});

test('request bulk confirmation cannot submit after an outgoing Router visit before React unmount', async t => {
  const h = harness(t, domains[1]);
  h.render(); await h.finish(0, ['one', 'two']);
  for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  h.render();
  let resolve; h.setConfirmation(() => new Promise(done => { resolve = done; }));
  const context = h.context('one');
  const running = h.menu.actions().find(action => action.visible(context) && action.label === 'Delete').run(context);
  await h.router.navigate('/admin/dashboard');
  resolve(true); await running;
  assert.equal(h.mutations.length, 0);
  assert.equal(h.requests.length, 1);
});
const errorPanels = tree => nodes(tree).filter(node => node.type?.name === 'PageErrorPanel');
const rowKeys = tree => nodes(tree).filter(node => node.type === 'tr' && node.key).map(node => node.key);

test('share bulk menu uses the full selection intersection, never exposes multi-copy and Delete ignores status', async t => {
  const h = harness(t, domains[0]);
  h.render(); const data = payload(domains[0], ['one', 'two', 'three']);
  data[1].active = false; data[2].active = false;
  h.requests[0].resolve(data); await flush();
  for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  const tree = h.render(), context = h.context('one');
  assert.deepEqual(Array.from(h.menu.actions().filter(action => action.visible(context)), action => action.label), ['Delete']);
  const floating = nodes(tree).find(node => node.type?.name === 'FloatingPageActions');
  assert.equal(floating.props.mode, 'menu');
  assert.equal(floating.props.selectedCount, 3);
  const buttons = nodes(floating).filter(node => node.type === 'button');
  assert.deepEqual(buttons.map(node => node.props['aria-label']), ['Delete', 'Delete expired links']);
  await buttons[0].props.onClick(); await flush();
  assert.equal(h.mutations.length, 1);
  assert.equal(h.mutations[0].name, 'resolveShareSelection');
  assert.equal(h.mutations[0].args[1], 'DELETE');
  assert.deepEqual(Array.from(h.mutations[0].args[0]), ['one', 'two', 'three']);
  assert.equal(h.confirmations.length, 1);
  assert.deepEqual(rowKeys(h.render()), []);
});

test('share partial bulk delete keeps failed rows, failure text and selection while refreshing the current search once', async t => {
  const h = harness(t, domains[0], '?q=path:photos');
  h.render(); await h.finish(0, ['one', 'two']);
  for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  h.render();
  h.setBulkOutcome({ ok: true, succeededCount: 1, failedCount: 1, results: [
    { id: 'one', status: 'APPLIED', message: 'Deleted' },
    { id: 'two', status: 'FAILED', message: 'Refresh shared links before retrying.' },
  ] });
  const context = h.context('one');
  await h.menu.actions().find(action => action.visible(context) && action.label === 'Delete').run(context);
  await flush(); const tree = h.render();
  assert.deepEqual(rowKeys(tree), ['two']);
  assert.deepEqual(selectedKeys(tree), ['two']);
  assert.ok(nodes(tree).some(node => node.props?.text === 'Refresh shared links before retrying.'));
  assert.equal(h.requests.length, 2);
  assert.equal(h.requests[1].query, 'path:photos');
  assert.equal(h.mutations.length, 1);
});

test('share bulk revoke keeps records pending canonical reload and only clears successful selected IDs', async t => {
  const h = harness(t, domains[0], '?q=status:active');
  h.render(); await h.finish(0, ['one', 'two']);
  for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  h.render(); const context = h.context('one');
  await h.menu.actions().find(action => action.visible(context) && action.label === 'Revoke').run(context);
  await flush();
  assert.deepEqual(rowKeys(h.render()), ['one', 'two']);
  assert.deepEqual(selectedKeys(h.render()), []);
  assert.equal(h.mutations[0].args[1], 'REVOKE');
  assert.equal(h.requests.length, 2);
  await h.finish(1, []);
  assert.deepEqual(rowKeys(h.render()), []);
});

test('share whole-list expiry cleanup stays available for an empty search and never submits selected tokens', async t => {
  const h = harness(t, domains[0], '?q=path:nomatch');
  h.render(); await h.finish(0, []);
  const tree = h.render(), floating = nodes(tree).find(node => node.type?.name === 'FloatingPageActions');
  assert.equal(floating.props.mode, 'menu');
  const button = nodes(floating).find(node => node.type === 'button' && node.props['aria-label'] === 'Delete expired links');
  assert.equal(button.props.disabled, false);
  await button.props.onClick(); await flush(); h.render();
  assert.deepEqual(h.mutations, [{ name: 'deleteExpiredShares', args: [] }]);
  assert.equal(h.requests.length, 2);
  assert.equal(h.requests[1].query, 'path:nomatch');
});

test('share Delete shortcut opts into the same confirmed bulk path with repeat and input protection', async t => {
  const h = harness(t, domains[0]);
  h.render(); await h.finish(0, ['one', 'two']);
  for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  h.render();
  let resolve;
  h.setConfirmation(() => new Promise(done => { resolve = done; }));
  const key = (repeat = false) => {
    let prevented = false;
    h.key({ key: 'Delete', repeat, target: h.document.body, preventDefault() { prevented = true; } });
    return prevented;
  };
  const input = { inside: true, closest: selector => selector.includes('input') ? input : null, matches: () => false };
  h.document.activeElement = input;
  assert.equal(key(), false);
  h.document.activeElement = h.document.body;
  assert.equal(key(true), false);
  assert.equal(key(), true);
  assert.equal(key(), false);
  assert.equal(h.confirmations.length, 1);
  assert.equal(h.mutations.length, 0);
  resolve(true); await flush(); h.render();
  assert.equal(h.mutations.length, 1);
  assert.equal(h.mutations[0].name, 'resolveShareSelection');
  assert.equal(h.mutations[0].args[1], 'DELETE');
  assert.deepEqual(selectedKeys(h.render()), []);
});

test('share bulk confirmation is canceled on selection or Router changes without sending a request', async t => {
  for (const change of ['selection', 'route', 'cancel']) {
    const h = harness(t, domains[0]);
    h.render(); await h.finish(0, ['one', 'two']);
    for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
    h.render(); let resolve; h.setConfirmation(() => new Promise(done => { resolve = done; }));
    const context = h.context('one');
    const running = h.menu.actions().find(action => action.visible(context) && action.label === 'Delete').run(context);
    if (change === 'selection') { rowCheckbox(listRows(h.render())[1]).props.onChange({ currentTarget: { checked: false } }); h.render(); }
    if (change === 'route') await h.router.navigate('/admin/dashboard');
    resolve(change !== 'cancel'); await running;
    assert.equal(h.mutations.length, 0);
    assert.equal(h.requests.length, 1);
  }
});

test('share lost bulk response keeps uncertain selection and reloads without mutation replay', async t => {
  const h = harness(t, domains[0]);
  h.render(); await h.finish(0, ['one', 'two']);
  for (const row of listRows(h.render())) rowCheckbox(row).props.onChange({ currentTarget: { checked: true } });
  h.render(); h.setBulkOutcome(new Error('Lost response'));
  const context = h.context('one');
  await h.menu.actions().find(action => action.visible(context) && action.label === 'Delete').run(context);
  await flush();
  assert.deepEqual(selectedKeys(h.render()), ['one', 'two']);
  assert.equal(h.requests.length, 2);
  assert.equal(h.mutations.length, 1);
  assert.deepEqual(h.errors, ['Lost response']);
});

for (const domain of domains) {
  test(`${domain.scope} submits only on command, preserves other URL fields and restores history`, async t => {
    const h = harness(t, domain, '?q=name:old&destinationPath=photos&copyFrom=source');
    h.render(); await h.finish(0, ['old']); h.render();
    assert.equal(h.search.schemaScope, domain.scope);
    h.search.onChange('name:"summer holiday"'); h.render();
    assert.equal(h.requests.length, 1);
    h.search.onSubmit({ preventDefault() {} }); await flush();
    assert.deepEqual(rowKeys(h.render()), []);
    assert.equal(h.requests[1].query, 'name:"summer holiday"');
    assert.equal(new URLSearchParams(h.router.state.location.search).get('destinationPath'), 'photos');
    assert.equal(new URLSearchParams(h.router.state.location.search).get('copyFrom'), 'source');
    await h.finish(1, ['new']); h.render();
    await h.router.navigate(-1); h.render();
    assert.equal(h.search.value, 'name:old');
    await h.router.navigate(1); h.render();
    assert.equal(h.search.value, 'name:"summer holiday"');
    h.search.onReset(); await flush(); h.render();
    const params = new URLSearchParams(h.router.state.location.search);
    assert.equal(params.has('q'), false);
    assert.equal(params.get('copyFrom'), 'source');
    assert.equal(h.requests.at(-1).query, '');
  });

  for (const outcome of ['resolve', 'reject']) {
    test(`${domain.scope} ignores late ${outcome} after navigation and keeps current query feedback`, async t => {
      const h = harness(t, domain, '?q=name:old');
      h.render();
      await h.router.navigate(`/admin/${domain.scope}?q=name:new`); h.render();
      assert.equal(h.requests[0].signal.aborted, true);
      await h.finish(1, ['new']);
      if (outcome === 'resolve') h.requests[0].resolve(payload(domain, ['old']));
      else h.requests[0].reject(new Error('Old query failure'));
      await flush();
      const tree = h.render();
      assert.deepEqual(rowKeys(tree), ['new']);
      assert.equal(errorPanels(tree).length, 0);
    });
  }

  test(`${domain.scope} draft reset does not fetch and query errors remain resettable`, async t => {
    const h = harness(t, domain);
    h.render(); await h.finish(); h.render();
    h.search.onChange('name:draft'); h.render();
    h.search.onChange(''); h.search.onReset(); h.render();
    assert.equal(h.requests.length, 1);
    await h.router.navigate(`/admin/${domain.scope}?q=typo:value`); h.render();
    h.requests[1].reject(new Error('Unknown search field: typo')); await flush();
    assert.equal(errorPanels(h.render())[0].props.message, 'Unknown search field: typo');
    h.search.onReset(); await flush(); h.render(); await h.finish(); h.render();
    assert.equal(h.search.appliedQuery, '');
    assert.equal(h.search.disabled, undefined);
  });

  test(`${domain.scope} mutations reload the applied search, not the unsubmitted input`, async t => {
    const h = harness(t, domain, '?q=status:active');
    h.render();
    const data = payload(domain, ['active']);
    const item = domain.scope === 'shares' ? data[0] : data.requests[0];
    item.active = true;
    h.requests[0].resolve(data); await flush();
    let tree = h.render();
    h.search.onChange('name:draft'); tree = h.render();
    await nodes(tree).find(node => node.type === 'button' && node.props['aria-label'] === 'Revoke').props.onClick();
    await flush(); h.render();
    assert.equal(h.requests.at(-1).query, 'status:active');
    assert.equal(h.search.value, 'name:draft');
    assert.equal(h.mutations.length, 1);
    await h.finish();
    assert.deepEqual(rowKeys(h.render()), []);
  });
}

test('request form edits survive query navigation, empty results, retry and reset; context changes reinitialize defaults', async t => {
  const domain = domains[1], h = harness(t, domain, '?destinationPath=photos&copyFrom=original');
  const fields = tree => nodes(tree).filter(node => node.type === 'input');
  h.render(); await h.finish(0, ['first']);
  let tree = h.render();
  fields(tree).find(node => node.props.maxLength === 120).props.onChange({ target: { value: 'Edited title' } });
  nodes(tree).find(node => node.type === 'textarea').props.onChange({ target: { value: 'Edited description' } });
  fields(tree).find(node => node.props.id === 'fileRequestDestination').props.onChange({ target: { value: 'draft destination' } });
  h.render();
  h.search.onChange('status:full'); h.render(); h.search.onSubmit({ preventDefault() {} });
  await flush(); h.render(); await h.finish(); tree = h.render();
  assert.equal(fields(tree).find(node => node.props.maxLength === 120).props.value, 'Edited title');
  assert.equal(nodes(tree).find(node => node.type === 'textarea').props.value, 'Edited description');
  assert.equal(fields(tree).find(node => node.props.id === 'fileRequestDestination').props.value, 'draft destination');
  // Empty filtered results must not remove the existing whole-catalog cleanup command.
  assert.ok(nodes(tree).find(node => node.type === 'button' && node.props.children === 'Delete expired'));
  await h.router.navigate('/admin/file-requests?destinationPath=photos&copyFrom=original&q=typo:value'); h.render();
  h.requests.at(-1).reject(new Error('Unknown search field: typo')); await flush();
  errorPanels(h.render())[0].props.actions.props.onClick(); h.render(); await h.finish(); tree = h.render();
  assert.equal(fields(tree).find(node => node.props.maxLength === 120).props.value, 'Edited title');
  h.search.onReset(); await flush(); h.render(); await h.finish(); tree = h.render();
  assert.equal(fields(tree).find(node => node.props.maxLength === 120).props.value, 'Edited title');
  await h.router.navigate('/admin/file-requests?destinationPath=other');
  assert.equal(fields(h.render()).length, 0, 'Do not expose old defaults for a different creation context');
  await h.finish(); tree = h.render();
  assert.equal(fields(tree).find(node => node.props.maxLength === 120).props.value, 'Initial title');
});

test('shared link transport forwards expressions and structured errors without fallback', async () => {
  const module = { exports: {} }, calls = [];
  vm.runInNewContext(domains[0].apiCode, { module, exports: module.exports, URLSearchParams, Error,
    require: () => ({}), fetch: async (url, options) => {
      calls.push({ url, options });
      return calls.length < 3 ? { ok: true, json: async () => [] }
        : { ok: false, json: async () => ({ notification: { message: 'Unknown search field: typo' } }) };
    },
  });
  const signal = new AbortController().signal, query = 'path:"summer holiday" created:>=2026-10-04T00:00:00+09:00';
  await module.exports.loadShares(signal, query);
  assert.equal(new URL(calls[0].url, 'https://nas.test').searchParams.get('q'), query);
  assert.equal(calls[0].options.signal, signal);
  await module.exports.loadShares(signal);
  assert.equal(calls[1].url, '/api/v1/shares');
  await assert.rejects(module.exports.loadShares(signal, 'typo:value'), { message: 'Unknown search field: typo' });
  assert.equal(calls.length, 3);
});

test('request transport uses the existing JSON client and preserves creation context alongside q', async () => {
  const module = { exports: {} }, signal = new AbortController().signal;
  const search = `?${new URLSearchParams({ destinationPath: 'photos', copyFrom: 'source', q: 'name:"summer holiday"' })}`;
  let calls = 0;
  vm.runInNewContext(domains[1].apiCode, { module, exports: module.exports, require: () => ({}),
    window: { EnderVault: { requestJson: async (url, options) => {
      calls++;
      assert.equal(url, `/api/v1/file-requests${search}`); assert.equal(options.signal, signal);
      return { requests: [] };
    } } },
  });
  await module.exports.loadFileRequests(search, signal);
  assert.equal(calls, 1);
});

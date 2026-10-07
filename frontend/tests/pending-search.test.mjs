import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createMemoryRouter } from 'react-router-dom';
import { tableColumnsHarness } from './helpers/table-columns-harness.mjs';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent',
    build: { write: false, minify: false,
      lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
}
const apiCode = await compile('pending-decisions/pending-decision-api.ts');
const pageCode = await compile('pending-decisions/PendingDecisionsApp.tsx');
const selectionCode = await compile('shared/browser/useItemSelection.ts');
const shortcutsCode = await compile('shared/browser/useSelectionShortcuts.ts');

function api(fetch) {
  const module = { exports: {} };
  vm.runInNewContext(apiCode, { module, exports: module.exports, URLSearchParams, fetch, Error,
    require: () => ({ notify() { assert.fail('A read must not notify or mutate'); } }),
  });
  return module.exports;
}

test('Pending query transport preserves quoted expressions and omits q for unfiltered dialog reads', async () => {
  const signal = new AbortController().signal;
  const calls = [];
  const payload = { decisions: [] };
  const client = api(async (url, options) => {
    calls.push(new URL(url, 'https://nas.test'));
    assert.equal(options.signal, signal);
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.headers.Accept, 'application/json');
    return { ok: true, json: async () => payload };
  });
  const query = '(name:"summer holiday" || submitter:"\uac00\ub098") created:>=2026-10-03T09:00:00+09:00';
  assert.equal(await client.loadPendingDecisions(signal, query), payload);
  assert.equal(calls[0].pathname, '/api/v1/pending-decisions');
  assert.equal(calls[0].searchParams.get('q'), query);
  await client.loadPendingDecisions(signal);
  assert.equal(calls[1].search, '');
  const dialog = readFileSync(new URL('../src/pending-decisions/PendingDecisionDialog.tsx', import.meta.url), 'utf8');
  assert.match(dialog, /loadPendingDecisions\(controller.signal\)/);
});

test('Pending client preserves query errors and cancellation instead of retrying or falling back', async () => {
  let calls = 0;
  const client = api(async () => {
    calls++;
    return { ok: false, status: 400, json: async () => ({
      notification: { message: 'Unknown search field: typo' }, position: 2,
    }) };
  });
  await assert.rejects(client.loadPendingDecisions(new AbortController().signal, '  typo:value'),
    error => error instanceof client.PendingDecisionLoadError && error.status === 400
      && error.message === 'Unknown search field: typo');
  assert.equal(calls, 1);
  const failure = new DOMException('Canceled', 'AbortError');
  const canceled = api(async () => { throw failure; });
  await assert.rejects(canceled.loadPendingDecisions(new AbortController().signal, 'name:test'), error => error === failure);
});

// Deterministic effects, Router history, requests and timers; not a browser layout test.
function harness(t, query = '') {
  const tableColumns = tableColumnsHarness();
  const router = createMemoryRouter([{ path: '*', element: null }], {
    initialEntries: [`/admin/pending-decisions${query ? `?${new URLSearchParams({ q: query })}` : ''}`],
  });
  const slots = [], effects = [], requests = [], timers = new Map(), listeners = new Map();
  let cursor = 0, dirty, registration, actionOptions, menuOptions, timerId = 0;
  const react = {
    useState(initial) {
      const index = cursor++;
      if (!slots[index]) slots[index] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, next => {
        const value = typeof next === 'function' ? next(slots[index].value) : next;
        if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
      }];
    },
    useRef(initial) {
      const index = cursor++;
      if (!slots[index]) slots[index] = { value: { current: initial } };
      return slots[index].value;
    },
    useMemo: (factory) => factory(),
    useEffect(run, deps) {
      const index = cursor++, previous = slots[index];
      if (previous && deps.length === previous.deps.length && deps.every((dep, i) => Object.is(dep, previous.deps[i]))) return;
      const slot = { deps, cleanup: previous?.cleanup };
      slots[index] = slot;
      effects.push(() => { slot.cleanup?.(); slot.cleanup = run(); });
    },
  };
  const eventTarget = {
    addEventListener: (name, fn) => listeners.set(name, fn),
    removeEventListener: (name) => listeners.delete(name),
  };
  const body = { closest: () => null }, root = { closest: () => null };
  const scope = { contains: element => element.inside !== false };
  const document = { ...eventTarget, body, documentElement: root, activeElement: body,
    visibilityState: 'visible', fullscreenElement: null,
    querySelector: selector => selector === '.app-main' ? scope : null };
  const window = { ...eventTarget, setTimeout: () => assert.fail('No long press started'), clearTimeout() {} };
  const hook = (code) => {
    const module = { exports: {} };
    vm.runInNewContext(code, { module, exports: module.exports, document, window,
      require: id => id === 'react' ? react : { toastError: () => assert.fail('No delete action registered') } });
    return module.exports;
  };
  const client = api(() => assert.fail('Page requests use the deferred fixture'));
  const modules = {
    react, 'react/jsx-runtime': jsx,
    'react-router-dom': { useSearchParams: () => [new URLSearchParams(router.state.location.search),
      next => router.navigate({ pathname: '/admin/pending-decisions', search: new URLSearchParams(next).toString() })] },
    '../app/RouteSearch': { useRouteSearch: control => { registration = control; } },
    '../shared/browser/useHashTarget': { useHashTarget() {} },
    '../shared/browser/BrowserEntries': { icon: () => null },
    '../shared/browser/StableTable': { StableTable() {} },
    '../shared/browser/useTableColumns': tableColumns,
    '../shared/browser/SelectionHeader': { SelectionHeader() {} },
    '../shared/browser/useItemSelection': hook(selectionCode),
    '../shared/browser/useSelectionShortcuts': hook(shortcutsCode),
    '../shared/browser/useBrowserContextMenu': { useBrowserContextMenu: options => { menuOptions = options; } },
    '../shared/browser/ListingHistoryContext': { useLocationGuard: () => {
      const location = router.state.location;
      return () => router.state.location === location;
    } },
    '../shared/layout/OverflowMarquee': { OverflowMarquee() {} },
    '../shared/layout/PageHeader': { PageHeader() {} },
    '../shared/layout/PageErrorPanel': { PageErrorPanel() {} },
    '../shared/layout/LoadingState': { LoadingState() {} },
    './PendingDecisionActions': { PendingDecisionActions() {} },
    './PendingDecisionSelectionActions': { PendingDecisionSelectionActions() {} },
    './pending-decision-menu-actions': {
      pendingDecisionMenuActions: () => [], keepPendingMenuContext: () => true,
    },
    './usePendingDecisionActions': { usePendingDecisionActions: options => { actionOptions = options; return {}; } },
    './pending-decision-api': {
      PendingDecisionLoadError: client.PendingDecisionLoadError,
      loadPendingDecisions: (signal, query) => new Promise((resolve, reject) => requests.push({ signal, query, resolve, reject })),
    },
  };
  const module = { exports: {} };
  vm.runInNewContext(pageCode, { module, exports: module.exports, AbortController, Error, URLSearchParams,
    document, window,
    setTimeout: (run, delay) => { assert.equal(delay, 5000); timers.set(++timerId, run); return timerId; },
    clearTimeout: id => timers.delete(id),
    require: id => { assert.ok(id in modules, `unexpected import ${id}`); return modules[id]; },
  });
  const dispose = () => slots.forEach(slot => slot.cleanup?.());
  t.after(() => { dispose(); router.dispose(); });
  return {
    router, requests, timers, listeners, client, dispose,
    setTableActions: tableColumns.setShown,
    get search() { return registration; },
    get menu() { return menuOptions; },
    resolve(id) { actionOptions.resolved(id); },
    resolveSelected(results) { actionOptions.bulkResolved({ results }); },
    press(key, extra = {}) {
      const event = { key, target: body, defaultPrevented: false, ctrlKey: false, metaKey: false,
        altKey: false, shiftKey: false, isComposing: false, repeat: false,
        preventDefault() { this.defaultPrevented = true; }, ...extra };
      listeners.get('keydown')?.(event);
      return event;
    },
    render() {
      let tree, count = 0;
      do {
        assert.ok(++count < 20, 'render/effect loop');
        dirty = false; cursor = 0; tree = module.exports.PendingDecisionsApp();
        effects.splice(0).forEach(run => run());
      } while (dirty);
      return tree;
    },
    async finish(index = requests.length - 1, decisions = []) {
      requests[index].resolve({ decisions });
      await flush();
    },
    async poll() {
      const [id, run] = timers.entries().next().value;
      timers.delete(id); run(); await flush();
    },
  };
}

const flush = () => new Promise(setImmediate);
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.props?.children)];
}
const row = id => ({ id, originalFilename: id, directory: false, destinationLabel: '/photos' });
const rowIds = tree => nodes(tree).filter(node => node.type === 'tr' && node.props.id).map(node => node.props.id);
const errors = tree => nodes(tree).filter(node => node.type?.name === 'PageErrorPanel');
const selectedIds = tree => nodes(tree).filter(node => node.type === 'tr' && node.props.className === 'is-selected')
  .map(node => node.props['data-decision-id']);
const header = tree => nodes(tree).find(node => node.type?.name === 'SelectionHeader');
const checkbox = (tree, id) => nodes(tree).find(node => node.type === 'tr' && node.props['data-decision-id'] === id)
  .props.children[0].props.children;

test('Pending drafts do not fetch until submit; URL history, reload and empty search restore the query', async t => {
  const h = harness(t, 'source:admin_upload');
  h.render(); await h.finish(); h.render();
  assert.equal(h.search.schemaScope, 'pending-decisions');
  assert.equal(h.search.suggestionHidden, 'show');
  h.search.onChange('name:"summer holiday"'); h.render();
  assert.equal(h.requests.length, 1);
  h.search.onSubmit({ preventDefault() {} }); await flush(); h.render();
  assert.equal(new URLSearchParams(h.router.state.location.search).get('q'), 'name:"summer holiday"');
  assert.equal(h.requests[1].query, 'name:"summer holiday"');
  await h.finish(); h.render();
  assert.equal(h.search.disabled, undefined, 'Empty results must remain searchable');
  await h.router.navigate(-1); h.render();
  assert.equal(h.search.value, 'source:admin_upload');
  await h.router.navigate(1); h.render();
  assert.equal(h.search.value, 'name:"summer holiday"');
  h.search.onChange(''); h.render(); h.search.onSubmit({ preventDefault() {} });
  await flush(); h.render();
  assert.equal(h.router.state.location.search, '');
  assert.equal(h.requests.at(-1).query, '');
});

for (const outcome of ['resolve', 'reject']) {
  test(`Pending ignores an old ${outcome} after query navigation`, async t => {
    const h = harness(t, 'name:old');
    h.render(); await h.finish(0, [row('old')]); h.render();
    await h.poll();
    await h.router.navigate('/admin/pending-decisions?q=name:new');
    assert.deepEqual(rowIds(h.render()), [], 'Old-query rows must disappear immediately');
    assert.equal(h.requests[1].signal.aborted, true);
    await h.finish(2, [row('new')]);
    if (outcome === 'resolve') h.requests[1].resolve({ decisions: [row('old')] });
    else h.requests[1].reject(new Error('Old query failure'));
    await flush();
    const tree = h.render();
    assert.deepEqual(rowIds(tree), ['decision-new']);
    assert.equal(errors(tree).length, 0);
    assert.equal(h.timers.size, 1, 'Only the current query schedules polling');
  });
}

test('Pending polling and task notifications keep the submitted query, not the edited draft', async t => {
  const h = harness(t, 'destination:photos');
  h.render(); await h.finish(0, [row('first')]); h.render();
  h.search.onChange('source:directory_copy'); h.render();
  await h.poll();
  assert.equal(h.requests[1].query, 'destination:photos');
  h.listeners.get('endervault:task-terminal')(); h.render();
  assert.equal(h.requests[1].signal.aborted, true);
  assert.equal(h.requests[2].query, 'destination:photos');
  await h.finish(2, [row('second')]);
  const tree = h.render();
  assert.deepEqual(rowIds(tree), ['decision-second']);
  h.resolve('second');
  assert.deepEqual(rowIds(h.render()), []);
});

test('invalid Pending expressions show the shared error panel without automatic query retries', async t => {
  const h = harness(t, 'typo:value');
  h.render();
  h.requests[0].reject(new h.client.PendingDecisionLoadError('Unknown search field: typo', 400));
  await flush();
  assert.equal(errors(h.render())[0].props.message, 'Unknown search field: typo');
  assert.equal(h.timers.size, 0);
  h.search.onReset(); await flush(); h.render();
  assert.equal(h.requests[1].query, '');
  await h.finish(1); h.render();
  assert.equal(h.timers.size, 1);
});

test('Pending reset clears applied q but preserves other URL parameters and draft-only reset does not refetch', async t => {
  const h = harness(t);
  h.render(); await h.finish(); h.render();
  h.search.onChange('draft'); h.render();
  h.search.onChange(''); h.search.onReset(); await flush(); h.render();
  assert.equal(h.search.value, '');
  assert.equal(h.search.appliedQuery, '');
  assert.equal(h.requests.length, 1);
  await h.router.navigate('/admin/pending-decisions?q=name:old&keep=unchanged');
  h.render(); await h.finish(); h.render();
  h.search.onChange(''); h.search.onReset(); await flush(); h.render();
  assert.equal(h.search.value, '');
  assert.equal(h.search.appliedQuery, '');
  assert.equal(new URLSearchParams(h.router.state.location.search).has('q'), false);
  assert.equal(new URLSearchParams(h.router.state.location.search).get('keep'), 'unchanged');
  assert.equal(h.requests.at(-1).query, '');
  await h.router.navigate(-1); h.render();
  assert.equal(h.search.value, 'name:old');
  assert.equal(h.search.appliedQuery, 'name:old');
});

test('transient Pending polling errors retain same-query rows, and unmount cancels reads and timers', async t => {
  const h = harness(t, 'type:file');
  h.render(); await h.finish(0, [row('file')]); h.render();
  await h.poll(); h.requests[1].reject(new Error('Network unavailable')); await flush();
  const tree = h.render();
  assert.deepEqual(rowIds(tree), ['decision-file']);
  assert.equal(errors(tree)[0].props.stale, true);
  assert.equal(h.timers.size, 1);
  await h.poll(); await h.finish(2, [row('file')]);
  assert.equal(errors(h.render()).length, 0, 'Recovered polling clears its error');
  await h.poll(); h.dispose();
  h.requests[3].resolve({ decisions: [row('late')] }); await flush();
  assert.equal(h.requests[3].signal.aborted, true);
  assert.equal(h.timers.size, 0);
  assert.equal(h.listeners.size, 0);
  assert.deepEqual(rowIds(h.render()), ['decision-file']);
});

test('Pending hides Actions without dropping selection, polling or its shared decision actions', async t => {
  const h = harness(t); h.render(); await h.finish(0, [row('one')]);
  h.render(); h.press('a', { ctrlKey: true }); h.render();
  h.setTableActions(false);
  let tree = h.render();
  const table = nodes(tree).find(node => node.type?.name === 'StableTable');
  assert.equal(table.props.columns.length, 7);
  assert.ok(!table.props.columns.includes('actions'));
  assert.ok(!nodes(tree).some(node => node.type === 'th' && node.props.children === 'Actions'));
  assert.ok(!nodes(tree).some(node => node.type?.name === 'PendingDecisionActions'));
  const item = nodes(tree).find(node => node.type === 'tr' && node.props['data-context-item']);
  assert.equal(nodes(item).filter(node => node.type === 'td').length, 7);
  assert.ok(item.props.className.includes('is-selected'));
  assert.equal(h.requests.length, 1);
  assert.equal(h.timers.size, 1);
  assert.ok(nodes(tree).some(node => node.type?.name === 'PendingDecisionSelectionActions'));
  await h.poll(); await h.finish(1, []); tree = h.render();
  assert.equal(nodes(tree).find(node => node.props?.className === 'empty').props.colSpan, 7);
});

test('Pending uses stable columns, shared select-all and marquee without adding a bulk toolbar', async t => {
  const h = harness(t);
  h.render(); await h.finish(0, [{ ...row('directory'), directory: true, mergeId: 'review' }]);
  const tree = h.render();
  const table = nodes(tree).find(node => node.type?.name === 'StableTable');
  assert.deepEqual([...table.props.columns], ['select', 'text', 'type', 'text', 'size', 'date', 'status', 'actions']);
  assert.equal(table.props.actionCount, 4);
  assert.equal(header(tree).props.total, 1);
  assert.equal(header(tree).props.selected, 0);
  assert.equal(header(tree).props.disabled, false);
  assert.equal(nodes(tree).filter(node => node.type?.name === 'OverflowMarquee').length, 4);
  const element = nodes(tree).find(node => node.type === 'tr' && node.props.id);
  assert.equal(element.props['data-context-item'], 'true');
  assert.equal(element.props['data-decision-id'], 'directory');
  assert.equal(nodes(tree).filter(node => node.type?.name === 'PendingDecisionActions').length, 1);
  assert.equal(nodes(tree).some(node => node.type?.name === 'FloatingPageActions'), false);
  assert.equal(nodes(tree).filter(node => node.type?.name === 'PendingDecisionSelectionActions').length, 1);
  assert.equal(h.menu.menuId, 'pendingContextMenu');
  assert.equal(h.menu.keyAttribute, 'data-decision-id');
  assert.equal(h.menu.entries()[0].id, 'directory');
  await h.poll(); await h.finish(1, []);
  assert.equal(nodes(h.render()).find(node => node.props?.className === 'empty').props.colSpan, 8);
});

test('Pending Ctrl/Command+A selects only current results, Escape clears and Delete is never handled', async t => {
  const h = harness(t, 'name:visible');
  h.render();
  assert.equal(h.press('a', { ctrlKey: true }).defaultPrevented, false, 'No snapshot yet');
  await h.finish(0, [row('file'), { ...row('merge-review'), directory: true, mergeId: 'review' }]);
  let tree = h.render();
  checkbox(tree, 'file').props.onChange({ currentTarget: { checked: true } });
  tree = h.render();
  assert.equal(header(tree).props.selected, 1);
  assert.equal(h.press('A', { metaKey: true }).defaultPrevented, true);
  tree = h.render();
  assert.deepEqual(selectedIds(tree), ['file', 'merge-review']);
  assert.equal(h.press('Delete').defaultPrevented, false);
  assert.equal(h.press('Escape').defaultPrevented, true);
  assert.deepEqual(selectedIds(h.render()), []);
  header(h.render()).props.onChange(true);
  assert.deepEqual(selectedIds(h.render()), ['file', 'merge-review']);
  header(h.render()).props.onChange(false);
  assert.deepEqual(selectedIds(h.render()), []);
});

test('Pending Shift checkbox ranges use shared anchor behavior and do not invoke Actions', async t => {
  const h = harness(t);
  h.render(); await h.finish(0, [row('a'), row('b'), row('c')]);
  let tree = h.render();
  const node = { closest: selector => selector.includes('input') ? {} : null };
  const rowEvent = (shiftKey) => ({ target: node, nativeEvent: { shiftKey },
    stopPropagation() { this.stopped = true; }, stopped: false });
  const rows = value => nodes(value).filter(node => node.type === 'tr' && node.props.id);
  rows(tree)[0].props.onChangeCapture(rowEvent(false));
  checkbox(tree, 'a').props.onChange({ currentTarget: { checked: true } });
  tree = h.render();
  const event = rowEvent(true);
  rows(tree)[2].props.onChangeCapture(event);
  assert.equal(event.stopped, true);
  assert.deepEqual(selectedIds(h.render()), ['a', 'b', 'c']);
  rows(h.render())[1].props.onChangeCapture(rowEvent(true));
  assert.deepEqual(selectedIds(h.render()), ['a', 'b']);
  assert.equal(h.requests.length, 1, 'Selecting does not send mutation or list requests');
});

test('Pending polling retains surviving IDs, never selects additions, and query changes reset selection', async t => {
  const h = harness(t, 'name:old');
  h.render(); await h.finish(0, [row('a'), row('b')]); h.render();
  h.press('a', { ctrlKey: true }); h.render();
  h.search.onChange('name:new');
  assert.deepEqual(selectedIds(h.render()), ['a', 'b'], 'Draft input is not a query change');
  await h.poll(); await h.finish(1, [row('b'), row('c')]);
  assert.deepEqual(selectedIds(h.render()), ['b']);
  h.search.onSubmit({ preventDefault() {} }); await flush();
  assert.deepEqual(selectedIds(h.render()), []);
  await h.finish(2, [row('b')]);
  assert.deepEqual(selectedIds(h.render()), [], 'A repeated ID in a new search is not selected');
});

test('Pending stale errors keep selection but disable shortcuts; resolved IDs are pruned', async t => {
  const h = harness(t);
  h.render(); await h.finish(0, [row('a'), row('b')]); h.render();
  h.press('a', { ctrlKey: true }); h.render();
  await h.poll(); h.requests[1].reject(new Error('Offline')); await flush();
  let tree = h.render();
  assert.deepEqual(selectedIds(tree), ['a', 'b']);
  assert.equal(header(tree).props.disabled, true);
  assert.equal(checkbox(tree, 'a').props.disabled, true);
  assert.equal(h.press('a', { ctrlKey: true }).defaultPrevented, false);
  await h.poll(); await h.finish(2, [row('a'), row('b')]); tree = h.render();
  h.resolve('a');
  assert.deepEqual(selectedIds(h.render()), ['b']);
});

test('partial bulk results prune only successes, keep failed selections and show per-item feedback across polling', async t => {
  const h = harness(t, 'source:admin_upload');
  h.render(); await h.finish(0, [row('a'), row('b'), row('c')]); h.render();
  h.press('a', { ctrlKey: true }); h.render();
  h.resolveSelected([
    { id: 'a', status: 'RESOLVED', removedId: 'a' },
    { id: 'b', status: 'NOT_FOUND', removedId: null, message: 'Required entry is missing.' },
    { id: 'c', status: 'FAILED', removedId: null, message: 'Recovery may be required.' },
  ]);
  let tree = h.render();
  assert.deepEqual(rowIds(tree), ['decision-b', 'decision-c']);
  assert.deepEqual(selectedIds(tree), ['b', 'c']);
  assert.ok(nodes(tree).some(node => node.props?.text === 'Required entry is missing.'));
  await h.poll(); await h.finish(1, [row('c'), row('new')]);
  tree = h.render();
  assert.deepEqual(selectedIds(tree), ['c']);
  assert.ok(nodes(tree).some(node => node.props?.text === 'Recovery may be required.'));
  await h.router.navigate('/admin/pending-decisions?q=name:new');
  h.render(); await h.finish(2, [row('c')]);
  assert.ok(!nodes(h.render()).some(node => node.props?.text === 'Recovery may be required.'));
});

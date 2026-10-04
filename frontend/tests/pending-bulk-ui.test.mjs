import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createMemoryRouter } from 'react-router-dom';
import { browserMenuContext } from '../src/shared/browser/browser-menu-context.ts';

async function compile(path) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${path}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}

const codes = {};
for (const name of ['pending-decisions/pending-decision-api.ts', 'shared/api/form-api.ts',
  'pending-decisions/pending-decision-actions.ts', 'pending-decisions/pending-decision-menu-actions.ts',
  'pending-decisions/PendingDecisionSelectionActions.tsx', 'shared/browser/ListingHistoryContext.tsx',
  'shared/browser/useBrowserContextMenu.ts']) {
  codes[name] = await compile(name);
}
function load(name, imports = {}, globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(codes[name], { module, exports: module.exports, ...globals,
    require: id => { assert.ok(id in imports, `Unexpected import ${id}`); return imports[id]; },
  });
  return module.exports;
}
const policy = load('pending-decisions/pending-decision-actions.ts');
const item = (id, directory = false, mergeId = null) => ({ id, directory, mergeId });
const menuPolicy = load('pending-decisions/pending-decision-menu-actions.ts', { './pending-decision-actions': policy });

function transport(answer) {
  const requests = [], notices = [], events = [], refreshes = [];
  const window = { location: { href: '/new-page' },
    dispatchEvent: event => events.push(event.type),
    EnderVault: { csrfPair: () => ({ name: '_csrf', value: 'csrf-token' }),
      requestJson: async (...args) => { requests.push(args); return answer(...args); },
      showNotification: notice => notices.push(notice) },
    EnderVaultFileBrowser: { requestListingRefresh: url => refreshes.push(url) } };
  const form = load('shared/api/form-api.ts', {}, { window, FormData, URLSearchParams });
  const api = load('pending-decisions/pending-decision-api.ts', { '../shared/api/form-api': form }, {
    window, URLSearchParams, CustomEvent: class { constructor(type) { this.type = type; } },
  });
  return { api, requests, notices, events, refreshes };
}

test('bulk transport sends 200 repeated IDs and CSRF in one URL-encoded request, with one summary/refresh', async () => {
  const result = { ok: true, succeededCount: 199, failedCount: 1, results: [],
    notification: { type: 'warning', message: '199 succeeded, 1 unsuccessful' } };
  const h = transport(() => result);
  const ids = Array.from({ length: 200 }, (_, index) => `id-${index}`);
  assert.equal(await h.api.resolvePendingSelection(ids, 'REPLACE', '/original-page'), result);
  assert.equal(h.requests.length, 1);
  const [url, options] = h.requests[0];
  assert.equal(url, '/api/v1/pending-decisions/resolve-selected'); assert.equal(options.method, 'POST');
  assert.ok(options.body instanceof URLSearchParams, 'No multipart part-count limitation');
  assert.deepEqual(options.body.getAll('ids'), ids); assert.equal(options.body.get('_csrf'), 'csrf-token');
  assert.equal(options.body.get('action'), 'REPLACE'); assert.equal(options.body.get('replaceConfirmed'), 'true');
  assert.deepEqual(h.notices, [result.notification]);
  assert.deepEqual(h.events, ['endervault:notifications-changed']); assert.deepEqual(h.refreshes, ['/original-page']);
});

test('all-failure envelopes are reported once and never interpreted as transport errors or retried', async () => {
  const result = { ok: true, succeededCount: 0, failedCount: 2, results: [], notification: { type: 'error' } };
  const h = transport(() => result);
  assert.equal(await h.api.resolvePendingSelection(['a', 'b'], 'DISCARD', '/pending'), result);
  assert.equal(h.requests.length, 1); assert.deepEqual(h.notices, [result.notification]);
  assert.equal(h.requests[0][1].body.has('replaceConfirmed'), false);
  assert.deepEqual(h.events, ['endervault:notifications-changed']); assert.deepEqual(h.refreshes, []);
});

test('uncertain HTTP/network failure refreshes once without replay, success notification or new-page targeting', async () => {
  for (const action of ['KEEP_BOTH', 'DISCARD']) {
    const failure = new Error('Network lost');
    const h = transport(() => { throw failure; });
    await assert.rejects(h.api.resolvePendingSelection(['a', 'b'], action, '/pending?q=name:test'), error => error === failure);
    assert.equal(h.requests.length, 1); assert.deepEqual(h.notices, []);
    assert.deepEqual(h.events, ['endervault:notifications-changed']);
    assert.deepEqual(h.refreshes, action === 'DISCARD' ? [] : ['/pending?q=name:test']);
  }
});

function menuHarness() {
  const calls = [];
  let reason = '';
  const actions = { runSelected: (...args) => calls.push(args), unavailableReason: () => reason };
  const menu = load('pending-decisions/pending-decision-menu-actions.ts', {
    './pending-decision-actions': policy,
  }).pendingDecisionMenuActions(actions);
  const context = items => ({ mode: items.length > 1 ? 'selection' : items.length ? 'single' : 'background', items });
  const visible = items => menu.filter(action => action.visible(context(items)));
  return { menu, context, visible, actions, calls, block: value => { reason = value; } };
}

test('Pending single/multiple menu actions use the same registry as row buttons and never offer bulk rename/merge', () => {
  const h = menuHarness();
  for (const items of [[item('file')], [item('dir', true)], [item('owned', true, 'review')],
    [item('a'), item('b')], [item('a'), item('b', true)]]) {
    const definitions = policy.pendingDecisionActionsFor(items);
    const context = h.context(items);
    const visible = h.visible(items);
    assert.deepEqual(Array.from(visible, action => action.id), Array.from(definitions, action => `pending-${action.id.toLowerCase()}`));
    visible.forEach((action, index) => {
      assert.equal(action.icon, definitions[index].icon); assert.equal(action.danger, definitions[index].danger);
      assert.equal(action.disabled(context), false); assert.equal(action.title(context), definitions[index].description);
      assert.equal(action.label(context), items.length > 1 ? `${definitions[index].label} (${items.length} selected)` : definitions[index].label);
      action.run(context);
      assert.deepEqual(Array.from(h.calls.at(-1)[0]), items.map(item => item.id));
      assert.equal(h.calls.at(-1)[1], definitions[index].id);
    });
  }
  assert.deepEqual(Array.from(h.visible([])), [], 'Background leaves global sticky action to the menu engine');
});

test('mixed ordinary/review selection has a disabled explanation, never a silently reduced bulk target', () => {
  const h = menuHarness();
  const items = [item('a'), item('owned', true, 'review')];
  const actions = h.visible(items);
  assert.equal(actions.length, 1); assert.equal(actions[0].id, 'pending-no-common-action');
  assert.equal(actions[0].disabled, true); assert.equal(actions[0].title, policy.NO_COMMON_PENDING_ACTION);
  actions[0].run(h.context(items)); assert.deepEqual(h.calls, []);
});

test('limits and in-flight reasons disable menu actions with a shared explanatory title', () => {
  const h = menuHarness(); const items = [item('a'), item('b')]; const context = h.context(items);
  h.block('Select no more than 200 items per action.');
  h.visible(items).forEach(action => {
    assert.equal(action.disabled(context), true); assert.match(action.title(context), /200/);
  });
  h.block('An action is already in progress for these items.');
  assert.match(h.visible(items)[0].title(context), /in progress/);
});

test('shared row targeting uses Pending IDs, preserves multi-selection and clears selection for another row', () => {
  const h = menuHarness(); const entries = [item('a'), item('b', true), item('c')];
  const rows = entries.map(entry => ({ getAttribute: () => entry.id,
    closest: selector => selector === '[data-context-item="true"]' ? rows.find(row => row.getAttribute() === entry.id) : null }));
  let cleared = 0;
  const workspace = { contains: target => rows.includes(target), querySelectorAll: () => rows };
  const context = target => browserMenuContext({ event: { target }, workspace, entries, itemKey: entry => entry.id,
    keyAttribute: 'data-decision-id', selected: new Set(['a', 'b']), clearSelection: () => cleared++ });
  const multi = context(rows[0]);
  assert.equal(multi.mode, 'selection');
  const keep = h.menu.find(action => action.id === 'pending-keep_both');
  keep.run(multi); assert.deepEqual(Array.from(h.calls[0][0]), ['a', 'b']);
  const single = context(rows[2]); assert.equal(single.mode, 'single'); assert.equal(cleared, 1);
  keep.run(single); assert.deepEqual(Array.from(h.calls[1][0]), ['c']);
});

function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.props?.children)];
}

test('click/touch alternative reuses FloatingPageActions, definitions and the exact same executor', () => {
  const h = menuHarness();
  function FloatingPageActions() {}
  const component = load('pending-decisions/PendingDecisionSelectionActions.tsx', {
    'react/jsx-runtime': jsx, '../app/FloatingPageActions': { FloatingPageActions },
    './pending-decision-actions': policy,
  });
  for (const items of [[], [item('a')], [item('a'), item('b')], [item('a'), item('b', true)],
    [item('a'), item('owned', true, 'review')]]) {
    h.block('');
    const tree = component.PendingDecisionSelectionActions({ items, actions: h.actions });
    assert.equal(tree.type, FloatingPageActions); assert.equal(tree.props.selectedCount, items.length);
    const buttons = nodes(tree).filter(node => node.type === 'button');
    const definitions = policy.pendingDecisionActionsFor(items);
    assert.equal(buttons.length, definitions.length || 1);
    buttons.forEach((button, index) => {
      if (!definitions.length) { assert.equal(button.props.disabled, true); return; }
      assert.equal(button.props['aria-label'], definitions[index].label);
      button.props.onClick();
      assert.deepEqual(Array.from(h.calls.at(-1)[0]), items.map(item => item.id));
      assert.equal(h.calls.at(-1)[1], definitions[index].id);
    });
    h.block('Busy');
    const busy = component.PendingDecisionSelectionActions({ items, actions: h.actions });
    nodes(busy).filter(node => node.type === 'button').forEach(button => assert.equal(button.props.disabled, true));
  }
});

test('the reusable Router guard observes departure before React rerenders, without activating a listing', async t => {
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: ['/admin/pending-decisions'] });
  t.after(() => router.dispose());
  const history = { isCurrent: location => location === router.state.location };
  const hooks = load('shared/browser/ListingHistoryContext.tsx', {
    react: { createContext: () => ({}), useContext: () => history },
    'react/jsx-runtime': jsx, 'react-router-dom': { useLocation: () => router.state.location },
  });
  const guard = hooks.useLocationGuard();
  assert.equal(guard(), true);
  await router.navigate('/files'); assert.equal(guard(), false);
  assert.equal(hooks.useLocationGuard()(), true);
});

test('Pending retains menu targets across identical polling, reordered results and unrelated additions', () => {
  const a = { ...item('a'), originalFilename: 'a.txt', destinationLabel: '/photos' };
  const b = { ...item('b', true), originalFilename: 'folder', destinationLabel: '/photos' };
  for (const context of [{ mode: 'single', items: [a] }, { mode: 'selection', items: [a, b] }]) {
    const selected = new Set(context.mode === 'selection' ? ['a', 'b'] : []);
    assert.equal(menuPolicy.keepPendingMenuContext(context,
      [{ ...b, statusLabel: 'Updated' }, item('new'), { ...a }], selected), true);
  }
  assert.equal(menuPolicy.keepPendingMenuContext({ mode: 'background', items: [] }, [a], new Set()), true);
});

test('Pending invalidates a menu when its target, ownership, name, destination or multi-selection changes', () => {
  const a = { ...item('a'), originalFilename: 'a.txt', destinationLabel: '/photos' };
  const b = { ...item('b', true), originalFilename: 'folder', destinationLabel: '/photos' };
  const context = { mode: 'selection', items: [a, b] };
  for (const next of [[a], [a, { ...b, mergeId: 'review' }], [a, { ...b, directory: false }],
    [a, { ...b, originalFilename: 'renamed' }], [a, { ...b, destinationLabel: '/new-path' }]]) {
    assert.equal(menuPolicy.keepPendingMenuContext(context, next, new Set(['a', 'b'])), false);
  }
  for (const selected of [new Set(), new Set(['a']), new Set(['a', 'b', 'new'])]) {
    assert.equal(menuPolicy.keepPendingMenuContext(context, [a, b], selected), false);
  }
  assert.equal(menuPolicy.keepPendingMenuContext({ mode: 'single', items: [a] }, [a, b], new Set(['b'])), false);
  assert.equal(menuPolicy.keepPendingMenuContext({ mode: 'single', items: [a] }, [a], new Set(['a'])), true);
});

function menuHookHarness() {
  const slots = [], effects = [];
  let cursor = 0, closed = 0, disposed = 0, active = null;
  const menu = { close: () => { closed++; active = null; }, dispose: () => disposed++, activeContext: () => active };
  const hooks = load('shared/browser/useBrowserContextMenu.ts', {
    react: {
      useRef(initial) { const index = cursor++; return slots[index] ??= { current: initial }; },
      useEffect(run, deps) {
        const index = cursor++, previous = slots[index];
        if (previous && deps.every((dep, i) => Object.is(dep, previous.deps[i]))) return;
        const slot = { deps, cleanup: previous?.cleanup }; slots[index] = slot;
        effects.push(() => { slot.cleanup?.(); slot.cleanup = run(); });
      },
    }, './browser-menu-context': { browserMenuContext() {} },
  }, { document: { querySelector: () => ({}) }, window: { EnderVaultContextMenus: { createActionMenu: () => menu } } });
  return { get closed() { return closed; }, get disposed() { return disposed; }, get active() { return active; },
    open(context) { active = context; },
    render(options) {
      cursor = 0;
      hooks.useBrowserContextMenu({ menuId: 'pending', pageScope: 'pending-react', errorMessage: 'Failed',
        entries: () => [], actions: () => [], ...options });
      effects.splice(0).forEach(run => run());
    }, dispose() { slots.forEach(slot => slot.cleanup?.()); } };
}

test('common menu retention is opt-in and keeps the same DOM/context only for valid refreshed targets', () => {
  const h = menuHookHarness(); const a = item('a'); const context = { mode: 'single', items: [a] };
  let items = [a], enabled = true;
  const options = () => ({ contentKey: items, contextKey: 'same-query',
    keepOnRefresh: current => enabled && menuPolicy.keepPendingMenuContext(current, items, new Set()) });
  h.render(options()); h.open(context); const before = h.closed;
  items = [item('new'), { ...a }]; h.render(options());
  assert.equal(h.closed, before); assert.equal(h.active, context);
  items = [item('new'), { ...a }]; h.render(options()); assert.equal(h.closed, before);
  enabled = false; items = [...items]; h.render(options()); assert.equal(h.closed, before + 1);
  enabled = true; h.open(context);
  h.render({ ...options(), contextKey: 'new-query' }); assert.equal(h.active, null);
  h.dispose(); assert.equal(h.disposed, 1);

  const legacy = menuHookHarness(); legacy.render({ contentKey: [a] }); legacy.open(context);
  const oldCount = legacy.closed; legacy.render({ contentKey: [{ ...a }] });
  assert.equal(legacy.closed, oldCount + 1, 'Other callers keep their existing close-on-refresh default');
  legacy.dispose();
});

test('danger context items override the global danger background only while idle', () => {
  const css = readFileSync(new URL('../../src/main/resources/static/css/components/context-menu.css', import.meta.url), 'utf8');
  const base = readFileSync(new URL('../../src/main/resources/static/css/base.css', import.meta.url), 'utf8');
  assert.match(base, /button\.danger\s*\{[^}]*background:\s*var\(--danger-bg\)/);
  assert.match(css, /\.context-menu-item\.danger\s*\{[^}]*background:\s*transparent;[^}]*color:\s*var\(--danger\)/);
  assert.match(css, /\.context-menu-item\.danger:not\(:disabled\):hover,[\s\S]*?background:\s*var\(--danger-bg\)/);
});

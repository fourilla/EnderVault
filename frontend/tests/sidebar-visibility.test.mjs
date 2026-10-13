import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { createHookHarness } from './helpers/react-hooks.mjs';
import { bootstrapReducer, initialBootstrapState } from '../src/app/bootstrap-state.ts';
import { AdminAppAccessDeniedError, AdminAppSessionExpiredError } from '../src/app/app-api.ts';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent',
    build: { write: false, minify: false,
      lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const providerCode = await compile('app/AdminAppContext.tsx');
const sidebarCode = await compile('app/AdminSidebar.tsx');
const favoriteCode = await compile('shared/api/favorite-api.ts');
const serverTasksCode = readFileSync(new URL('../../src/main/resources/static/js/server-tasks.js', import.meta.url), 'utf8');
const flush = () => new Promise(setImmediate);

function snapshot(hidden) {
  return { browser: { showTableActions: false, hidden }, appearance: {}, capabilities: {}, storage: {},
    favorites: (hidden === 'show' ? ['visible.txt', 'private/child.txt', 'secret.txt'] : ['visible.txt'])
      .map(path => ({ path, name: path, hidden: path !== 'visible.txt', openUrl: '/files' })) };
}

// Exercise the real Provider/reducer and sidebar with deferred transport and deterministic hooks.
function setup(t, { panelEnabled = false, trackedIds = [] } = {}) {
  const h = createHookHarness(), pending = [], toasts = [];
  const window = new EventTarget(), document = new EventTarget();
  const storage = new Map([['endervault.trackedTasks', JSON.stringify(trackedIds)]]), timers = new Map();
  let taskResponse = [], timerId = 0, invalidations = 0;
  document.visibilityState = 'visible';
  document.body = { dataset: { serverTaskActivity: String(panelEnabled) } };
  document.querySelector = () => null;
  document.addEventListener('endervault:favorites-changed', () => invalidations++);
  window.setInterval = callback => { timers.set(++timerId, callback); return timerId; };
  window.clearInterval = id => timers.delete(id);
  window.sessionStorage = { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) };
  window.EnderVault = { showToast: (...args) => toasts.push(args), requestJson: async () => taskResponse };
  vm.runInNewContext(serverTasksCode, { window, document, CustomEvent, FormData, URLSearchParams });
  let value;
  h.react.createContext = () => ({ Provider: 'Provider' });
  h.react.useContext = () => value;
  h.react.useReducer = (reducer, initial) => {
    const [state, setState] = h.react.useState(initial);
    return [state, h.react.useCallback(action => setState(current => reducer(current, action)), [reducer])];
  };
  const jsx = (type, props) => ({ type, props });
  const modules = {
    react: h.react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'Fragment' },
    './app-api': { AdminAppAccessDeniedError, AdminAppSessionExpiredError,
      fetchAdminAppBootstrap: signal => new Promise((resolve, reject) => pending.push({ signal, resolve, reject })) },
    './bootstrap-state': { bootstrapReducer, initialBootstrapState },
    '../shared/appearance/presets': { applyAppearance() {} },
    '../shared/layout/LoadingState': { LoadingState: 'LoadingState' },
    '../shared/layout/PageErrorPanel': { PageErrorPanel: 'PageErrorPanel' },
    'react-router-dom': { Link: 'Link', NavLink: 'NavLink' },
    './navigation': { navigationFor: () => [], navigationEntryAvailable: () => true, isSpaNavigationUrl: () => true },
    './ShellNavigationLink': { ShellNavigationLink: 'ShellNavigationLink' },
    './form-api': { notify() {}, postForm() { assert.fail('Favorites task refresh must not submit a mutation'); } },
    './selected-item-api': { resolveSelectedItems() { assert.fail('Favorites task refresh must not resolve selections'); } },
  };
  function load(code) {
    const module = { exports: {} };
    vm.runInNewContext(code, { module, exports: module.exports, window, document,
      AbortController, CustomEvent, DOMException, Error,
      require: id => { assert.ok(id in modules, `unexpected import ${id}`); return modules[id]; } });
    return module.exports;
  }
  modules['../shared/api/favorite-api'] = load(favoriteCode);
  const provider = load(providerCode);
  modules['./AdminAppContext'] = provider;
  const { AdminSidebar } = load(sidebarCode);
  const child = {};
  const render = () => h.render(() => provider.AdminAppProvider({ children: child }), tree => { value = tree.props.value; });
  function favoriteLinks() {
    const links = [];
    const visit = node => {
      if (Array.isArray(node)) node.forEach(visit);
      else if (node && typeof node === 'object') {
        if (node.props?.favorite) links.push(node.type(node.props));
        visit(node.props?.children);
      }
    };
    visit(AdminSidebar({ favoritesOpen: true, onToggleFavorites() {} }));
    return links;
  }
  const favorites = () => favoriteLinks().map(link => link.props['data-favorite-sidebar-path']);
  t.after(() => h.dispose());
  return { h, pending, toasts, window, document, render, favorites, favoriteLinks, child,
    tasks: window.EnderVaultServerTasks, respond: tasks => { taskResponse = tasks; },
    get value() { return value; }, get invalidations() { return invalidations; },
    async finish(hidden, index = pending.length - 1) {
      pending[index].resolve(typeof hidden === 'string' ? snapshot(hidden) : hidden); await flush(); return render();
    },
    async fail(index = pending.length - 1) {
      pending[index].reject(new Error('offline')); await flush(); return render();
    },
  };
}

test('accepted hidden policy refreshes sidebar in both directions without replacing the workspace', async t => {
  const s = setup(t);
  s.render(); await s.finish('hide');
  const sync = s.value.syncBrowserHidden;
  sync('hide');
  assert.equal(s.pending.length, 1, 'unchanged initial policy needs no extra bootstrap');
  sync('show');
  assert.deepEqual(s.favorites(), ['visible.txt'], 'keep current sidebar until the new bootstrap arrives');
  const tree = await s.finish('show');
  assert.equal(tree.props.children, s.child);
  assert.equal(s.value.syncBrowserHidden, sync, 'bootstrap refresh must not restart listing effects');
  assert.deepEqual(s.favorites(), ['visible.txt', 'private/child.txt', 'secret.txt']);
  sync('show');
  assert.equal(s.pending.length, 2);
  sync('hide'); await s.finish('hide');
  assert.deepEqual(s.favorites(), ['visible.txt']);
});

test('rapid show then hide cancels pending show and ignores its late bootstrap', async t => {
  const s = setup(t);
  s.render(); await s.finish('hide');
  s.value.syncBrowserHidden('show');
  s.value.syncBrowserHidden('show');
  assert.equal(s.pending.length, 2, 'same policy observations share the pending refresh');
  s.value.syncBrowserHidden('hide');
  assert.equal(s.pending.length, 3, 'returning to the initial policy still replaces a pending opposite refresh');
  assert.equal(s.pending[1].signal.aborted, true);
  await s.finish('hide', 2);
  await s.finish('show', 1);
  assert.equal(s.value.bootstrap.browser.hidden, 'hide');
  assert.deepEqual(s.favorites(), ['visible.txt']);
  s.value.syncBrowserHidden('hide');
  assert.equal(s.pending.length, 3);
});

test('failed visibility refresh preserves the shell and retries on the next accepted same policy', async t => {
  const s = setup(t);
  s.render(); await s.finish('hide');
  s.value.syncBrowserHidden('show');
  const tree = await s.fail();
  assert.equal(tree.props.children, s.child);
  assert.deepEqual(s.favorites(), ['visible.txt']);
  assert.equal(s.toasts.length, 1);
  s.value.syncBrowserHidden('show');
  assert.equal(s.pending.length, 3, 'an unconfirmed policy is not cached as successfully synchronized');
  await s.finish('show');
  assert.equal(s.favorites().length, 3);
  s.window.dispatchEvent(new Event('focus'));
  assert.equal(s.pending.length, 3, 'successful refresh removes the failure retry listener');
});

test('existing favorites invalidation and focus retry continue using the same bootstrap loader', async t => {
  const s = setup(t);
  s.render(); await s.finish('hide');
  s.document.dispatchEvent(new Event('endervault:favorites-changed'));
  assert.equal(s.pending.length, 2);
  await s.fail();
  s.window.dispatchEvent(new Event('focus'));
  assert.equal(s.pending.length, 3);
  await s.finish('hide');
  s.value.syncBrowserHidden('hide');
  assert.equal(s.pending.length, 3);
});

test('Favorites invalidation refreshes hidden styles and renamed links while Show hidden remains selected', async t => {
  const s = setup(t);
  s.render(); await s.finish('show');
  const secret = () => s.favoriteLinks().find(link => link.props['data-favorite-sidebar-path'] === 'secret.txt');
  assert.equal(secret().props.className, 'is-hidden-item');
  s.document.dispatchEvent(new Event('endervault:favorites-changed'));
  assert.equal(secret().props.className, 'is-hidden-item', 'retain the old snapshot during refresh');
  const next = snapshot('show');
  next.favorites = next.favorites.map(favorite => favorite.path === 'secret.txt' ? { ...favorite, hidden: false } : favorite);
  s.pending.at(-1).resolve(next); await flush(); s.render();
  assert.equal(secret().props.className, undefined, 'visible item loses the translucent style');
  s.document.dispatchEvent(new Event('endervault:favorites-changed'));
  const renamed = { ...next, favorites: next.favorites.map(favorite => favorite.path === 'secret.txt'
    ? { ...favorite, path: 'visible +.txt', name: 'visible +.txt', openUrl: '/files/detail?path=visible%20%2B.txt' } : favorite) };
  s.pending.at(-1).resolve(renamed); await flush(); s.render();
  assert.equal(secret(), undefined);
  const link = s.favoriteLinks().find(link => link.props['data-favorite-sidebar-path'] === 'visible +.txt');
  assert.equal(link.props.className, undefined);
  assert.equal(link.props.to, '/files/detail?path=visible%20%2B.txt');
  s.value.syncBrowserHidden('show');
  assert.equal(s.pending.length, 3, 'unchanged view policy does not suppress or duplicate mutation refreshes');
});

const fileTask = (id, type = 'FILE_MOVE', status = 'COMPLETE', active = false) => ({
  id, type, status, active, progressPercent: active ? 10 : 100,
});

for (const hidden of ['hide', 'show']) {
  test(`completed move and trash refresh sidebar paths with ${hidden} hidden policy and no listing or activity panel`, async t => {
    const s = setup(t);
    s.render(); await s.finish(hidden);
    const move = fileTask('move');
    s.tasks.track({ ...move, status: 'RUNNING', active: true }, { announceStart: true });
    assert.equal(s.pending.length, 1, 'task admission must not refresh an unchanged Favorites snapshot');
    assert.equal(s.invalidations, 0);
    assert.equal(s.window.EnderVaultFileBrowser, undefined, 'sidebar synchronization cannot depend on a mounted listing');
    s.respond([move]); await s.tasks.refresh();
    assert.equal(s.invalidations, 1);
    assert.equal(s.pending.length, 2);
    assert.ok(s.favorites().includes('visible.txt'), 'retain the existing sidebar while refreshing');
    const moved = snapshot(hidden);
    moved.favorites[0] = { ...moved.favorites[0], path: 'target/visible +.txt', name: 'visible +.txt',
      openUrl: '/files/detail?path=target%2Fvisible%20%2B.txt' };
    await s.finish(moved);
    assert.ok(!s.favorites().includes('visible.txt'));
    assert.equal(s.favoriteLinks()[0].props.to, '/files/detail?path=target%2Fvisible%20%2B.txt');
    assert.equal(s.value.bootstrap.browser.hidden, hidden);
    s.tasks.track(move);
    assert.equal(s.pending.length, 2, 'repeated final task snapshots must not emit duplicate refreshes');
    s.tasks.track(fileTask('trash', 'FILE_TRASH', 'RUNNING', true));
    assert.equal(s.pending.length, 2);
    s.respond([fileTask('trash', 'FILE_TRASH')]); await s.tasks.refresh();
    const trashed = { ...moved, favorites: moved.favorites.slice(1) };
    await s.finish(trashed);
    assert.ok(!s.favorites().includes('target/visible +.txt'), 'trashed Favorites leave the sidebar');
    assert.equal(s.invalidations, 2);
    s.value.syncBrowserHidden(hidden);
    assert.equal(s.pending.length, 3, 'task invalidation preserves policy synchronization without an extra refresh');
  });
}

test('move, trash and directory merge invalidate every inactive outcome including partial, pending, canceled and failed', async t => {
  const s = setup(t);
  s.render(); await s.finish('hide');
  let expected = 0;
  for (const type of ['FILE_MOVE', 'FILE_TRASH', 'DIRECTORY_MERGE']) {
    for (const status of ['COMPLETE', 'PARTIAL', 'PENDING', 'CANCELED', 'FAILED']) {
      s.tasks.track(fileTask(`${type}-${status}`, type, status));
      assert.equal(s.invalidations, ++expected, `${type}/${status} may have committed changes before stopping`);
      assert.equal(s.pending.length, expected + 1);
      await s.finish('hide');
    }
  }
});

test('unrelated and active task signals do not invalidate Favorites', async t => {
  const s = setup(t);
  s.render(); await s.finish('hide');
  for (const type of ['FILE_COPY', 'ARCHIVE_CREATE', 'METADATA_INSPECTION', 'REMOTE_DOWNLOAD', 'UNKNOWN']) {
    s.tasks.track(fileTask(type, type));
  }
  for (const detail of [fileTask('active', 'FILE_MOVE', 'RUNNING', true),
    { type: 'FILE_TRASH', status: 'COMPLETE' }, null]) {
    s.document.dispatchEvent(new CustomEvent('endervault:task-terminal', { detail }));
  }
  assert.equal(s.invalidations, 0);
  assert.equal(s.pending.length, 1);
});

test('restored task polling updates Favorites without a locally initiated task marker', async t => {
  const s = setup(t, { panelEnabled: true, trackedIds: ['restored'] });
  const terminal = [];
  s.document.addEventListener('endervault:task-terminal', event => terminal.push(event.detail));
  s.render(); await s.finish('hide');
  s.respond([fileTask('restored', 'FILE_TRASH')]);
  s.document.dispatchEvent(new Event('DOMContentLoaded'));
  await flush();
  assert.equal(terminal.length, 1);
  assert.equal(terminal[0].initiatedHere, false);
  assert.equal(s.invalidations, 1);
  await s.finish({ ...snapshot('hide'), favorites: [] });
  assert.deepEqual(s.favorites(), []);
});

test('task refresh supersedes an older pending bootstrap and ignores its stale paths', async t => {
  const s = setup(t);
  s.render(); await s.finish('hide');
  s.value.syncBrowserHidden('show');
  s.tasks.track(fileTask('move'));
  assert.equal(s.pending.length, 3);
  assert.equal(s.pending[1].signal.aborted, true);
  const moved = snapshot('show');
  moved.favorites[0] = { ...moved.favorites[0], path: 'target/visible.txt', openUrl: '/files/detail?path=target%2Fvisible.txt' };
  await s.finish(moved, 2);
  await s.finish('show', 1);
  assert.ok(s.favorites().includes('target/visible.txt'));
  assert.ok(!s.favorites().includes('visible.txt'));
  assert.equal(s.favoriteLinks()[0].props.to, '/files/detail?path=target%2Fvisible.txt');
  s.value.syncBrowserHidden('show');
  assert.equal(s.pending.length, 3);
});

test('shell unmount detaches the task observer and Favorites refresh listener', async t => {
  const s = setup(t);
  s.render(); await s.finish('hide');
  s.h.dispose();
  s.tasks.track(fileTask('move'));
  assert.equal(s.invalidations, 0, 'unmounted shell must no longer translate task events');
  s.document.dispatchEvent(new Event('endervault:favorites-changed'));
  assert.equal(s.pending.length, 1, 'unmounted shell must not start another bootstrap');
});

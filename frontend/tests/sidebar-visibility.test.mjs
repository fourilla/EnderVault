import assert from 'node:assert/strict';
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
      lib: { entry: fileURLToPath(new URL(`../src/app/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const providerCode = await compile('AdminAppContext.tsx');
const sidebarCode = await compile('AdminSidebar.tsx');
const flush = () => new Promise(setImmediate);

function snapshot(hidden) {
  return { browser: { showTableActions: false, hidden }, appearance: {}, capabilities: {}, storage: {},
    favorites: (hidden === 'show' ? ['visible.txt', 'private/child.txt', 'secret.txt'] : ['visible.txt'])
      .map(path => ({ path, name: path, hidden: path !== 'visible.txt', openUrl: '/files' })) };
}

// Exercise the real Provider/reducer and sidebar with deferred transport and deterministic hooks.
function setup(t) {
  const h = createHookHarness(), pending = [], toasts = [];
  const window = new EventTarget(), document = new EventTarget();
  window.EnderVault = { showToast: (...args) => toasts.push(args) };
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
  };
  function load(code) {
    const module = { exports: {} };
    vm.runInNewContext(code, { module, exports: module.exports, window, document,
      AbortController, DOMException, Error,
      require: id => { assert.ok(id in modules, `unexpected import ${id}`); return modules[id]; } });
    return module.exports;
  }
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
  return { pending, toasts, window, document, render, favorites, favoriteLinks, child, get value() { return value; },
    async finish(hidden, index = pending.length - 1) {
      pending[index].resolve(snapshot(hidden)); await flush(); return render();
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

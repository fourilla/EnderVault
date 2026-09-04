import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { createMemoryRouter } from 'react-router-dom';
import { createListingHistory } from '../src/shared/browser/listing-history.ts';
import { browserHistory } from '../src/files/browser-history.ts';
import { canonicalState } from '../src/files/browser-api.ts';
import { createScrollRestoration } from '../src/shared/browser/scroll-restoration.ts';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent',
    build: { write: false, minify: false,
      lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
}
const contextCode = await compile('shared/browser/ListingHistoryContext.tsx');
const scrollCode = await compile('shared/browser/useNavigationScroll.ts');
const filesCode = await compile('files/useBrowserNavigation.ts');

// Deterministic hook lifecycle harness. Router is real; DOM layout and React scheduling are not.
function hooks() {
  const slots = [];
  let cursor = 0, dirty = true;
  const layouts = [], effects = [];
  const same = (a, b) => a && b && a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  const effect = (queue) => (run, deps) => {
    const index = cursor++, previous = slots[index];
    if (!previous || !same(previous.deps, deps)) {
      const next = { deps, cleanup: previous?.cleanup };
      slots[index] = next;
      queue.push(() => { next.cleanup?.(); next.cleanup = run(); });
    }
  };
  const react = {
    createContext: () => ({}), useContext: () => null,
    useState(initial) {
      const index = cursor++;
      if (!slots[index]) slots[index] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, (update) => {
        const value = typeof update === 'function' ? update(slots[index].value) : update;
        if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
      }];
    },
    useRef(initial) { const index = cursor++; return slots[index] ||= { current: initial }; },
    useMemo(create, deps) {
      const index = cursor++;
      if (!slots[index] || !same(slots[index].deps, deps)) slots[index] = { deps, value: create() };
      return slots[index].value;
    },
    useCallback(callback, deps) { return react.useMemo(() => callback, deps); },
    useEffect: effect(effects), useLayoutEffect: effect(layouts),
  };
  return {
    react, changed() { dirty = true; },
    render(run) {
      let result, count = 0;
      do {
        assert.ok(++count < 30, 'render/effect loop');
        dirty = false; cursor = 0; result = run();
        layouts.splice(0).forEach((fn) => fn());
        effects.splice(0).forEach((fn) => fn());
      } while (dirty);
      return result;
    },
    dispose() { slots.forEach((slot) => slot.cleanup?.()); },
  };
}

function load(code, require, window = {}, document = {}) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, require, window, document,
    URLSearchParams, AbortController, CustomEvent });
  return module.exports;
}

function payload(state, view = state.view || 'table') {
  return { mode: state.mode, path: state.path, parentPath: null, breadcrumbs: [], directories: [], entries: [],
    page: { number: state.page, totalPages: 5 }, search: { query: state.query, performed: state.mode === 'search' },
    preferences: { view, sort: state.sort || 'name', direction: state.direction || 'asc',
      hidden: state.hidden || 'hide', pageSize: state.pageSize || 50, pageSizeOptions: [50] } };
}

async function flush() { await new Promise(setImmediate); }

function setup(t) {
  const h = hooks(), scrolls = [], pending = [];
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: ['/files'] });
  const window = { scrollY: 0, scrollTo({ top }) { this.scrollY = top; scrolls.push(top); } };
  const document = { dispatchEvent() {}, querySelector: () => null };
  const history = createListingHistory(router, () => window.scrollY, () => ({ getItem: () => null, setItem() {} }));
  const unsubscribe = router.subscribe(h.changed);
  h.react.useContext = () => history;
  const routeNavigate = router.navigate.bind(router);
  const modules = {
    react: h.react, 'react/jsx-runtime': {},
    'react-router-dom': { useLocation: () => router.state.location, useNavigate: () => routeNavigate },
    './browser-history': { browserHistory },
    './scroll-restoration': { createScrollRestoration },
    '../shared/browser/useListingRefresh': { useListingRefresh() {} },
    '../shared/api/form-api': { postForm: async (_url, { view }) => ({ view }), toastError() {} },
    './browser-api': { canonicalState, loadBrowserPayload: (state, signal) => new Promise((resolve) => {
      pending.push({ state, signal, resolve });
    }) },
  };
  const require = (id) => {
    assert.ok(id in modules, `unexpected import ${id}`);
    return modules[id];
  };
  const context = load(contextCode, require, window, document);
  modules['../shared/browser/ListingHistoryContext'] = context;
  modules['../shared/browser/useNavigationScroll'] = load(scrollCode, require, window, document);
  const { useBrowserNavigation } = load(filesCode, require, window, document);
  t.after(() => { h.dispose(); unsubscribe(); history.dispose(); router.dispose(); });
  const render = () => h.render(useBrowserNavigation);
  return { router, history, window, scrolls, pending, render, h, context,
    async finish(index = pending.length - 1) {
      pending[index].resolve(payload(pending[index].state));
      await flush(); return render();
    },
  };
}

test('Files view-only change avoids refetch and survives a subsequent data refresh', async (t) => {
  const s = setup(t);
  s.render();
  let result = await s.finish();
  assert.equal(s.pending.length, 1);
  const key = s.router.state.location.key;
  await result.applyView('grid');
  result = s.render();
  assert.equal(result.payload.preferences.view, 'grid');
  assert.equal(s.pending.length, 1);
  assert.equal(s.router.state.location.key, key);
  s.window.scrollY = 800;
  result.reload();
  s.render();
  assert.equal(s.pending[1].state.view, 'grid');
  result = await s.finish();
  assert.equal(result.payload.preferences.view, 'grid');
  assert.equal(s.window.scrollY, 800);
  assert.deepEqual(s.scrolls, [0]);
});

test('current-directory navigation completes once and replaces instead of duplicating the visit', async (t) => {
  const s = setup(t);
  s.render();
  let result = await s.finish();
  const key = s.router.state.location.key;
  result.browse('');
  result = s.render();
  assert.notEqual(s.router.state.location.key, key);
  assert.equal(s.router.state.historyAction, 'REPLACE');
  assert.equal(result.payload, null);
  assert.equal(s.pending.length, 2);
  result = await s.finish();
  assert.equal(result.loading, false);
  assert.ok(result.payload);
  assert.equal(s.pending.length, 2);
});

test('direct directory query becomes Router state without duplicate fetching or extra Back entries', async (t) => {
  const s = setup(t);
  s.render();
  await s.finish();
  await s.router.navigate('/files?path=child&size=50');
  let result = s.render();
  assert.equal(s.router.state.location.pathname, '/files');
  assert.equal(s.router.state.location.search, '');
  assert.equal(result.state.path, 'child');
  assert.equal(s.pending.length, 2);
  result = await s.finish();
  assert.equal(result.payload.path, 'child');
  await s.router.navigate(-1);
  result = s.render();
  assert.equal(result.state.path, '');
});

test('same-component Back clears old rows and restores scroll only after the restored list arrives', async (t) => {
  const s = setup(t);
  s.render();
  let result = await s.finish();
  s.window.scrollY = 430;
  result.browse('child');
  result = s.render();
  assert.equal(result.payload, null);
  result = await s.finish();
  assert.equal(result.payload.path, 'child');
  s.window.scrollY = 700;
  await s.router.navigate(-1);
  result = s.render();
  assert.equal(result.state.path, '');
  assert.equal(result.payload, null);
  assert.equal(s.scrolls.at(-1), 0);
  result = await s.finish();
  assert.equal(result.payload.path, '');
  assert.equal(s.scrolls.at(-1), 430);
});

test('late Files response cannot change sticky context, visible data, or the current Recent URL', async (t) => {
  const s = setup(t);
  s.render();
  await s.router.navigate('/files/recent');
  // Resolve before unmount cleanup to exercise the synchronous Router ownership guard.
  s.pending[0].resolve(payload(s.pending[0].state));
  await flush();
  assert.equal(s.router.state.location.pathname, '/files/recent');
  assert.equal(s.history.isCurrent(s.router.state.location), true);
  await s.router.navigate(-1);
  const result = s.render();
  assert.equal(result.payload, null);
  assert.equal(s.pending.length, 1); // The harness never unmounted; real React remounts and refetches.
});

test('snapshot setter from an old visit cannot remove or replace the current rows', (t) => {
  const s = setup(t);
  let [value, setFirst] = s.h.render(() => s.context.useListingSnapshot('first'));
  assert.equal(value, null);
  setFirst({ name: 'first' });
  [value] = s.h.render(() => s.context.useListingSnapshot('first'));
  assert.equal(value.name, 'first');
  let [, setSecond] = s.h.render(() => s.context.useListingSnapshot('second'));
  setSecond({ name: 'second' });
  s.h.render(() => s.context.useListingSnapshot('second'));
  setFirst(() => ({ name: 'late first' }));
  [value] = s.h.render(() => s.context.useListingSnapshot('second'));
  assert.equal(value.name, 'second');
});

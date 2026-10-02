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
import { bookmarkHistory } from '../src/bookmarks/bookmark-history.ts';
import { bookmarkRequestKeyFor } from '../src/bookmarks/bookmark-api.ts';
import { recentHistory } from '../src/recent/recent-history.ts';
import { canonicalRecentState, recentRequestKeyFor } from '../src/recent/recent-api.ts';

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
const selectionCode = await compile('shared/browser/useItemSelection.ts');
const entrySelectionCode = await compile('shared/browser/useEntrySelection.ts');
const shortcutCode = await compile('shared/browser/useSelectionShortcuts.ts');
const bookmarksCode = await compile('bookmarks/BookmarksApp.tsx');
const bookmarkActionsCode = await compile('bookmarks/useBookmarkActions.ts');
const recentCode = await compile('recent/RecentApp.tsx');
const recentActionsCode = await compile('recent/recent-actions.ts');

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

// Actual page, Router, selection and shortcut hooks; leaf components and network are controlled.
function setupSelectablePage(t, kind) {
  const h = hooks(), pending = [], posts = [], confirmations = [], listeners = new Map(), scrolls = [];
  const config = kind === 'bookmarks' ? bookmarkHistory : recentHistory;
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: [config.pathname] });
  const window = { scrollY: 0, clearTimeout, setTimeout,
    scrollTo({ top }) { this.scrollY = top; scrolls.push(top); },
    EnderVault: { askConfirmation(options) {
      return new Promise((resolve) => confirmations.push({ options, resolve }));
    } },
  };
  const body = { closest: () => null };
  const scope = { contains: () => true };
  const document = { body, documentElement: body, activeElement: body, visibilityState: 'visible',
    dispatchEvent() {}, querySelector: (selector) => selector === '.app-main' ? scope : null,
    addEventListener(type, handler) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type).add(handler);
    },
    removeEventListener: (type, handler) => listeners.get(type)?.delete(handler),
  };
  const history = createListingHistory(router, () => window.scrollY, () => ({ getItem: () => null, setItem() {} }));
  const unsubscribe = router.subscribe(h.changed);
  h.react.useContext = () => history;
  const routeNavigate = router.navigate.bind(router);
  const jsx = (type, props) => ({ type, props });
  h.react.createElement = (type, props, ...children) => jsx(type, { ...props, children });
  const modules = {
    react: h.react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'Fragment' },
    'react-router-dom': { useLocation: () => router.state.location, useNavigate: () => routeNavigate, Link: 'Link' },
    './scroll-restoration': { createScrollRestoration },
    './bookmark-history': { bookmarkHistory }, './recent-history': { recentHistory },
    './bookmark-api': { bookmarkRequestKeyFor, loadBookmarks: request },
    './recent-api': { canonicalRecentState, recentRequestKeyFor, loadRecentPayload: request },
    '../shared/browser/useListingRefresh': { useListingRefresh: (callback) => { refresh = callback; } },
    '../shared/browser/BrowserEntries': { EntryTable: 'EntryTable', EntryGrid: 'EntryGrid', icon: (name) => name },
    '../shared/browser/BrowserPagination': { BrowserPagination: 'BrowserPagination' },
    '../shared/browser/ViewOptionsControl': { ViewOptionsControl: 'ViewOptionsControl' },
    '../shared/layout/LoadingState': { LoadingState: 'LoadingState' },
    '../shared/layout/PageErrorPanel': { PageErrorPanel: 'PageErrorPanel' },
    '../shared/browser/file-entry-actions': { createFileEntryActions: () => ({}) },
    '../shared/browser/file-entry-menu-actions': { fileEntryMenuActions: () => [] },
    '../shared/browser/useBrowserContextMenu': { useBrowserContextMenu() {} },
    './useBookmarkContextMenu': { useBookmarkContextMenu() {} },
    './BookmarkEntries': { BookmarkTable: 'BookmarkTable', BookmarkBreadcrumbs: 'BookmarkBreadcrumbs' },
    './BookmarkDialogs': { BookmarkDialogs: 'BookmarkDialogs' },
    '../app/AdminAppContext': { useAdminApp: () => ({ bootstrap: { capabilities: {} } }) },
    '../app/RouteSearch': { useRouteSearch: (options) => { search = options; } },
    '../app/FloatingPageActions': { FloatingPageActions: 'FloatingPageActions' },
    '../shared/api/favorite-api': { toggleBookmarkFavorite() {} },
    '../shared/api/form-api': { notify() {}, toastError() {},
      postForm: async (url, fields) => { posts.push({ url, fields }); return {}; } },
    './bookmarks-app.css': {}, './recent-app.css': {},
  };
  let search, refresh, tree;
  function request(state, signal) {
    return new Promise((resolve, reject) => pending.push({ state, signal, resolve, reject }));
  }
  const require = (id) => {
    assert.ok(id in modules, `unexpected import ${id}`);
    return modules[id];
  };
  const loadModule = (code) => load(code, require, window, document);
  modules['../api/form-api'] = modules['../shared/api/form-api'];
  modules['../shared/browser/ListingHistoryContext'] = loadModule(contextCode);
  modules['../shared/browser/useNavigationScroll'] = loadModule(scrollCode);
  modules['../shared/browser/useItemSelection'] = modules['./useItemSelection'] = loadModule(selectionCode);
  modules['../shared/browser/useEntrySelection'] = loadModule(entrySelectionCode);
  modules['../shared/browser/useSelectionShortcuts'] = loadModule(shortcutCode);
  modules['./useBookmarkActions'] = loadModule(bookmarkActionsCode);
  modules['./recent-actions'] = loadModule(recentActionsCode);
  const app = kind === 'bookmarks' ? loadModule(bookmarksCode).BookmarksApp : loadModule(recentCode).RecentApp;
  const render = () => (tree = h.render(app));
  function find(type, predicate = () => true) {
    const nodes = [];
    const visit = (node) => {
      if (Array.isArray(node)) node.forEach(visit);
      else if (node && typeof node === 'object') {
        if (node.type === type && predicate(node.props)) nodes.push(node.props);
        visit(node.props?.children);
      }
    };
    visit(tree);
    return nodes;
  }
  const tables = () => kind === 'bookmarks' ? find('BookmarkTable') : [...find('EntryTable'), ...find('EntryGrid')];
  const items = () => tables().flatMap((table) => table.entries);
  const identity = (entry) => kind === 'bookmarks' ? entry.id : entry.path;
  const rowClick = (entry, modifiers) => {
    const table = tables().find((table) => table.entries.includes(entry));
    table.itemInteractionProps(entry).onClickCapture({ target: body, ...modifiers,
      preventDefault() {}, stopPropagation() {} });
    render();
  };
  const key = (key, modifiers = {}) => {
    const event = { key, target: body, ...modifiers, preventDefault() { this.defaultPrevented = true; } };
    listeners.get('keydown')?.forEach((handler) => handler(event));
    render();
    return Boolean(event.defaultPrevented);
  };
  t.after(() => { h.dispose(); unsubscribe(); history.dispose(); router.dispose(); });
  return { router, window, scrolls, pending, posts, confirmations, render, find, tables, items, identity, rowClick, key,
    selected: () => [...tables()[0].selected], refresh: () => { refresh(); render(); },
    async finish(index = pending.length - 1, transform = (value) => value) {
      const state = pending[index].state;
      const directory = { id: 'directory-id', path: 'nested', title: 'Nested', name: 'Nested', type: 'directory' };
      const entries = ['a', 'b', 'c'].map((id) => ({ id, path: `nested/${id}.txt`, title: id, name: `${id}.txt`, type: 'file' }));
      const data = kind === 'bookmarks'
        ? { currentDirectoryId: state.directoryId || '', directories: [directory], links: entries,
          breadcrumbs: [], totalItems: 4, search: { query: state.query, performed: Boolean(state.query) } }
        : { ...payload({ ...state, sort: state.sort || 'recent', direction: state.direction || 'desc' }),
          directories: [directory], entries, totalItems: 4,
          page: { number: state.page, totalPages: 3, totalItems: 9, startItem: 1, endItem: 3 } };
      pending[index].resolve(transform(data));
      await flush(); render();
    },
    async search(query) {
      search.onChange(query); render();
      search.onSubmit({ preventDefault() {} }); render();
    },
  };
}

for (const kind of ['bookmarks', 'recent']) {
  test(`${kind} page wires current visible IDs, anchored ranges, Escape and domain Delete`, async (t) => {
    const s = setupSelectablePage(t, kind);
    s.render();
    assert.equal(s.key('a', { ctrlKey: true }), false, 'loading does not consume keys');
    await s.finish();
    assert.equal(s.key('a', { metaKey: true }), true);
    assert.deepEqual(s.selected(), s.items().map(s.identity));
    s.key('Escape');
    const entries = s.items();
    s.rowClick(entries[0], { ctrlKey: true });
    s.rowClick(entries[2], { shiftKey: true });
    assert.deepEqual(s.selected(), entries.slice(0, 3).map(s.identity));
    s.key('Delete');
    assert.equal(s.confirmations.length, 1);
    assert.equal(s.posts.length, 0);
    s.confirmations[0].resolve(true);
    await flush(); s.render();
    assert.equal(s.posts.length, 1);
    assert.equal(s.posts[0].url, kind === 'bookmarks' ? '/api/v1/bookmarks/delete-selected' : '/api/v1/recent/remove');
    assert.deepEqual([...s.posts[0].fields[kind === 'bookmarks' ? 'bookmarkIds' : 'paths']], entries.slice(0, 3).map(s.identity));
    assert.deepEqual(s.selected(), []);
  });

  test(`${kind} query changes reset both selection and the Shift anchor`, async (t) => {
    const s = setupSelectablePage(t, kind);
    s.render(); await s.finish();
    const changes = kind === 'bookmarks' ? [{ directoryId: 'another' }, { query: 'query' }]
      : [{ query: 'query' }, { page: 2 }, { sort: 'name' }, { direction: 'asc' }, { hidden: 'show' }, { pageSize: 100 }];
    for (const updates of changes) {
      s.rowClick(s.items().at(-1), { ctrlKey: true });
      const previous = s.pending.at(-1).state;
      const next = kind === 'recent' ? canonicalRecentState(previous, {
        search: { query: previous.query }, page: { number: previous.page },
        preferences: { sort: 'recent', direction: 'desc', hidden: 'hide', pageSize: 50 },
      }) : previous;
      historyNavigate(s, { ...next, ...updates });
      s.render();
      assert.equal(s.key('a', { ctrlKey: true }), false);
      await s.finish();
      assert.deepEqual(s.selected(), []);
      s.rowClick(s.items()[0], { shiftKey: true });
      assert.deepEqual(s.selected(), [s.identity(s.items()[0])], 'old anchor cannot select a previous range');
      s.key('Escape');
    }
  });

  test(`${kind} refresh keeps visible selection/anchor, prunes missing IDs and disables keys after failure`, async (t) => {
    const s = setupSelectablePage(t, kind);
    s.render(); await s.finish();
    s.rowClick(s.items()[1], { ctrlKey: true });
    s.refresh(); await s.finish();
    s.rowClick(s.items()[2], { shiftKey: true });
    assert.deepEqual(s.selected(), s.items().slice(1, 3).map(s.identity));
    s.refresh();
    await s.finish(undefined, (value) => ({ ...value,
      [kind === 'bookmarks' ? 'links' : 'entries']: (value.links || value.entries).slice(1),
    }));
    assert.deepEqual(s.selected(), [s.identity(s.items()[1])]);
    s.rowClick(s.items()[2], { shiftKey: true });
    assert.deepEqual(s.selected(), [s.identity(s.items()[2])], 'missing anchor was pruned');
    s.refresh();
    s.pending.at(-1).reject(new Error('offline'));
    await flush(); s.render();
    assert.equal(s.key('a', { ctrlKey: true }), false);
    assert.equal(s.key('Delete'), false);
    assert.equal(s.find('PageErrorPanel').length, 1);
    s.refresh(); await s.finish();
    assert.equal(s.key('a', { ctrlKey: true }), true);
  });

  test(`${kind} router departure during Delete confirmation prevents submission before unmount`, async (t) => {
    const s = setupSelectablePage(t, kind);
    s.render(); await s.finish(); s.key('a', { ctrlKey: true }); s.key('Delete');
    await s.router.navigate('/dashboard');
    s.confirmations[0].resolve(true);
    await flush();
    assert.equal(s.posts.length, 0);
  });
}

function historyNavigate(s, state) {
  s.router.navigate(s.router.state.location.pathname, { state: { listing: state } });
}

test('Recent view-only changes preserve visit, page, scroll, selection and anchor through preference persistence', async (t) => {
  const s = setupSelectablePage(t, 'recent');
  s.render(); await s.finish();
  s.find('BrowserPagination')[0].onPageChange(2); s.render(); await s.finish();
  s.window.scrollY = 600;
  s.rowClick(s.items()[1], { ctrlKey: true });
  const visit = s.router.state.location.key;
  s.find('button', (props) => props.title === 'Switch to grid view')[0].onClick(); s.render();
  assert.equal(s.router.state.location.key, visit);
  assert.equal(s.pending.at(-1).state.page, 2);
  assert.equal(s.pending.at(-1).state.view, 'grid');
  assert.equal(s.find('EntryGrid').length, 1);
  assert.equal(s.key('Delete'), false, 'refresh disables keyboard mutations');
  await s.finish();
  assert.equal(s.window.scrollY, 600);
  assert.deepEqual(s.selected(), [s.identity(s.items()[1])]);
  s.rowClick(s.items()[2], { shiftKey: true });
  assert.deepEqual(s.selected(), s.items().slice(1, 3).map(s.identity));
  s.find('button', (props) => props.title === 'Switch to table view')[0].onClick(); s.render();
  const superseded = s.pending.at(-1);
  s.find('button', (props) => props.title === 'Switch to grid view')[0].onClick(); s.render();
  assert.equal(superseded.signal.aborted, true);
  await s.finish();
  superseded.resolve({ preferences: { view: 'table' } });
  await flush(); s.render();
  assert.equal(s.find('EntryGrid').length, 1);
  s.refresh();
  assert.equal(s.pending.at(-1).state.view, 'grid');
  await s.finish();
  assert.equal(s.window.scrollY, 600);
  assert.equal(s.router.state.location.key, visit);
});

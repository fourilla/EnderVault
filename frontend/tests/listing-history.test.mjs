import assert from 'node:assert/strict';
import test from 'node:test';
import { createMemoryRouter } from 'react-router-dom';
import { createListingHistory } from '../src/shared/browser/listing-history.ts';
import { browserHistory, defaultBrowserState, parseBrowserState } from '../src/files/browser-history.ts';
import { recentHistory, defaultRecentState, parseRecentState } from '../src/recent/recent-history.ts';
import { bookmarkHistory, defaultBookmarkState, parseBookmarkState } from '../src/bookmarks/bookmark-history.ts';
import { comicPageIndex } from '../src/file-detail/comic-page.ts';

function setup(t, initialEntries = ['/files'], persisted = new Map()) {
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries });
  let top = 0;
  const storage = { getItem: (key) => persisted.get(key), setItem: (key, value) => persisted.set(key, value) };
  const history = createListingHistory(router, () => top, () => storage);
  t.after(() => { history.dispose(); router.dispose(); });
  return {
    router, history, persisted, storage,
    scroll(value) { top = value; },
    enter(config, ready = true) {
      const location = router.state.location;
      const state = history.read(config, location);
      history.activate(location, state);
      if (ready) history.ready(location);
      return { location, state };
    },
    go(config, state, replace = false) {
      return router.navigate(config.pathname, { state: { listing: state }, replace, preventScrollReset: true });
    },
  };
}

test('Recent -> Details -> browser Back restores Recent, not the file parent; Forward still works', async (t) => {
  const s = setup(t);
  s.enter(browserHistory);
  await s.router.navigate('/files/recent');
  const recent = s.enter(recentHistory);
  s.history.remember(recent.location, { ...recent.state, view: 'grid', pageSize: 50 });
  s.scroll(640);
  await s.router.navigate('/files/detail?path=books%2Fa.txt');
  const detailKey = s.router.state.location.key;
  s.scroll(0); // The incoming page may reset the viewport only after Router notifies.
  await s.router.navigate(-1);
  assert.equal(s.router.state.location.pathname, '/files/recent');
  assert.equal(s.router.state.location.key, recent.location.key);
  const restored = s.enter(recentHistory).state;
  assert.equal(restored.scrollTop, 640);
  assert.equal(restored.view, 'grid');
  assert.equal(restored.pageSize, 50);
  await s.router.navigate(1);
  assert.equal(s.router.state.location.key, detailKey);
  assert.equal(s.router.state.location.search, '?path=books%2Fa.txt');
});

test('filtered/paged Recent visits sharing one URL have independent state and scroll', async (t) => {
  const s = setup(t, ['/files/recent']);
  s.enter(recentHistory);
  s.scroll(80);
  await s.go(recentHistory, { ...defaultRecentState(), query: 'report', page: 3, sort: 'name', hidden: 'show' });
  const filtered = s.enter(recentHistory);
  s.scroll(1200);
  await s.router.navigate('/files/detail?path=nested%2Freport.txt');
  await s.router.navigate(-1);
  const restored = s.enter(recentHistory);
  assert.equal(restored.location.key, filtered.location.key);
  assert.deepEqual(restored.state, { ...filtered.state, scrollTop: 1200 });
  s.scroll(1200);
  await s.router.navigate(-1);
  const initial = s.enter(recentHistory).state;
  assert.equal(initial.query, '');
  assert.equal(initial.page, 1);
  assert.equal(initial.scrollTop, 80);
  await s.router.navigate(1);
  assert.equal(s.enter(recentHistory).state.query, 'report');
});

test('Files search and Bookmarks directories retain their own origin through detail navigation', async (t) => {
  for (const [config, state, detail] of [
    [browserHistory, { ...defaultBrowserState(), mode: 'search', path: 'nested', query: 'q', page: 2 }, '/files/detail?path=nested/a.txt'],
    [bookmarkHistory, { ...defaultBookmarkState(), directoryId: 'uuid-directory', query: 'docs' }, '/files/bookmarks/uuid-link'],
  ]) {
    const s = setup(t, [config.pathname]);
    await s.go(config, state);
    const visit = s.enter(config);
    s.scroll(910);
    await s.router.navigate(detail);
    await s.router.navigate(-1);
    assert.equal(s.router.state.location.pathname, config.pathname);
    assert.deepEqual(s.enter(config).state, { ...visit.state, scrollTop: 910 });
  }
});

test('surface discriminators reject another list state even when version and fields overlap', () => {
  assert.equal(parseBrowserState(defaultRecentState()), null);
  assert.equal(parseBrowserState(defaultBookmarkState()), null);
  assert.equal(parseRecentState(defaultBrowserState()), null);
  assert.equal(parseBookmarkState(defaultRecentState()), null);
  assert.equal(parseBrowserState({ version: 1, path: 'old' }), null);
});

test('direct query entry is read once; a new sidebar visit does not inherit an old location', async (t) => {
  const s = setup(t, ['/files?path=parent%2Fchild&page=2&hidden=show']);
  const visit = s.enter(browserHistory);
  assert.equal(visit.state.path, 'parent/child');
  assert.equal(visit.state.page, 2);
  assert.equal(visit.state.hidden, 'show');
  await s.router.navigate('/files/recent?q=docs&page=4');
  assert.equal(s.enter(recentHistory).state.page, 4);
  await s.router.navigate('/files');
  assert.equal(s.enter(browserHistory).state.path, '');
  await s.router.navigate(-1);
  assert.equal(s.enter(recentHistory).state.query, 'docs');
  await s.router.navigate(-1);
  assert.equal(s.enter(browserHistory).state.path, 'parent/child');
});

test('refresh and canonicalization never replace Router keys, state, URLs, or stack entries', async (t) => {
  const routeState = { otherFeature: 'preserved', listing: defaultRecentState() };
  const s = setup(t, ['/files', { pathname: '/files/recent', state: routeState, key: 'recent-key' }]);
  const visit = s.enter(recentHistory);
  let transitions = 0;
  const stop = s.router.subscribe(() => transitions++);
  s.scroll(600);
  s.history.remember(visit.location, { ...visit.state, page: 2, view: 'table' });
  s.scroll(850);
  s.history.remember(visit.location, { ...visit.state, page: 2, view: 'grid' });
  assert.equal(s.router.state.location, visit.location);
  assert.equal(s.router.state.location.state, routeState);
  assert.equal(transitions, 0);
  stop();
  await s.router.navigate('/files/detail?path=a.txt');
  await s.router.navigate(-1);
  const restored = s.enter(recentHistory).state;
  assert.equal(restored.scrollTop, 850);
  assert.equal(restored.view, 'grid');
  await s.router.navigate(-1);
  assert.equal(s.router.state.location.pathname, '/files');
});

test('late data and activation from a departed visit cannot overwrite the new route or saved origin', async (t) => {
  const s = setup(t, ['/files/recent']);
  const old = s.enter(recentHistory);
  s.scroll(500);
  await s.router.navigate('/files?path=other');
  const next = s.enter(browserHistory);
  s.history.activate(old.location, { ...old.state, query: 'late' });
  s.history.remember(old.location, { ...old.state, query: 'late' });
  s.history.deactivate(old.location);
  assert.equal(s.history.isCurrent(old.location), false);
  assert.equal(s.history.isCurrent(next.location), true);
  s.scroll(300);
  await s.router.navigate('/admin/settings');
  await s.router.navigate(-1);
  assert.equal(s.enter(browserHistory).state.scrollTop, 300);
  await s.router.navigate(-1);
  const restored = s.enter(recentHistory).state;
  assert.equal(restored.query, '');
  assert.equal(restored.scrollTop, 500);
});

test('leaving before rows load does not replace a pending restored scroll with zero', async (t) => {
  const s = setup(t, [{ pathname: '/files/recent', state: { listing: { ...defaultRecentState(), scrollTop: 700 } } }]);
  s.enter(recentHistory, false);
  s.scroll(0);
  await s.router.navigate('/admin/settings');
  await s.router.navigate(-1);
  assert.equal(s.enter(recentHistory, false).state.scrollTop, 700);
});

test('pagehide/reload restores the exact visit, not the last state of another visit', async (t) => {
  const s = setup(t, ['/files/recent']);
  const visit = s.enter(recentHistory);
  s.history.remember(visit.location, { ...visit.state, query: 'saved', page: 4 });
  s.scroll(765);
  s.history.capture();
  const reloaded = setup(t, [visit.location], s.persisted);
  const restored = reloaded.enter(recentHistory).state;
  assert.equal(restored.query, 'saved');
  assert.equal(restored.page, 4);
  assert.equal(restored.scrollTop, 765);
  await reloaded.router.navigate('/files/recent');
  assert.equal(reloaded.enter(recentHistory).state.query, '');
});

test('same-URL replacement gets a fresh visit and remains navigable without duplicate Back steps', async (t) => {
  const s = setup(t, ['/admin/dashboard', '/files']);
  const old = s.enter(browserHistory);
  await s.go(browserHistory, { ...old.state, path: 'child' }, true);
  const next = s.enter(browserHistory);
  assert.notEqual(next.location.key, old.location.key);
  assert.equal(next.state.path, 'child');
  await s.router.navigate(-1);
  assert.equal(s.router.state.location.pathname, '/admin/dashboard');
});

test('a fresh document entry cannot inherit the previous document default key snapshot', (t) => {
  const first = setup(t, ['/files']);
  const visit = first.enter(browserHistory);
  first.history.remember(visit.location, { ...visit.state, path: 'previous-document' });
  first.scroll(800);
  first.history.capture();
  const next = setup(t, ['/files'], first.persisted);
  assert.equal(next.enter(browserHistory).state.path, '');
  assert.notEqual(next.router.state.location.key, visit.location.key);
});

test('blocked or corrupted session storage does not break navigation', async (t) => {
  const s = setup(t, ['/files/recent']);
  const broken = createListingHistory(s.router, () => 400, () => { throw new Error('blocked'); });
  t.after(broken.dispose);
  const visit = s.router.state.location;
  broken.activate(visit, defaultRecentState());
  broken.ready(visit);
  await s.router.navigate('/files/detail?path=a.txt');
  await s.router.navigate(-1);
  assert.equal(broken.read(recentHistory, s.router.state.location).scrollTop, 400);
  const corrupt = createListingHistory(s.router, () => 0, () => ({ getItem: () => '{broken', setItem() {} }));
  t.after(corrupt.dispose);
  assert.equal(corrupt.read(recentHistory, visit).surface, 'recent');
});

test('compact snapshot persistence is bounded to 100 visits', async (t) => {
  const s = setup(t);
  for (let index = 0; index < 105; index++) {
    await s.go(browserHistory, { ...defaultBrowserState(), path: String(index) });
    s.enter(browserHistory);
  }
  const records = JSON.parse([...s.persisted.values()][0]);
  assert.equal(records.length, 100);
  assert.equal(records.at(-1)[1].path, '104');
  assert.equal(records.at(-1)[1].entries, undefined);
});

test('comic view page comes from Router search and is safely bounded', () => {
  assert.equal(comicPageIndex('?comicPage=3', 20), 2);
  assert.equal(comicPageIndex('?comicPage=900', 20), 19);
  assert.equal(comicPageIndex('?comicPage=-1', 20), 0);
  assert.equal(comicPageIndex('?comicPage=bad', 20, 2), 2);
  assert.equal(comicPageIndex('', 20, 4), 4);
  assert.equal(comicPageIndex('?comicPage=1', 0), 0);
});

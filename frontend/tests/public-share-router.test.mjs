import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { createBrowserRouter, createMemoryRouter, matchRoutes } from 'react-router-dom';
import { createListingHistory } from '../src/shared/browser/listing-history.ts';
import { PublicShareError, requestSharedView, sharedViewUrl } from '../src/public-share/public-share-request.ts';

const built = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/public-share/public-share-router.ts', import.meta.url)), formats: ['cjs'] },
  rolldownOptions: { external: (_id, importer) => Boolean(importer) },
} });
const code = (Array.isArray(built) ? built[0] : built).output.find(item => item.type === 'chunk').code;
const module = { exports: {} };
vm.runInNewContext(code, { module, exports: module.exports, URL, Math, require(id) {
  if (id === 'react-router-dom') return { matchRoutes };
  assert.equal(id, './public-share-request');
  return { PublicShareError, requestSharedView, sharedViewUrl };
} });
const { createPublicShareRoutes, initializeSharedHistoryKey, sharedRouteToken } = module.exports;
const bootstrap = { token: 'token', targetType: 'DIRECTORY', rootUrl: '/s/token' };
const listing = path => ({ targetType: 'DIRECTORY', view: 'listing', path, rootUrl: bootstrap.rootUrl, entries: [] });
const detail = path => ({ targetType: 'DIRECTORY', view: 'detail', path, rootUrl: bootstrap.rootUrl, name: 'file.txt' });
const tick = () => new Promise(setImmediate);
const snapshot = router => router.state.loaderData[router.state.matches.at(-1).route.id];
const address = location => `${location.pathname}${location.search}`;

function waitFor(router, predicate) {
  if (predicate(router.state)) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { unsubscribe(); reject(new Error('Router state did not settle.')); }, 3000);
    const unsubscribe = router.subscribe(state => {
      if (predicate(state)) { clearTimeout(timeout); unsubscribe(); resolve(); }
    });
  });
}

function requestQueue() {
  const requests = [];
  const request = (url, signal) => new Promise((resolve, reject) => requests.push({ url, signal, resolve, reject }));
  return { requests, request };
}

function setup(t, options = {}, share = bootstrap) {
  const queue = requestQueue();
  const routes = createPublicShareRoutes(share, 'public-view', 'initial-loading', queue.request);
  const router = createMemoryRouter(routes, { initialEntries: [share.rootUrl], ...options });
  t.after(() => router.dispose());
  return { ...queue, router, routes };
}

async function initialize(s, data = listing('')) {
  await tick();
  assert.equal(s.requests.length, 1);
  s.requests[0].resolve(data);
  await waitFor(s.router, state => state.initialized && state.navigation.state === 'idle');
}

test('public routes share one loader, opt into every navigation and reserve fallback for initial loading', async t => {
  const s = setup(t);
  assert.deepEqual(Array.from(s.routes, route => route.path), ['/s/:token', '/s/:token/file']);
  assert.equal(s.routes[0].loader, s.routes[1].loader);
  for (const route of s.routes) {
    assert.equal(route.element, 'public-view');
    assert.equal(route.hydrateFallbackElement, 'initial-loading');
    assert.equal(route.shouldRevalidate({ defaultShouldRevalidate: false }), true);
  }
  assert.equal(s.router.state.initialized, false);
  await initialize(s);
  await tick();
  assert.equal(s.requests.length, 1);
  assert.equal(s.requests[0].url, '/s/token/listing.json');
  assert.equal(snapshot(s.router).data.path, '');
});

test('pending directory navigation keeps the committed URL and data until one atomic completion', async t => {
  const s = setup(t);
  await initialize(s, listing('A'));
  const initialKey = s.router.state.location.key;
  const observed = [];
  const unsubscribe = s.router.subscribe(state => observed.push({
    address: address(state.location), path: snapshot(s.router)?.data?.path,
  }));
  t.after(unsubscribe);
  const navigation = s.router.navigate('/s/token?path=B');
  await tick();
  assert.equal(s.router.state.navigation.state, 'loading');
  assert.equal(address(s.router.state.navigation.location), '/s/token?path=B');
  assert.equal(address(s.router.state.location), '/s/token');
  assert.equal(s.router.state.location.key, initialKey);
  assert.equal(snapshot(s.router).data.path, 'A');
  s.requests[1].resolve(listing('B'));
  await navigation;
  assert.equal(address(s.router.state.location), '/s/token?path=B');
  assert.equal(snapshot(s.router).data.path, 'B');
  assert.ok(observed.every(state => state.address === '/s/token' ? state.path === 'A' : state.path === 'B'));
});

test('Back during another pending navigation revalidates the saved visit before showing its rows', async t => {
  const s = setup(t);
  await initialize(s, listing('A'));
  const keyA = s.router.state.location.key;
  const toB = s.router.navigate('/s/token?path=B');
  await tick(); s.requests[1].resolve(listing('B')); await toB;
  const toC = s.router.navigate('/s/token?path=C');
  await tick();
  const back = s.router.navigate(-1);
  await tick();
  assert.equal(s.requests[2].signal.aborted, true);
  assert.equal(s.requests[3].url, '/s/token/listing.json');
  assert.equal(address(s.router.state.location), '/s/token?path=B');
  assert.equal(snapshot(s.router).data.path, 'B');
  assert.equal(s.router.state.navigation.location.key, keyA);
  s.requests[3].reject(new PublicShareError('This shared item is unavailable.', 404));
  await back;
  await waitFor(s.router, state => state.navigation.state === 'idle');
  assert.equal(s.router.state.location.key, keyA);
  assert.equal(snapshot(s.router).data, undefined);
  assert.equal(snapshot(s.router).error, 'This shared item is unavailable.');
  s.requests[2].resolve(listing('C')); await toC; await tick();
  assert.equal(snapshot(s.router).error, 'This shared item is unavailable.');
});

for (const late of ['success', 'error']) {
  test(`an aborted request's late ${late} cannot replace the latest completed view`, async t => {
    const s = setup(t);
    await initialize(s, listing('A'));
    const toB = s.router.navigate('/s/token?path=B');
    await tick();
    const toC = s.router.navigate('/s/token?path=C');
    await tick();
    assert.equal(s.requests[1].signal.aborted, true);
    assert.equal(s.requests[2].signal.aborted, false);
    s.requests[2].resolve(listing('C')); await toC;
    if (late === 'success') s.requests[1].resolve(listing('B'));
    else s.requests[1].reject(new PublicShareError('This shared item is unavailable.', 404));
    await toB; await tick();
    assert.equal(address(s.router.state.location), '/s/token?path=C');
    assert.equal(snapshot(s.router).data.path, 'C');
    assert.equal(s.router.state.errors, null);
  });
}

test('a loader never requests an already aborted navigation and propagates later aborts', async t => {
  const s = setup(t);
  await initialize(s);
  const loader = s.routes[0].loader;
  const before = new AbortController(); before.abort();
  await assert.rejects(loader({ params: { token: 'token' }, request: new Request('http://nas.test/s/token', {
    signal: before.signal,
  }) }), error => error.name === 'AbortError');
  assert.equal(s.requests.length, 1);
  const during = new AbortController();
  const pending = loader({ params: { token: 'token' }, request: new Request('http://nas.test/s/token', {
    signal: during.signal,
  }) });
  const aborted = assert.rejects(pending, error => error.name === 'AbortError');
  during.abort(); s.requests[1].resolve(listing('stale'));
  await aborted;
});

test('disposing the Router aborts its pending public request', async t => {
  const s = setup(t);
  await tick();
  s.router.dispose();
  assert.equal(s.requests[0].signal.aborted, true);
  s.requests[0].resolve(listing('late')); await tick();
  assert.equal(s.router.state.initialized, false);
  assert.equal(snapshot(s.router), undefined);
});

test('listing and detail routes commit together while retaining reserved path and item characters', async t => {
  const s = setup(t);
  await initialize(s);
  const path = '한글/+%; &,#= folder', item = '한글+%; &,?#=.txt';
  const search = `?${new URLSearchParams({ path, item, ignored: 'x' })}`;
  const toDetail = s.router.navigate(`/s/token/file${search}`);
  await tick();
  const detailUrl = new URL(s.requests[1].url, 'http://nas.test');
  assert.equal(detailUrl.pathname, '/s/token/detail.json');
  assert.deepEqual([...detailUrl.searchParams], [['path', path], ['item', item]]);
  assert.equal(snapshot(s.router).data.view, 'listing');
  s.requests[1].resolve(detail(path)); await toDetail;
  assert.equal(address(s.router.state.location), `/s/token/file${search}`);
  assert.equal(snapshot(s.router).data.view, 'detail');
  const toListing = s.router.navigate(`/s/token${search}`);
  await tick();
  assert.deepEqual([...new URL(s.requests[2].url, 'http://nas.test').searchParams], [['path', path]]);
  assert.equal(snapshot(s.router).data.view, 'detail');
  s.requests[2].resolve(listing(path)); await toListing;
  assert.equal(snapshot(s.router).data.view, 'listing');
});

test('FILE shares load their detail at the root without directory query parameters', async t => {
  const s = setup(t, { initialEntries: ['/s/token?path=ignored&item=ignored'] }, { ...bootstrap, targetType: 'FILE' });
  await initialize(s, { ...detail(''), targetType: 'FILE' });
  assert.equal(s.requests[0].url, '/s/token/detail.json');
  assert.equal(snapshot(s.router).data.targetType, 'FILE');
});

test('trailing slash and encoded root routes keep their decoded token and outgoing data while pending', async t => {
  const s = setup(t);
  await initialize(s, listing('original'));
  for (const url of ['/s/%74oken/?path=a%2Bb', '/S/token/?path=next']) {
    assert.equal(sharedRouteToken(new URL(url, 'http://nas.test').pathname), bootstrap.token);
    const previousLocation = s.router.state.location;
    const previousData = snapshot(s.router).data;
    const navigation = s.router.navigate(url);
    await tick();
    assert.equal(s.router.state.location, previousLocation);
    assert.equal(snapshot(s.router).data, previousData);
    const request = s.requests.at(-1);
    const path = new URL(url, 'http://nas.test').searchParams.get('path');
    assert.equal(request.url, `/s/token/listing.json?${new URLSearchParams({ path })}`);
    request.resolve(listing(path)); await navigation;
    assert.equal(address(s.router.state.location), url);
    assert.equal(snapshot(s.router).data.path, path);
  }
  assert.equal(sharedRouteToken('/s/another-token/'), 'another-token');
  assert.equal(sharedRouteToken('/s/token/unrecognized'), undefined);
});

test('trailing and uppercase detail routes use the canonical detail API without replacing the pending view', async t => {
  const s = setup(t);
  await initialize(s);
  for (const url of ['/s/token/file/?item=first%2B.txt', '/S/%74oken/FILE?item=second.txt']) {
    assert.equal(sharedRouteToken(new URL(url, 'http://nas.test').pathname), bootstrap.token);
    const previousLocation = s.router.state.location;
    const previousData = snapshot(s.router).data;
    const navigation = s.router.navigate(url);
    await tick();
    assert.equal(s.router.state.location, previousLocation);
    assert.equal(snapshot(s.router).data, previousData);
    const request = s.requests.at(-1);
    const item = new URL(url, 'http://nas.test').searchParams.get('item');
    assert.equal(request.url, `/s/token/detail.json?${new URLSearchParams({ item })}`);
    request.resolve(detail(item)); await navigation;
    assert.equal(address(s.router.state.location), url);
    assert.equal(snapshot(s.router).data.view, 'detail');
    assert.equal(snapshot(s.router).data.path, item);
  }
});

test('re-entering the identical public URL always performs another server validation', async t => {
  const s = setup(t, { initialEntries: ['/s/token?path=A'] });
  await initialize(s, listing('A'));
  const repeat = s.router.navigate('/s/token?path=A');
  await tick();
  assert.equal(s.requests.length, 2);
  assert.equal(snapshot(s.router).data.path, 'A');
  s.requests[1].resolve(listing('fresh A')); await repeat;
  assert.equal(snapshot(s.router).data.path, 'fresh A');
});

test('Forward revalidates rather than restoring a previously successful detail payload', async t => {
  const s = setup(t);
  await initialize(s);
  const toDetail = s.router.navigate('/s/token/file?item=file.txt');
  await tick(); s.requests[1].resolve(detail('file.txt')); await toDetail;
  const back = s.router.navigate(-1); await tick();
  s.requests[2].resolve(listing('')); await back;
  await waitFor(s.router, state => state.navigation.state === 'idle');
  const forward = s.router.navigate(1); await tick();
  assert.equal(s.requests[3].url, '/s/token/detail.json?item=file.txt');
  assert.equal(snapshot(s.router).data.view, 'listing');
  s.requests[3].reject(new PublicShareError('This shared item is unavailable.', 403));
  await forward;
  await waitFor(s.router, state => state.navigation.state === 'idle');
  assert.equal(snapshot(s.router).data, undefined);
  assert.equal(snapshot(s.router).error, 'This shared item is unavailable.');
});

test('a different token becomes unavailable without using the bootstrap token API', async t => {
  const s = setup(t);
  await initialize(s);
  await s.router.navigate('/s/another-token?path=private');
  assert.equal(s.requests.length, 1);
  assert.equal(snapshot(s.router).data, undefined);
  assert.equal(snapshot(s.router).error, 'This shared item is unavailable.');
  const back = s.router.navigate('/s/token');
  await tick();
  s.requests[1].resolve(listing('')); await back;
  assert.equal(snapshot(s.router).data.view, 'listing');
});

for (const [error, expected] of [
  [new PublicShareError('The shared request is invalid.', 400), 'The shared request is invalid.'],
  [new PublicShareError('This shared item is unavailable.', 403), 'This shared item is unavailable.'],
  [new PublicShareError('This shared item is unavailable.', 404), 'This shared item is unavailable.'],
  [new Error('private server details'), 'The shared item could not be loaded. Try again.'],
]) {
  test(`a loader exposes only the public error for ${error.status || 'an unexpected failure'}`, async t => {
    const s = setup(t);
    await tick(); s.requests[0].reject(error);
    await waitFor(s.router, state => state.initialized && state.navigation.state === 'idle');
    assert.equal(snapshot(s.router).error, expected);
    assert.equal(snapshot(s.router).data, undefined);
    assert.equal(s.router.state.errors, null);
  });
}

test('initial history keys preserve existing entry state and never change the document URL', () => {
  for (const key of [undefined, '', '   ', 'default', 23]) {
    const history = { state: { key, usr: { listing: 'saved' }, idx: 4, custom: 'retained' },
      calls: [], replaceState(...args) { this.calls.push(args); this.state = args[0]; } };
    initializeSharedHistoryKey(history);
    assert.equal(history.calls.length, 1);
    assert.equal(history.calls[0].length, 2);
    assert.equal(history.calls[0][1], '');
    assert.equal(history.state.usr.listing, 'saved');
    assert.equal(history.state.idx, 4);
    assert.equal(history.state.custom, 'retained');
    assert.match(history.state.key, /^[a-z0-9]+$/);
    assert.notEqual(history.state.key, 'default');
    initializeSharedHistoryKey(history);
    assert.equal(history.calls.length, 1);
  }
  const existing = { state: { key: 'existing-visit', usr: 'saved', idx: 2 }, replaceState() { assert.fail(); } };
  initializeSharedHistoryKey(existing);
  const fresh = { state: null, replaceState(state) { this.state = state; } };
  initializeSharedHistoryKey(fresh);
  assert.ok(fresh.state.key);
});

test('initial BrowserRouter loading stays single when compact history is installed afterward', async t => {
  const queue = requestQueue();
  const location = new URL('http://nas.test/s/token?path=a%2Bb');
  const listeners = new Map();
  const browserHistory = { state: { usr: { kept: true }, idx: 0 },
    replaceState(state, _title, url) {
      this.state = state;
      if (url !== undefined) location.href = new URL(url, location).href;
    },
    pushState() { assert.fail('Initial loading must not create another entry.'); },
  };
  const browserWindow = { location, history: browserHistory, document: { createElement() {} },
    sessionStorage: { getItem() {}, setItem() {} },
    addEventListener(name, listener) { listeners.set(name, listener); },
    removeEventListener(name) { listeners.delete(name); },
  };
  initializeSharedHistoryKey(browserHistory);
  const initialKey = browserHistory.state.key;
  const router = createBrowserRouter(createPublicShareRoutes(bootstrap, null, null, queue.request), {
    window: browserWindow, hydrationData: { loaderData: {} },
  });
  const history = createListingHistory(router, () => 0, () => ({ getItem() {}, setItem() {} }), 'public-initial-test');
  t.after(() => { history.dispose(); router.dispose(); });
  await tick();
  assert.equal(listeners.has('popstate'), true);
  assert.equal(listeners.has('pagehide'), true);
  assert.equal(queue.requests.length, 1);
  assert.equal(router.state.location.key, initialKey);
  assert.equal(router.state.location.state.kept, true);
  assert.equal(location.href, 'http://nas.test/s/token?path=a%2Bb');
  queue.requests[0].resolve(listing('a+b'));
  await waitFor(router, state => state.initialized && state.navigation.state === 'idle');
  await tick();
  assert.equal(queue.requests.length, 1);
  assert.equal(queue.requests[0].signal.aborted, false);
  assert.equal(snapshot(router).data.path, 'a+b');
});

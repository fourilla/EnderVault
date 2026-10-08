import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { createMemoryRouter } from 'react-router-dom';
import { createListingHistory } from '../src/shared/browser/listing-history.ts';
import { defaultSharedVisit, parseSharedVisit, sharedVisitConfig } from '../src/public-share/public-share-history.ts';
import { PublicShareError, requestSharedView, sharedViewUrl } from '../src/public-share/public-share-request.ts';

const bootstrap = { token: 'share-token', targetType: 'DIRECTORY', rootUrl: '/s/share-token' };
const response = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
});

test('public directory and detail queries preserve reserved characters and literal plus signs', () => {
  const path = '한글/+%; &,#= folder';
  const item = '한글+%; &,?#=.txt';
  const search = `?${new URLSearchParams({ path, item, ignored: 'x' })}`;
  const listing = new URL(sharedViewUrl(bootstrap, bootstrap.rootUrl, search), 'https://nas.test');
  const detail = new URL(sharedViewUrl(bootstrap, `${bootstrap.rootUrl}/file`, search), 'https://nas.test');
  assert.equal(listing.pathname, '/s/share-token/listing.json');
  assert.equal(listing.searchParams.get('path'), path);
  assert.equal(listing.searchParams.has('item'), false);
  assert.equal(detail.pathname, '/s/share-token/detail.json');
  assert.deepEqual([...detail.searchParams], [['path', path], ['item', item]]);
  assert.equal(sharedViewUrl({ ...bootstrap, targetType: 'FILE' }, bootstrap.rootUrl, search),
    '/s/share-token/detail.json');
});

test('public JSON requests omit credentials and carry cancellation without admin session or CSRF handling', async () => {
  const controller = new AbortController();
  const listing = { targetType: 'DIRECTORY', view: 'listing', path: '', rootUrl: bootstrap.rootUrl, entries: [] };
  const actual = await requestSharedView('/s/share-token/listing.json', controller.signal, async (url, options) => {
    assert.equal(url, '/s/share-token/listing.json');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.mode, 'same-origin');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.signal, controller.signal);
    assert.deepEqual(options.headers, { Accept: 'application/json' });
    return response(listing);
  });
  assert.deepEqual(actual, listing);
});

test('revoked, invalid and missing shares produce public errors without a login redirect message', async () => {
  for (const status of [400, 403, 404, 500]) {
    await assert.rejects(requestSharedView('/s/share-token/listing.json', new AbortController().signal,
      async () => response({ notification: { message: 'private internal path' } }, status)), error => {
      assert.ok(error instanceof PublicShareError);
      assert.equal(error.status, status);
      assert.doesNotMatch(error.message, /login|session|private internal/i);
      return true;
    });
  }
});

test('unexpected HTML and malformed view payloads are rejected rather than displayed as a shared list', async () => {
  const signal = new AbortController().signal;
  for (const result of [new Response('<html>login</html>'), response({}), response({
    targetType: 'DIRECTORY', view: 'listing', rootUrl: bootstrap.rootUrl, path: '', entries: null,
  })]) {
    await assert.rejects(requestSharedView('/s/share-token/listing.json', signal, async () => result), PublicShareError);
  }
});

test('public Back and Forward keep exact query URLs and restore selection and scroll only for that visit', async (t) => {
  const firstUrl = `${bootstrap.rootUrl}?${new URLSearchParams({ path: 'folder+%;&' })}`;
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: [firstUrl] });
  let top = 0;
  const saved = new Map();
  const storage = { getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value) };
  const history = createListingHistory(router, () => top, () => storage, 'public-test');
  t.after(() => { history.dispose(); router.dispose(); });
  const config = sharedVisitConfig(bootstrap.rootUrl);
  const visit = router.state.location;
  history.activate(visit, history.read(config, visit));
  history.ready(visit);
  history.remember(visit, { ...defaultSharedVisit(), selectedNames: ['a+%.txt'] });
  top = 850;
  await router.navigate(`${bootstrap.rootUrl}/file?${new URLSearchParams({ path: 'folder+%;&', item: 'a+%.txt' })}`);
  const detail = router.state.location;
  top = 0;
  await router.navigate(-1);
  assert.equal(`${router.state.location.pathname}${router.state.location.search}`, firstUrl);
  assert.deepEqual(history.read(config, router.state.location), {
    ...defaultSharedVisit(), selectedNames: ['a+%.txt'], scrollTop: 850,
  });
  await router.navigate(1);
  assert.equal(router.state.location.key, detail.key);
  assert.equal(router.state.location.search, detail.search);
  await router.navigate(`${bootstrap.rootUrl}?path=other`);
  assert.deepEqual(history.read(config, router.state.location), defaultSharedVisit());
  assert.deepEqual([...saved.keys()], ['public-test']);
});

test('public visit parsing excludes payloads and rejects admin history while preserving compact selection state', () => {
  assert.equal(parseSharedVisit({ surface: 'files', version: 1, selectedNames: ['a'] }), null);
  assert.deepEqual(parseSharedVisit({ ...defaultSharedVisit(), selectedNames: ['a', 'a', 12],
    scrollTop: -4, entries: ['private'], content: 'file bytes' }), {
    ...defaultSharedVisit(), selectedNames: ['a'],
  });
});

const built = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/shared/browser/ListingHistoryContext.tsx', import.meta.url)), formats: ['cjs'] },
  rolldownOptions: { external: (_id, importer) => Boolean(importer) },
} });
const hookCode = (Array.isArray(built) ? built[0] : built).output.find(item => item.type === 'chunk').code;

for (const preserveSearch of [false, true]) {
  test(`history hook ${preserveSearch ? 'preserves public' : 'canonicalizes admin'} queries`, () => {
    const location = { pathname: bootstrap.rootUrl, search: '?path=a%2Bb', hash: '', key: 'visit' };
    const effects = [], navigations = [], activations = [];
    const state = defaultSharedVisit();
    const history = { read: () => state, activate: (...args) => activations.push(args), deactivate() {},
      remember() {}, ready() {}, isCurrent: () => true };
    const module = { exports: {} };
    vm.runInNewContext(hookCode, { module, exports: module.exports, require: id => {
      if (id === 'react') return { createContext: () => ({}), useContext: () => history,
        useMemo: fn => fn(), useLayoutEffect: fn => effects.push(fn), useCallback: fn => fn };
      if (id === 'react-router-dom') return { useLocation: () => location,
        useNavigate: () => (...args) => navigations.push(args) };
      return {};
    } });
    const visit = module.exports.useListingHistory(sharedVisitConfig(location.pathname), { preserveSearch });
    effects.forEach(effect => effect());
    if (preserveSearch) {
      assert.equal(navigations.length, 0);
      assert.equal(activations.length, 1);
    } else {
      assert.equal(navigations.length, 1);
      assert.equal(navigations[0][0].search, undefined);
      assert.equal(navigations[0][1].replace, true);
      assert.equal(activations.length, 0);
    }
    visit.navigate(state);
    assert.equal(navigations.at(-1)[0].search, preserveSearch ? location.search : '');
  });
}

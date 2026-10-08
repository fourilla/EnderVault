import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createHookHarness } from './helpers/react-hooks.mjs';
import { defaultSharedVisit, sharedVisitConfig } from '../src/public-share/public-share-history.ts';
import { PublicShareError, sharedViewUrl } from '../src/public-share/public-share-request.ts';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const snapshotCode = await compile('shared/browser/ListingHistoryContext.tsx');
const appCode = await compile('public-share/PublicShareApp.tsx');

function load(code, modules, globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, ...globals, require(id) {
    assert.ok(id in modules, `Unexpected import: ${id}`); return modules[id];
  } });
  return module.exports;
}
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.props?.children)];
}
const tick = () => new Promise(setImmediate);

function setup() {
  const h = createHookHarness(), requests = [], initialState = defaultSharedVisit();
  const react = { ...h.react, createContext: () => ({}), useLayoutEffect: h.react.useEffect };
  let location = { pathname: '/s/token', search: '', key: 'A' };
  const router = { useLocation: () => location, useParams: () => ({ token: 'token' }), Link() {} };
  const { useListingSnapshot } = load(snapshotCode, { react, 'react/jsx-runtime': jsx, 'react-router-dom': router });
  function SharedDirectoryPage() {}
  function SharedFilePage() {}
  const document = { title: '' };
  const { PublicShareApp } = load(appCode, {
    react, 'react/jsx-runtime': jsx, 'react-router-dom': router,
    '../shared/browser/ListingHistoryContext': {
      useListingSnapshot,
      useListingHistory(_config, options) {
        assert.equal(options.preserveSearch, true);
        return { key: location.key, state: initialState, remember() {}, ready() {} };
      },
    },
    '../shared/browser/useNavigationScroll': { useNavigationScroll() {} },
    './SharedDirectoryPage': { SharedDirectoryPage }, './SharedFilePage': { SharedFilePage },
    './public-share-history': { sharedVisitConfig },
    './public-share-request': { PublicShareError, sharedViewUrl, requestSharedView(url, signal) {
      return new Promise((resolve, reject) => requests.push({ url, signal, resolve, reject }));
    } },
  }, { AbortController, document });
  return {
    requests, document,
    render() { return h.render(() => PublicShareApp({ bootstrap: {
      token: 'token', targetType: 'DIRECTORY', rootUrl: '/s/token',
    } })); },
    move(key, search) { location = { ...location, key, search }; },
    rows(tree) { return nodes(tree).filter(node => node.type === SharedDirectoryPage); },
    messages(tree) { return nodes(tree).filter(node => node.props?.role).map(node => node.props.children).flat(); },
    dispose: h.dispose,
  };
}
const listing = path => ({ targetType: 'DIRECTORY', view: 'listing', path, rootUrl: '/s/token', entries: [] });

test('a public visit loads once and departures abort it while late successes cannot replace the current view', async () => {
  const s = setup();
  try {
    s.render(); s.render();
    assert.equal(s.requests.length, 1);
    s.move('B', '?path=other');
    assert.equal(s.rows(s.render()).length, 0);
    assert.equal(s.requests[0].signal.aborted, true);
    s.requests[1].resolve(listing('other')); await tick();
    assert.equal(s.rows(s.render())[0].props.listing.path, 'other');
    s.requests[0].resolve(listing('')); await tick();
    assert.equal(s.rows(s.render())[0].props.listing.path, 'other');
    assert.equal(s.requests.length, 2);
  } finally { s.dispose(); }
});

test('Back before the intermediate request finishes revalidates without exposing cached rows on a revoked share', async () => {
  const s = setup();
  try {
    s.render(); s.requests[0].resolve(listing('')); await tick();
    assert.equal(s.rows(s.render()).length, 1);
    s.move('B', '?path=other'); s.render();
    s.move('A', '');
    const back = s.render();
    assert.equal(s.rows(back).length, 0);
    assert.ok(s.messages(back).includes('Loading shared item...'));
    assert.equal(s.requests[1].signal.aborted, true);
    s.requests[2].reject(new PublicShareError('This shared item is unavailable.', 404)); await tick();
    const blocked = s.render();
    assert.equal(s.rows(blocked).length, 0);
    assert.ok(nodes(blocked).some(node => node.props?.role === 'alert'));
    s.requests[1].resolve(listing('other')); await tick();
    assert.equal(s.rows(s.render()).length, 0);
  } finally { s.dispose(); }
});

test('departed errors cannot hide the next response and unmount aborts the current request', async () => {
  const s = setup();
  s.render(); s.move('B', '?path=other'); s.render();
  s.requests[0].reject(new PublicShareError('This shared item is unavailable.', 404)); await tick();
  assert.ok(s.messages(s.render()).includes('Loading shared item...'));
  s.requests[1].resolve(listing('other')); await tick();
  assert.equal(s.rows(s.render())[0].props.listing.path, 'other');
  s.dispose();
  assert.equal(s.requests[1].signal.aborted, true);
});

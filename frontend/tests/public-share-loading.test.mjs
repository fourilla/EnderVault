import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createMemoryRouter, matchRoutes } from 'react-router-dom';
import { createHookHarness } from './helpers/react-hooks.mjs';
import { createListingHistory } from '../src/shared/browser/listing-history.ts';
import { createScrollRestoration } from '../src/shared/browser/scroll-restoration.ts';
import { defaultSharedVisit, sharedVisitConfig } from '../src/public-share/public-share-history.ts';
import { PublicShareError, requestSharedView, sharedViewUrl } from '../src/public-share/public-share-request.ts';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
function load(code, modules, globals = {}) {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, ...globals, require(id) {
    assert.ok(id in modules, `Unexpected import: ${id}`); return modules[id];
  } });
  return module.exports;
}
const [historyCode, scrollCode, routerCode, appCode] = await Promise.all([
  'shared/browser/ListingHistoryContext.tsx', 'shared/browser/useNavigationScroll.ts',
  'public-share/public-share-router.ts', 'public-share/PublicShareApp.tsx',
].map(compile));
const publicRouting = load(routerCode, {
  './public-share-request': { PublicShareError, requestSharedView, sharedViewUrl },
  'react-router-dom': { matchRoutes },
}, { URL, Math });
const { createPublicShareRoutes } = publicRouting;
const nodes = tree => Array.isArray(tree) ? tree.flatMap(nodes) : !tree || typeof tree !== 'object' ? []
  : [tree, ...nodes(tree.props?.children)];
const tick = () => new Promise(setImmediate);
const bootstrap = { token: 'token', targetType: 'DIRECTORY', rootUrl: '/s/token' };
const listing = path => ({ targetType: 'DIRECTORY', view: 'listing', path, rootUrl: '/s/token', entries: [] });
const detail = name => ({ targetType: 'DIRECTORY', view: 'detail', path: name, rootUrl: '/s/token', name });

function setup(t) {
  const h = createHookHarness(), requests = [], scrolls = [], listeners = new Set();
  const react = { ...h.react, createContext: () => ({}), useContext: () => history,
    useLayoutEffect: h.react.useEffect };
  const router = createMemoryRouter(createPublicShareRoutes(bootstrap, null, null, (url, signal) =>
    new Promise((resolve, reject) => requests.push({ url, signal, resolve, reject }))),
  { initialEntries: [{ pathname: '/s/token', key: 'A' }] });
  let top = 0;
  const history = createListingHistory(router, () => top, () => ({ getItem: () => null, setItem() {} }));
  const hooks = {
    useLocation: () => router.state.location,
    useParams: () => router.state.matches.at(-1).params,
    useNavigation: () => router.state.navigation,
    useLoaderData: () => router.state.loaderData[router.state.matches.at(-1).route.id],
    useNavigate: () => router.navigate,
    Link() {},
  };
  const document = { title: '', addEventListener: (_name, listener) => listeners.add(listener),
    removeEventListener: (_name, listener) => listeners.delete(listener) };
  const window = { scrollTo: ({ top: next }) => { scrolls.push(next); top = next; } };
  const historyHooks = load(historyCode, { react, 'react/jsx-runtime': jsx, 'react-router-dom': hooks });
  const scrollHooks = load(scrollCode, { react, './scroll-restoration': { createScrollRestoration } }, { window });
  function SharedDirectoryPage() {}
  function SharedFilePage() {}
  function LoadingState() {}
  const { PublicShareApp, PublicShareLoading } = load(appCode, {
    react, 'react/jsx-runtime': jsx, 'react-router-dom': hooks,
    '../shared/browser/ListingHistoryContext': historyHooks,
    '../shared/browser/useNavigationScroll': scrollHooks,
    '../shared/layout/LoadingState': { LoadingState },
    './SharedDirectoryPage': { SharedDirectoryPage }, './SharedFilePage': { SharedFilePage },
    './public-share-history': { sharedVisitConfig },
    './public-share-router': publicRouting,
  }, { document, window });
  t.after(() => { h.dispose(); history.dispose(); router.dispose(); });
  return {
    router, requests, history, scrolls, document, listeners,
    render: () => h.render(() => PublicShareApp({ bootstrap })),
    initial: () => PublicShareLoading({ bootstrap }),
    views: tree => nodes(tree).filter(node => node.type === SharedDirectoryPage || node.type === SharedFilePage),
    shell: tree => nodes(tree).find(node => node.type?.name === 'SharedShell'),
    pending: tree => nodes(tree).find(node => node.props?.className === 'public-share-pending-overlay'),
    setTop: value => { top = value; },
  };
}
async function initialized(s) {
  s.requests[0].resolve(listing('')); await tick();
  assert.equal(s.router.state.initialized, true);
  return s.render();
}

test('pending navigation retains the committed page, key, selection and scroll until verified data replaces it', async t => {
  const s = setup(t);
  const initialShell = s.shell(s.initial());
  assert.equal(initialShell.props.busy, true);
  assert.equal(initialShell.type(initialShell.props).props.inert, false);
  let tree = await initialized(s);
  assert.deepEqual(s.scrolls, [0]);
  const first = s.views(tree)[0];
  const navigation = s.router.navigate('/s/token?path=other');
  tree = s.render();
  assert.equal(s.views(tree)[0].key, first.key);
  assert.equal(s.views(tree)[0].props.listing.path, '');
  assert.equal(s.views(tree)[0].props.disabled, true);
  assert.equal(s.shell(tree).type(s.shell(tree).props).props.inert, true);
  assert.ok(s.pending(tree));
  assert.deepEqual(s.scrolls, [0]);
  s.requests[1].resolve(listing('other')); await navigation;
  tree = s.render();
  assert.notEqual(s.views(tree)[0].key, first.key);
  assert.equal(s.views(tree)[0].props.listing.path, 'other');
  assert.equal(s.views(tree)[0].props.disabled, false);
  assert.equal(s.pending(tree), undefined);
  assert.equal(s.listeners.size, 0);
});

test('POP revalidates the target without showing its cached listing and preserves outgoing and target UI history', async t => {
  const s = setup(t);
  await initialized(s);
  s.history.remember(s.router.state.location, { ...defaultSharedVisit(), selectedNames: ['a.txt'] });
  s.setTop(850);
  const next = s.router.navigate('/s/token?path=other');
  s.requests[1].resolve(listing('other')); await next; s.render();
  s.setTop(420);
  const unfinished = s.router.navigate('/s/token?path=unfinished');
  s.render();
  const back = s.router.navigate(-1);
  let tree = s.render();
  assert.equal(s.requests[2].signal.aborted, true);
  assert.equal(s.views(tree)[0].props.listing.path, 'other');
  assert.deepEqual(s.scrolls, [0, 0]);
  s.requests[3].resolve(listing('fresh-root')); await back;
  tree = s.render();
  assert.equal(s.views(tree)[0].props.listing.path, 'fresh-root');
  assert.deepEqual(Array.from(s.views(tree)[0].props.initialSelectedNames), ['a.txt']);
  assert.deepEqual(s.scrolls, [0, 0, 850]);
  s.requests[2].resolve(listing('unfinished')); await unfinished; await tick();
  assert.equal(s.views(s.render())[0].props.listing.path, 'fresh-root');
  const forward = s.router.navigate(1);
  s.requests[4].resolve(listing('fresh-other')); await forward; s.render();
  assert.equal(s.scrolls.at(-1), 420);
});

test('revocation replaces the outgoing page with a public error and late departed errors cannot replace a newer view', async t => {
  const s = setup(t);
  await initialized(s);
  const abandoned = s.router.navigate('/s/token?path=abandoned'); s.render();
  const current = s.router.navigate('/s/token?path=current'); s.render();
  s.requests[2].resolve(listing('current')); await current; s.render();
  s.requests[1].reject(new PublicShareError('This shared item is unavailable.', 404)); await abandoned; await tick();
  assert.equal(s.views(s.render())[0].props.listing.path, 'current');
  const revoked = s.router.navigate('/s/token');
  assert.equal(s.views(s.render())[0].props.listing.path, 'current');
  s.requests[3].reject(new PublicShareError('This shared item is unavailable.', 404)); await revoked;
  const tree = s.render();
  assert.equal(s.views(tree).length, 0);
  assert.ok(nodes(tree).some(node => node.props?.role === 'alert'));
});

test('detail remains mounted while pending, then a committed visit replaces it and updates the title', async t => {
  const s = setup(t);
  await initialized(s);
  const next = s.router.navigate('/s/token/file?item=a.txt');
  assert.equal(s.views(s.render())[0].props.listing.path, '');
  s.requests[1].resolve(detail('a.txt')); await next;
  const first = s.views(s.render())[0];
  assert.equal(s.document.title, 'a.txt');
  const other = s.router.navigate('/s/token/file?item=b.txt');
  assert.equal(s.views(s.render())[0].key, first.key);
  assert.equal(s.document.title, 'a.txt');
  s.requests[2].resolve(detail('b.txt')); await other;
  assert.notEqual(s.views(s.render())[0].key, first.key);
  assert.equal(s.document.title, 'b.txt');
});

test('a different pending token hides previous content immediately and makes no request with the old bootstrap', async t => {
  const s = setup(t);
  await initialized(s);
  const navigation = s.router.navigate('/s/other-token');
  assert.equal(s.views(s.render()).length, 0);
  await navigation;
  assert.equal(s.views(s.render()).length, 0);
  assert.equal(s.requests.length, 1);
});

test('Router aliases for the same token keep the outgoing content visible during validation', async t => {
  const s = setup(t);
  await initialized(s);
  for (const url of ['/s/token/', '/s/%74oken/', '/s/token/FILE/?item=a.txt']) {
    const previous = s.views(s.render())[0];
    const navigation = s.router.navigate(url);
    const tree = s.render();
    assert.equal(s.views(tree)[0].key, previous.key);
    assert.ok(s.pending(tree));
    assert.equal(nodes(tree).some(node => node.props?.role === 'alert'), false);
    s.requests.at(-1).resolve(url.includes('FILE') ? detail('a.txt') : listing('alias'));
    await navigation; s.render();
  }
});

test('pending controls block background/menu/viewer interactions while native browser navigation stays available', async t => {
  const s = setup(t);
  await initialized(s);
  const navigation = s.router.navigate('/s/token?path=other');
  const overlay = s.pending(s.render());
  for (const eventName of ['onClickCapture', 'onContextMenuCapture', 'onMouseDownCapture']) {
    let prevented = false, stopped = false;
    overlay.props[eventName]({ preventDefault() { prevented = true; }, stopPropagation() { stopped = true; } });
    assert.equal(prevented, true); assert.equal(stopped, true);
  }
  for (const [extras, blocked] of [[{ key: 'Escape' }, true], [{ key: 'ArrowRight' }, true],
    [{ key: 'ArrowLeft', altKey: true }, false], [{ key: 'l', ctrlKey: true }, false], [{ key: 'Tab' }, false]]) {
    const event = { prevented: false, stopped: false, preventDefault() { this.prevented = true; },
      stopPropagation() { this.stopped = true; }, ...extras };
    s.listeners.forEach(listener => listener(event));
    assert.equal(event.prevented, blocked); assert.equal(event.stopped, blocked);
  }
  s.requests[1].resolve(listing('other')); await navigation; s.render();
  assert.equal(s.listeners.size, 0);
});

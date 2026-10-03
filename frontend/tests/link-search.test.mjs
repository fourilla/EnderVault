import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';
import { createMemoryRouter } from 'react-router-dom';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent',
    build: { write: false, minify: false,
      lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const domains = [
  { scope: 'shares', page: 'shares/SharedLinksApp.tsx', name: 'SharedLinksApp',
    api: 'shares/share-api.ts', load: 'loadShares' },
  { scope: 'file-requests', page: 'file-requests/FileRequestsApp.tsx', name: 'FileRequestsApp',
    api: 'file-requests/file-request-api.ts', load: 'loadFileRequests' },
];
for (const domain of domains) {
  domain.code = await compile(domain.page);
  domain.apiCode = await compile(domain.api);
}
const flush = () => new Promise(setImmediate);
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.props?.children)];
}
const share = id => ({ token: id, path: id, url: `/s/${id}`, type: 'FILE' });
const request = id => ({ id, title: id, destinationPath: 'photos', allowedExtensions: [],
  acceptedBytes: 0, maxTotalBytes: 1000, createdLabel: '2026-10-04', expiresLabel: 'Never' });
const payload = (domain, ids = []) => domain.scope === 'shares' ? ids.map(share) : {
  requests: ids.map(request), enabled: true, uploaderNamePolicies: [{ value: 'OPTIONAL', label: 'Optional' }],
  defaults: { title: 'Initial title', description: 'Initial description', destinationPath: 'photos',
    uploaderNamePolicy: 'OPTIONAL', expirationDays: 7, maxFileSizeGb: '1', maxTotalGb: '2', maxFiles: 10,
    allowedExtensions: '', duplicating: false },
};

// Deterministic page effects + actual Router history; no backend or browser layout is involved.
function harness(t, domain, search = '') {
  const route = `/admin/${domain.scope}`;
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: [route + search] });
  const slots = [], effects = [], requests = [], mutations = [];
  let cursor = 0, dirty, registration;
  const react = {
    useState(initial) {
      const index = cursor++;
      if (!slots[index]) slots[index] = { value: initial };
      return [slots[index].value, next => {
        const value = typeof next === 'function' ? next(slots[index].value) : next;
        if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
      }];
    },
    useRef(initial) { const index = cursor++; return slots[index] ??= { current: initial }; },
    useEffect(run, deps) {
      const index = cursor++, previous = slots[index];
      if (previous && deps.every((dep, i) => Object.is(dep, previous.deps[i]))) return;
      const slot = { deps, cleanup: previous?.cleanup };
      slots[index] = slot;
      effects.push(() => { slot.cleanup?.(); slot.cleanup = run(); });
    },
  };
  const module = { exports: {} };
  vm.runInNewContext(domain.code, { module, exports: module.exports, AbortController, URLSearchParams, Error,
    window: { EnderVault: { askConfirmation: async () => true } },
    require(id) {
      if (id === 'react') return react;
      if (id === 'react/jsx-runtime') return jsx;
      if (id === 'react-router-dom') return { Link() {}, useLocation: () => router.state.location,
        useNavigate: () => router.navigate,
        useSearchParams: () => [new URLSearchParams(router.state.location.search), next => router.navigate({
          pathname: route, search: new URLSearchParams(next).toString() })] };
      if (id.endsWith('/RouteSearch')) return { useRouteSearch: control => { registration = control; } };
      if (id.endsWith('/BrowserEntries')) return { icon: () => null };
      if (id.endsWith('/form-api')) return { toastError: reason => { throw reason; } };
      if (id.endsWith('/format-bytes')) return { formatBytes: String };
      if (id.endsWith('-api')) return new Proxy({}, { get(_target, name) {
        if (name === domain.load) return (...args) => new Promise((resolve, reject) => {
          const [signal, query] = domain.scope === 'shares' ? args : [args[1], new URLSearchParams(args[0]).get('q') ?? ''];
          requests.push({ signal, query, args, resolve, reject });
        });
        return async (...args) => { mutations.push({ name, args }); };
      } });
      const name = id.split('/').at(-1);
      return { [name]: { [name]: () => null }[name] };
    },
  });
  t.after(() => { slots.forEach(slot => slot.cleanup?.()); router.dispose(); });
  return { router, requests, mutations,
    get search() { return registration; },
    render() {
      let tree, count = 0;
      do {
        assert.ok(++count < 20, 'render/effect loop');
        dirty = false; cursor = 0; tree = module.exports[domain.name]();
        effects.splice(0).forEach(run => run());
      } while (dirty);
      return tree;
    },
    async finish(index = requests.length - 1, ids = []) {
      requests[index].resolve(payload(domain, ids)); await flush();
    },
  };
}
const errorPanels = tree => nodes(tree).filter(node => node.type?.name === 'PageErrorPanel');
const rowKeys = tree => nodes(tree).filter(node => node.type === 'tr' && node.key).map(node => node.key);

for (const domain of domains) {
  test(`${domain.scope} submits only on command, preserves other URL fields and restores history`, async t => {
    const h = harness(t, domain, '?q=name:old&destinationPath=photos&copyFrom=source');
    h.render(); await h.finish(0, ['old']); h.render();
    assert.equal(h.search.schemaScope, domain.scope);
    h.search.onChange('name:"summer holiday"'); h.render();
    assert.equal(h.requests.length, 1);
    h.search.onSubmit({ preventDefault() {} }); await flush();
    assert.deepEqual(rowKeys(h.render()), []);
    assert.equal(h.requests[1].query, 'name:"summer holiday"');
    assert.equal(new URLSearchParams(h.router.state.location.search).get('destinationPath'), 'photos');
    assert.equal(new URLSearchParams(h.router.state.location.search).get('copyFrom'), 'source');
    await h.finish(1, ['new']); h.render();
    await h.router.navigate(-1); h.render();
    assert.equal(h.search.value, 'name:old');
    await h.router.navigate(1); h.render();
    assert.equal(h.search.value, 'name:"summer holiday"');
    h.search.onReset(); await flush(); h.render();
    const params = new URLSearchParams(h.router.state.location.search);
    assert.equal(params.has('q'), false);
    assert.equal(params.get('copyFrom'), 'source');
    assert.equal(h.requests.at(-1).query, '');
  });

  for (const outcome of ['resolve', 'reject']) {
    test(`${domain.scope} ignores late ${outcome} after navigation and keeps current query feedback`, async t => {
      const h = harness(t, domain, '?q=name:old');
      h.render();
      await h.router.navigate(`/admin/${domain.scope}?q=name:new`); h.render();
      assert.equal(h.requests[0].signal.aborted, true);
      await h.finish(1, ['new']);
      if (outcome === 'resolve') h.requests[0].resolve(payload(domain, ['old']));
      else h.requests[0].reject(new Error('Old query failure'));
      await flush();
      const tree = h.render();
      assert.deepEqual(rowKeys(tree), ['new']);
      assert.equal(errorPanels(tree).length, 0);
    });
  }

  test(`${domain.scope} draft reset does not fetch and query errors remain resettable`, async t => {
    const h = harness(t, domain);
    h.render(); await h.finish(); h.render();
    h.search.onChange('name:draft'); h.render();
    h.search.onChange(''); h.search.onReset(); h.render();
    assert.equal(h.requests.length, 1);
    await h.router.navigate(`/admin/${domain.scope}?q=typo:value`); h.render();
    h.requests[1].reject(new Error('Unknown search field: typo')); await flush();
    assert.equal(errorPanels(h.render())[0].props.message, 'Unknown search field: typo');
    h.search.onReset(); await flush(); h.render(); await h.finish(); h.render();
    assert.equal(h.search.appliedQuery, '');
    assert.equal(h.search.disabled, undefined);
  });

  test(`${domain.scope} mutations reload the applied search, not the unsubmitted input`, async t => {
    const h = harness(t, domain, '?q=status:active');
    h.render();
    const data = payload(domain, ['active']);
    const item = domain.scope === 'shares' ? data[0] : data.requests[0];
    item.active = true;
    h.requests[0].resolve(data); await flush();
    let tree = h.render();
    h.search.onChange('name:draft'); tree = h.render();
    await nodes(tree).find(node => node.type === 'button' && node.props['aria-label'] === 'Revoke').props.onClick();
    await flush(); h.render();
    assert.equal(h.requests.at(-1).query, 'status:active');
    assert.equal(h.search.value, 'name:draft');
    assert.equal(h.mutations.length, 1);
    await h.finish();
    assert.deepEqual(rowKeys(h.render()), []);
  });
}

test('request form edits survive query navigation, empty results, retry and reset; context changes reinitialize defaults', async t => {
  const domain = domains[1], h = harness(t, domain, '?destinationPath=photos&copyFrom=original');
  const fields = tree => nodes(tree).filter(node => node.type === 'input');
  h.render(); await h.finish(0, ['first']);
  let tree = h.render();
  fields(tree).find(node => node.props.maxLength === 120).props.onChange({ target: { value: 'Edited title' } });
  nodes(tree).find(node => node.type === 'textarea').props.onChange({ target: { value: 'Edited description' } });
  fields(tree).find(node => node.props.id === 'fileRequestDestination').props.onChange({ target: { value: 'draft destination' } });
  h.render();
  h.search.onChange('status:full'); h.render(); h.search.onSubmit({ preventDefault() {} });
  await flush(); h.render(); await h.finish(); tree = h.render();
  assert.equal(fields(tree).find(node => node.props.maxLength === 120).props.value, 'Edited title');
  assert.equal(nodes(tree).find(node => node.type === 'textarea').props.value, 'Edited description');
  assert.equal(fields(tree).find(node => node.props.id === 'fileRequestDestination').props.value, 'draft destination');
  // Empty filtered results must not remove the existing whole-catalog cleanup command.
  assert.ok(nodes(tree).find(node => node.type === 'button' && node.props.children === 'Delete expired'));
  await h.router.navigate('/admin/file-requests?destinationPath=photos&copyFrom=original&q=typo:value'); h.render();
  h.requests.at(-1).reject(new Error('Unknown search field: typo')); await flush();
  errorPanels(h.render())[0].props.actions.props.onClick(); h.render(); await h.finish(); tree = h.render();
  assert.equal(fields(tree).find(node => node.props.maxLength === 120).props.value, 'Edited title');
  h.search.onReset(); await flush(); h.render(); await h.finish(); tree = h.render();
  assert.equal(fields(tree).find(node => node.props.maxLength === 120).props.value, 'Edited title');
  await h.router.navigate('/admin/file-requests?destinationPath=other');
  assert.equal(fields(h.render()).length, 0, 'Do not expose old defaults for a different creation context');
  await h.finish(); tree = h.render();
  assert.equal(fields(tree).find(node => node.props.maxLength === 120).props.value, 'Initial title');
});

test('shared link transport forwards expressions and structured errors without fallback', async () => {
  const module = { exports: {} }, calls = [];
  vm.runInNewContext(domains[0].apiCode, { module, exports: module.exports, URLSearchParams, Error,
    require: () => ({}), fetch: async (url, options) => {
      calls.push({ url, options });
      return calls.length < 3 ? { ok: true, json: async () => [] }
        : { ok: false, json: async () => ({ notification: { message: 'Unknown search field: typo' } }) };
    },
  });
  const signal = new AbortController().signal, query = 'path:"summer holiday" created:>=2026-10-04T00:00:00+09:00';
  await module.exports.loadShares(signal, query);
  assert.equal(new URL(calls[0].url, 'https://nas.test').searchParams.get('q'), query);
  assert.equal(calls[0].options.signal, signal);
  await module.exports.loadShares(signal);
  assert.equal(calls[1].url, '/api/v1/shares');
  await assert.rejects(module.exports.loadShares(signal, 'typo:value'), { message: 'Unknown search field: typo' });
  assert.equal(calls.length, 3);
});

test('request transport uses the existing JSON client and preserves creation context alongside q', async () => {
  const module = { exports: {} }, signal = new AbortController().signal;
  const search = `?${new URLSearchParams({ destinationPath: 'photos', copyFrom: 'source', q: 'name:"summer holiday"' })}`;
  let calls = 0;
  vm.runInNewContext(domains[1].apiCode, { module, exports: module.exports, require: () => ({}),
    window: { EnderVault: { requestJson: async (url, options) => {
      calls++;
      assert.equal(url, `/api/v1/file-requests${search}`); assert.equal(options.signal, signal);
      return { requests: [] };
    } } },
  });
  await module.exports.loadFileRequests(search, signal);
  assert.equal(calls, 1);
});

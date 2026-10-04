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
      lib: { entry: fileURLToPath(new URL(`../src/trash/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const code = await compile('TrashApp.tsx');
const apiCode = await compile('trash-api.ts');
const flush = () => new Promise(setImmediate);
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  return [tree, ...nodes(tree.props?.children)];
}
const payload = ids => ({ items: ids.map(id => ({ id, originalName: `${id}.pdf`, originalPath: `photos/${id}.pdf` })) });
const rows = tree => nodes(tree).filter(node => node.type === 'tr' && node.key).map(node => node.key);
const panels = tree => nodes(tree).filter(node => node.type?.name === 'PageErrorPanel');

function harness(t, search = '') {
  const router = createMemoryRouter([{ path: '*', element: null }], { initialEntries: ['/admin/trash' + search] });
  const slots = [], effects = [], requests = [], mutations = [], confirmations = [];
  let cursor = 0, dirty, registration;
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, AbortController, URLSearchParams, Error,
    window: { EnderVault: { askConfirmation: async config => { confirmations.push(config); return true; } } },
    require(id) {
      if (id === 'react') return {
        useState(initial) {
          const index = cursor++;
          slots[index] ??= { value: initial };
          return [slots[index].value, next => {
            const value = typeof next === 'function' ? next(slots[index].value) : next;
            if (!Object.is(value, slots[index].value)) { slots[index].value = value; dirty = true; }
          }];
        },
        useEffect(run, deps) {
          const index = cursor++, previous = slots[index];
          if (previous && deps.every((dep, i) => Object.is(dep, previous.deps[i]))) return;
          const slot = { deps, cleanup: previous?.cleanup };
          slots[index] = slot;
          effects.push(() => { slot.cleanup?.(); slot.cleanup = run(); });
        },
      };
      if (id === 'react/jsx-runtime') return jsx;
      if (id === 'react-router-dom') return { useSearchParams: () => [new URLSearchParams(router.state.location.search),
        next => router.navigate({ pathname: '/admin/trash', search: new URLSearchParams(next).toString() })] };
      if (id.endsWith('/RouteSearch')) return { useRouteSearch: control => { registration = control; } };
      if (id.endsWith('/BrowserEntries')) return { icon: () => null };
      if (id.endsWith('/form-api')) return { toastError: reason => { throw reason; } };
      if (id.endsWith('/trash-api')) return {
        loadTrash: (signal, query) => new Promise((resolve, reject) => { requests.push({ signal, query, resolve, reject }); }),
        ...Object.fromEntries(['restoreTrashItem', 'deleteTrashItem', 'emptyTrash'].map(name => [name,
          async (...args) => { mutations.push({ name, args }); }])),
      };
      const name = id.split('/').at(-1);
      return { [name]: { [name]: () => null }[name] };
    },
  });
  t.after(() => { slots.forEach(slot => slot.cleanup?.()); router.dispose(); });
  return { router, requests, mutations, confirmations,
    get search() { return registration; },
    render() {
      let tree, count = 0;
      do {
        assert.ok(++count < 20, 'render/effect loop');
        dirty = false; cursor = 0; tree = module.exports.TrashApp();
        effects.splice(0).forEach(run => run());
      } while (dirty);
      return tree;
    },
    async finish(index = requests.length - 1, ids = []) { requests[index].resolve(payload(ids)); await flush(); },
  };
}

test('trash search submits on command, preserves URL fields and restores history', async t => {
  const h = harness(t, '?q=name:old&context=keep');
  h.render(); await h.finish(0, ['old']); h.render();
  assert.equal(h.search.schemaScope, 'trash');
  h.search.onChange('name:"summer holiday"'); h.render();
  assert.equal(h.requests.length, 1);
  h.search.onSubmit({ preventDefault() {} }); await flush();
  assert.deepEqual(rows(h.render()), []);
  assert.equal(h.requests[1].query, 'name:"summer holiday"');
  assert.equal(new URLSearchParams(h.router.state.location.search).get('context'), 'keep');
  await h.finish(1, ['new']); h.render();
  await h.router.navigate(-1); h.render();
  assert.equal(h.search.value, 'name:old');
  await h.router.navigate(1); h.render();
  assert.equal(h.search.value, 'name:"summer holiday"');
  h.search.onReset(); await flush(); h.render();
  const params = new URLSearchParams(h.router.state.location.search);
  assert.equal(params.has('q'), false);
  assert.equal(params.get('context'), 'keep');
  assert.equal(h.requests.at(-1).query, '');
});

for (const outcome of ['resolve', 'reject']) {
  test(`trash ignores late ${outcome} after navigation`, async t => {
    const h = harness(t, '?q=name:old');
    h.render();
    await h.router.navigate('/admin/trash?q=name:new'); h.render();
    assert.equal(h.requests[0].signal.aborted, true);
    await h.finish(1, ['new']);
    if (outcome === 'resolve') h.requests[0].resolve(payload(['old']));
    else h.requests[0].reject(new Error('Old query failure'));
    await flush();
    const tree = h.render();
    assert.deepEqual(rows(tree), ['new']);
    assert.equal(panels(tree).length, 0);
  });
}

test('draft-only reset does not fetch; query errors retry the applied expression and remain resettable', async t => {
  const h = harness(t);
  h.render(); await h.finish(); h.render();
  h.search.onChange('draft'); h.render();
  h.search.onChange(''); h.search.onReset(); h.render();
  assert.equal(h.requests.length, 1);
  await h.router.navigate('/admin/trash?q=typo:value'); h.render();
  h.requests[1].reject(new Error('Unknown search field: typo')); await flush();
  h.search.onChange('name:draft');
  panels(h.render())[0].props.actions.props.onClick(); h.render();
  assert.equal(h.requests[2].query, 'typo:value');
  assert.equal(h.search.value, 'name:draft');
  h.requests[2].reject(new Error('Unknown search field: typo')); await flush();
  h.search.onReset(); await flush(); h.render(); await h.finish(); h.render();
  assert.equal(h.search.appliedQuery, '');
  assert.equal(h.search.disabled, undefined);
});

for (const [label, mutation] of [['Restore', 'restoreTrashItem'], ['Permanently delete', 'deleteTrashItem']]) {
  test(`${label} retains applied search and input draft while refreshing`, async t => {
    const h = harness(t, '?q=extension:pdf');
    h.render(); await h.finish(0, ['chosen']); h.render();
    h.search.onChange('name:draft');
    const button = nodes(h.render()).find(node => node.type === 'button' && node.props['aria-label'] === label);
    button.props.onClick(); await flush(); h.render();
    assert.equal(h.requests.at(-1).query, 'extension:pdf');
    assert.equal(h.search.value, 'name:draft');
    assert.deepEqual(h.mutations, [{ name: mutation, args: ['chosen'] }]);
    await h.finish(); assert.deepEqual(rows(h.render()), []);
  });
}

test('empty filtered results keep search and the whole-catalog empty command', async t => {
  const h = harness(t, '?q=name:missing');
  h.render(); await h.finish();
  const tree = h.render();
  assert.ok(nodes(tree).find(node => node.type === 'p' && node.props.children === 'No trash items match this search.'));
  assert.equal(h.search.disabled, undefined);
  const action = nodes(tree).find(node => node.props?.mode === 'single');
  action.props.onAction(); await flush(); h.render();
  assert.match(h.confirmations[0].message, /every item in trash/);
  assert.deepEqual(h.mutations, [{ name: 'emptyTrash', args: [] }]);
  assert.equal(h.requests.at(-1).query, 'name:missing');
});

test('trash transport forwards quoted expressions and structured errors without fallback', async () => {
  const module = { exports: {} }, calls = [];
  vm.runInNewContext(apiCode, { module, exports: module.exports, URLSearchParams, Error,
    require: () => ({}), fetch: async (url, options) => {
      calls.push({ url, options });
      return calls.length < 3 ? { ok: true, json: async () => ({ items: [] }) }
        : { ok: false, json: async () => ({ notification: { message: 'Unknown search field: typo' } }) };
    },
  });
  const signal = new AbortController().signal, query = 'name:"summer holiday" deleted:>=2026-10-04T00:00:00+09:00';
  await module.exports.loadTrash(signal, query);
  assert.equal(new URL(calls[0].url, 'https://nas.test').searchParams.get('q'), query);
  assert.equal(calls[0].options.signal, signal);
  await module.exports.loadTrash(signal);
  assert.equal(calls[1].url, '/api/v1/trash');
  await assert.rejects(module.exports.loadTrash(signal, 'typo:value'), { message: 'Unknown search field: typo' });
  assert.equal(calls.length, 3);
});

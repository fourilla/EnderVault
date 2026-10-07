import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';
import { listHarness, domains, nodes as listNodes } from './helpers/selectable-list-harness.mjs';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent',
    build: { write: false, minify: false,
      lib: { entry: fileURLToPath(new URL(`../src/trash/${file}`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const apiCode = await compile('trash-api.ts');
const flush = () => new Promise(setImmediate);
function nodes(tree) {
  if (Array.isArray(tree)) return tree.flatMap(nodes);
  if (!tree || typeof tree !== 'object') return [];
  if (tree.type?.name === 'ListItemActions') return nodes(tree.type(tree.props));
  return [tree, ...nodes(tree.props?.children)];
}
const payload = ids => ({ items: ids.map(id => ({ id, originalName: `${id}.pdf`, originalPath: `photos/${id}.pdf` })) });
const rows = tree => nodes(tree).filter(node => node.type === 'tr' && node.key).map(node => node.key);
const panels = tree => nodes(tree).filter(node => node.type?.name === 'PageErrorPanel');

const harness = (t, search = '') => listHarness(t, domains[0], search);

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
  const action = listNodes(tree).find(node => node.props?.['aria-label'] === 'Empty trash');
  action.props.onClick(); await flush(); h.render();
  assert.match(h.confirmations[0].message, /every item in trash/);
  assert.deepEqual(h.mutations, [{ name: 'emptyTrash', args: [] }]);
  assert.equal(h.requests.at(-1).query, 'name:missing');
});

for (const confirmed of [false, true]) {
  test(`empty trash releases its lock after query navigation and late confirmation ${confirmed}`, async t => {
    const h = harness(t, '?q=name:old');
    h.render(); await h.finish(0, ['old']); h.render();
    let finishConfirmation;
    h.setConfirmation(() => new Promise(resolve => { finishConfirmation = resolve; }));
    const empty = () => listNodes(h.render()).find(node => node.props?.['aria-label'] === 'Empty trash');
    empty().props.onClick();
    assert.equal(empty().props.disabled, true);
    await h.router.navigate('/admin/trash?q=name:new'); h.render();
    await h.finish(1, ['new']);
    assert.equal(empty().props.disabled, true, 'The pending action still owns the shared lock');
    empty().props.onClick();
    assert.equal(h.confirmations.length, 1);
    finishConfirmation(confirmed); await flush();
    assert.equal(empty().props.disabled, false);
    assert.deepEqual(h.mutations, [], 'The outgoing confirmation cannot submit');
    assert.equal(h.requests.length, 2, 'The outgoing visit cannot reload the new query');
    assert.deepEqual(rows(h.render()), ['new']);
    h.setConfirmation(async () => false);
    empty().props.onClick(); await flush();
    assert.equal(h.confirmations.length, 2, 'A new action can acquire the released lock');
    assert.equal(empty().props.disabled, false);
  });
}

for (const outcome of ['resolve', 'reject']) {
  test(`empty trash releases its lock after query navigation and submitted request ${outcome}`, async t => {
    const h = harness(t, '?q=name:old');
    h.render(); await h.finish(0, ['old']); h.render();
    let finishRequest, failRequest;
    h.setMutationOutcome('emptyTrash', new Promise((resolve, reject) => { finishRequest = resolve; failRequest = reject; }));
    const empty = () => listNodes(h.render()).find(node => node.props?.['aria-label'] === 'Empty trash');
    empty().props.onClick(); await flush();
    assert.deepEqual(h.mutations, [{ name: 'emptyTrash', args: [] }]);
    await h.router.navigate('/admin/trash?q=name:new'); h.render();
    await h.finish(1, ['new']);
    assert.equal(empty().props.disabled, true, 'Submitted work keeps ownership until it settles');
    empty().props.onClick();
    const restore = nodes(h.render()).find(node => node.type === 'button' && node.props['aria-label'] === 'Restore');
    restore.props.onClick(); await flush();
    assert.equal(h.confirmations.length, 1);
    assert.equal(h.mutations.length, 1, 'Neither whole-list nor row actions overlap submitted work');
    if (outcome === 'resolve') finishRequest(); else failRequest(new Error('Connection lost'));
    await flush();
    assert.equal(empty().props.disabled, false);
    assert.equal(h.requests.length, 2, 'Completion cannot reload the outgoing or new visit');
    assert.equal(h.mutations.length, 1, 'Submitted work is never replayed');
    assert.deepEqual(rows(h.render()), ['new']);
    assert.deepEqual(h.errors, outcome === 'reject' ? ['Connection lost'] : []);
    h.setConfirmation(async () => false);
    empty().props.onClick(); await flush();
    assert.equal(h.confirmations.length, 2, 'A new action can acquire the released lock');
    assert.equal(empty().props.disabled, false);
  });
}

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

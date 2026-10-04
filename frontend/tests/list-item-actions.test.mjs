import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

async function compile(file) {
  const result = await build({ configFile: false, logLevel: 'silent', build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${file}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}
const code = await compile('shared/browser/list-item-actions.ts');
const deferred = () => { let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const evaluate = (code, dependencies, window = {}) => {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, window, Error, require: dependencies });
  return module.exports;
};

function setup() {
  const executed = [], errors = [], confirmations = [], confirm = deferred();
  let redraws = 0, reloads = 0, current = true, blocked = false;
  const { createListItemActions } = evaluate(code, () => ({ toastError: reason => errors.push(reason.message) }), {
    EnderVault: { askConfirmation: options => { confirmations.push(options); return confirm.promise; } },
  });
  const options = { items: [{ id: 'custom_token-1', path: 'photos', active: true }], enabled: true,
    blocked: () => blocked, contextKey: {}, isCurrent: () => current, itemKey: item => item.id,
    itemIdentity: item => JSON.stringify([item.id, item.path, item.active]), reload: () => reloads++,
    definitions: [{ id: 'revoke', supports: item => item.active, changesList: true,
      confirmation: { title: 'Revoke', message: 'Confirm', confirmLabel: 'Revoke', danger: true },
      execute: async item => { executed.push(item.id); } }],
  };
  const controller = createListItemActions(() => options, () => redraws++);
  controller.activate();
  return { controller, options, executed, errors, confirmations, confirm,
    get reloads() { return reloads; }, get redraws() { return redraws; },
    leave() { current = false; }, block() { blocked = true; } };
}

test('single actions use a synchronous shared lock, current IDs and one canonical reload', async () => {
  const h = setup(), running = h.controller.run('custom_token-1', 'revoke');
  assert.equal(h.controller.isBusy(), true);
  await h.controller.run('custom_token-1', 'revoke');
  assert.equal(h.confirmations.length, 1);
  h.confirm.resolve(true); await running;
  assert.deepEqual(h.executed, ['custom_token-1']);
  assert.equal(h.reloads, 1);
  assert.equal(h.controller.isBusy(), false);
  assert.equal(h.redraws, 2);
});

for (const change of ['removed', 'renamed', 'inactive', 'disabled', 'blocked', 'query', 'left-before-render', 'disposed']) {
  test(`${change}: late confirmation must not mutate a stale list target`, async () => {
    const h = setup(), running = h.controller.run('custom_token-1', 'revoke');
    if (change === 'removed') h.options.items = [];
    if (change === 'renamed') h.options.items[0].path = 'other';
    if (change === 'inactive') h.options.items[0].active = false;
    if (change === 'disabled') h.options.enabled = false;
    if (change === 'blocked') h.block();
    if (change === 'query') h.options.contextKey = {};
    if (change === 'left-before-render') h.leave();
    if (change === 'disposed') h.controller.dispose();
    h.confirm.resolve(true); await running;
    assert.deepEqual(h.executed, []);
    assert.equal(h.reloads, 0);
    assert.equal(h.controller.isBusy(), false);
  });
}

test('same identity metadata refresh is accepted, but confirmation cancellation does nothing', async () => {
  const h = setup(), running = h.controller.run('custom_token-1', 'revoke');
  h.options.items = [{ ...h.options.items[0], changedCounter: 2 }];
  h.confirm.resolve(false); await running;
  assert.deepEqual(h.executed, []); assert.equal(h.reloads, 0);
  h.confirm.resolve(true);
  const next = setup(), accepted = next.controller.run('custom_token-1', 'revoke');
  next.options.items = [{ ...next.options.items[0], changedCounter: 3 }];
  next.confirm.resolve(true); await accepted;
  assert.deepEqual(next.executed, ['custom_token-1']);
});

test('already submitted mutations survive disposal but never reload the outgoing page', async () => {
  const h = setup(), response = deferred();
  h.options.definitions[0].confirmation = undefined;
  h.options.definitions[0].execute = async item => { h.executed.push(item.id); await response.promise; };
  const submitted = h.controller.run('custom_token-1', 'revoke');
  assert.deepEqual(h.executed, ['custom_token-1']);
  h.controller.dispose(); response.resolve(); await submitted;
  assert.equal(h.reloads, 0);
  assert.equal(h.redraws, 1, 'No outgoing state update');
});

test('server or clipboard failure reports once, releases the lock and never replays the request', async () => {
  const h = setup();
  h.options.definitions[0].confirmation = undefined;
  h.options.definitions[0].execute = async item => { h.executed.push(item.id); throw new Error('Pending files remain'); };
  await h.controller.run('custom_token-1', 'revoke');
  assert.deepEqual(h.executed, ['custom_token-1']);
  assert.deepEqual(h.errors, ['Pending files remain']);
  assert.equal(h.reloads, 0); assert.equal(h.controller.disabled(), false);
});

test('share and request definitions retain their original single transports and confirmation policies', async () => {
  const controller = evaluate(code, () => ({ toastError() {} }));
  const posts = [], notified = [];
  const forms = { postForm: async (url, values) => { posts.push({ url, values }); return { ok: true }; },
    notify: body => notified.push(body) };
  const shareApi = evaluate(await compile('shares/share-api.ts'), () => forms);
  const requestApi = evaluate(await compile('file-requests/file-request-api.ts'), () => forms);
  const shares = evaluate(await compile('shares/share-list-actions.ts'), id => id.endsWith('/list-item-actions') ? controller : shareApi);
  const requests = evaluate(await compile('file-requests/file-request-list-actions.ts'), id => id.endsWith('/list-item-actions') ? controller : requestApi);
  const item = { token: 'not-a-uuid_token', id: 'request/id', active: true };
  for (const action of shares.shareListActions.filter(action => action.changesList)) {
    assert.equal(action.confirmation, undefined);
    await action.execute(item);
  }
  for (const action of requests.fileRequestListActions(() => {}).filter(action => action.changesList)) {
    assert.ok(action.confirmation);
    assert.equal(action.supports(item), action.id === 'request-revoke');
    assert.equal(action.supports({ ...item, active: false }), action.id === 'request-delete');
    await action.execute(item);
  }
  assert.deepEqual(posts.map(post => post.url), ['/api/v1/shares/revoke', '/api/v1/shares/delete',
    '/api/v1/file-requests/request%2Fid/revoke', '/api/v1/file-requests/request%2Fid/delete']);
  assert.equal(posts[0].values.token, item.token);
  assert.equal(posts[1].values.token, item.token);
  assert.equal(notified.length, 4);
});

function bulkSetup() {
  const h = setup(), submitted = [], resolved = [];
  h.options.items.push({ id: 'second', path: 'photos', active: true });
  h.options.selectedIds = () => h.options.items.map(item => item.id);
  h.options.bulkResolved = (result, action) => resolved.push({ result, action });
  h.options.definitions[0].bulk = {
    confirmation: count => ({ title: 'Selected revoke', message: String(count), confirmLabel: 'Revoke', danger: true }),
    execute: async ids => { submitted.push(Array.from(ids)); return bulkResult(ids); },
  };
  return { ...h, h, submitted, resolved };
}
const bulkResult = ids => ({ ok: true, succeededCount: ids.length, failedCount: 0,
  results: Array.from(ids, id => ({ id, status: 'APPLIED', message: 'Done' })) });

test('selected actions use one confirmation, one bulk transport, one reload and the same lock as row actions', async () => {
  const b = bulkSetup(), ids = b.options.selectedIds(), running = b.controller.runSelected(ids, 'revoke');
  await b.controller.runSelected(ids, 'revoke');
  await b.controller.run(ids[0], 'revoke');
  assert.equal(b.confirmations.length, 1);
  assert.equal(b.confirmations[0].message, '2');
  assert.equal(b.controller.isBusy(), true);
  b.confirm.resolve(true); await running;
  assert.deepEqual(b.submitted, [ids]);
  assert.deepEqual(b.executed, [], 'No repeated single POSTs');
  assert.equal(b.h.reloads, 1);
  assert.equal(b.resolved.length, 1);
  assert.equal(b.resolved[0].action, 'revoke');
  assert.equal(b.controller.isBusy(), false);
});

for (const change of ['selection', 'removed', 'renamed', 'inactive', 'disabled', 'blocked', 'query', 'left-before-render', 'disposed', 'cancel']) {
  test(`selected ${change}: confirmation cannot execute a stale or canceled selection`, async () => {
    const b = bulkSetup(), running = b.controller.runSelected(b.options.selectedIds(), 'revoke');
    if (change === 'selection') b.options.selectedIds = () => ['second'];
    if (change === 'removed') b.options.items.pop();
    if (change === 'renamed') b.options.items[1].path = 'other';
    if (change === 'inactive') b.options.items[1].active = false;
    if (change === 'disabled') b.options.enabled = false;
    if (change === 'blocked') b.h.block();
    if (change === 'query') b.options.contextKey = {};
    if (change === 'left-before-render') b.h.leave();
    if (change === 'disposed') b.controller.dispose();
    b.confirm.resolve(change !== 'cancel'); await running;
    assert.deepEqual(b.submitted, []);
    assert.equal(b.h.reloads, 0);
    assert.equal(b.resolved.length, 0);
    assert.equal(b.controller.isBusy(), false);
  });
}

test('bulk capabilities, shared eligibility, unique IDs and 200 item limit are checked before confirmation', async () => {
  const b = bulkSetup();
  assert.equal(b.controller.selectionDefinitions(b.options.items).length, 1);
  b.options.items[1].active = false;
  assert.equal(b.controller.selectionDefinitions(b.options.items).length, 0);
  await b.controller.runSelected(b.options.selectedIds(), 'revoke');
  await b.controller.runSelected(['custom_token-1', 'custom_token-1'], 'revoke');
  await b.controller.runSelected([], 'revoke');
  await b.controller.runSelected(Array.from({ length: 201 }, (_, i) => String(i)), 'revoke');
  b.options.definitions[0].bulk = undefined;
  await b.controller.runSelected(['custom_token-1'], 'revoke');
  assert.equal(b.confirmations.length, 0);
  assert.deepEqual(b.submitted, []);
});

test('lost bulk response reloads once without automatic replay or removing uncertain IDs', async () => {
  const b = bulkSetup();
  b.options.definitions[0].bulk.execute = async ids => { b.submitted.push(Array.from(ids)); throw new Error('Network lost'); };
  const running = b.controller.runSelected(b.options.selectedIds(), 'revoke');
  b.confirm.resolve(true); await running;
  assert.equal(b.h.reloads, 1);
  assert.equal(b.submitted.length, 1);
  assert.equal(b.resolved.length, 0);
  assert.deepEqual(b.errors, ['Network lost']);
});

test('submitted bulk work survives outgoing view disposal without applying results or reloading another page', async () => {
  const b = bulkSetup(), response = deferred(), ids = b.options.selectedIds();
  b.options.definitions[0].bulk.execute = async ids => { b.submitted.push(Array.from(ids)); return response.promise; };
  const running = b.controller.runSelected(ids, 'revoke');
  b.confirm.resolve(true); await new Promise(setImmediate);
  assert.equal(b.submitted.length, 1);
  b.controller.dispose(); response.resolve(bulkResult(ids)); await running;
  assert.equal(b.resolved.length, 0);
  assert.equal(b.h.reloads, 0);
});

test('bulk envelope validation refuses missing, duplicate, reordered and inconsistent successes', () => {
  const { validateListBulkResult } = evaluate(code, () => ({ toastError() {} }));
  const ids = ['first', 'second'], good = bulkResult(ids);
  assert.equal(validateListBulkResult(good, ids), good);
  const partial = { ...good, succeededCount: 1, failedCount: 1,
    results: [good.results[0], { id: 'second', status: 'REJECTED', message: 'Pending files remain' }] };
  assert.equal(validateListBulkResult(partial, ids), partial);
  for (const bad of [null, { ...good, ok: false }, { ...good, results: [] }, { ...good, succeededCount: 1 },
    { ...good, failedCount: 1 }, { ...good, results: [...good.results].reverse() },
    { ...good, results: [good.results[0], good.results[0]] }, { ...good, results: [good.results[0], null] },
    { ...good, results: [good.results[0], { id: 'second', status: 'UNKNOWN', message: 'Bad' }] }]) {
    assert.throws(() => validateListBulkResult(bad, ids), /could not be verified/);
  }
});

test('request selected transport submits encoded IDs and a single summary, never calls the single mutation loop', async () => {
  const controller = evaluate(code, () => ({ toastError() {} })), posts = [], notified = [];
  const forms = { postEncodedForm: async (url, values) => { posts.push({ url, values }); return bulkResult(values.ids); },
    postForm: () => assert.fail('Bulk must not use single multipart transport'), notify: body => notified.push(body) };
  const api = evaluate(await compile('file-requests/file-request-api.ts'), id => id.endsWith('/list-item-actions') ? controller : forms);
  const ids = ['one', 'two'];
  const result = await api.resolveFileRequestSelection(ids, 'DELETE');
  assert.equal(result.succeededCount, 2);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].url, '/api/v1/file-requests/selected/resolve');
  assert.deepEqual(Array.from(posts[0].values.ids), ids);
  assert.equal(posts[0].values.confirmed, true);
  assert.equal(posts[0].values.action, 'DELETE');
  assert.equal(notified.length, 1);
});

test('share selected transport preserves case-sensitive tokens, uses encoded bulk and validates before notification', async () => {
  const controller = evaluate(code, () => ({ toastError() {} })), posts = [], notified = [];
  let invalid = false;
  const forms = { postEncodedForm: async (url, values) => {
    posts.push({ url, values }); return invalid ? { ok: true } : bulkResult(values.tokens);
  }, postForm: () => assert.fail('No single mutation or multipart fallback'), notify: body => notified.push(body) };
  const api = evaluate(await compile('shares/share-api.ts'), id => id.endsWith('/list-item-actions') ? controller : forms);
  const ids = ['Custom_token-1', 'custom_token-1'];
  await api.resolveShareSelection(ids, 'DELETE');
  assert.equal(posts.length, 1);
  assert.equal(posts[0].url, '/api/v1/shares/selected/resolve');
  assert.deepEqual(Array.from(posts[0].values.tokens), ids);
  assert.equal(posts[0].values.action, 'DELETE');
  assert.equal(posts[0].values.confirmed, true);
  assert.equal(notified.length, 1);
  invalid = true;
  await assert.rejects(api.resolveShareSelection(ids, 'REVOKE'), /could not be verified/);
  assert.equal(notified.length, 1);
  assert.equal(posts.length, 2);
});

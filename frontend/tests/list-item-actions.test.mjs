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

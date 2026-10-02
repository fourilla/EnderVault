import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

const resolver = registerHooks({ resolve(specifier, context, next) {
  if (specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier) && context.parentURL?.includes('/frontend/src/')) {
    return next(new URL(specifier + '.ts', context.parentURL).href, context);
  }
  return next(specifier, context);
} });
const { useBookmarkActions } = await import('../src/bookmarks/useBookmarkActions.ts');
const { createRecentActions } = await import('../src/recent/recent-actions.ts');
const { bookmarkRequestKeyFor } = await import('../src/bookmarks/bookmark-api.ts');
const { recentRequestKeyFor } = await import('../src/recent/recent-api.ts');
resolver.deregister();

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

function setup(t, domain) {
  const previous = globalThis.window;
  t.after(() => { if (previous === undefined) delete globalThis.window; else globalThis.window = previous; });
  const entries = domain === 'Bookmarks'
    ? [{ id: 'uuid-one', title: 'Same name', type: 'directory' }, { id: 'uuid-two', title: 'Same name', type: 'link' }]
    : [{ path: 'one/deep/same + &.txt', type: 'file' }, { path: 'two/same + &.txt', type: 'file' }];
  const key = (entry) => domain === 'Bookmarks' ? entry.id : entry.path;
  const keys = entries.map(key);
  let selected = new Set(keys), reloads = 0, response = {}, current = true;
  let state = { directoryId: 'parent-one', query: 'original query', page: 3 };
  const requests = [], confirmations = [], toasts = [], notifications = [];
  globalThis.window = { EnderVault: {
    csrfPair: () => ({ name: '_csrf', value: 'test-csrf' }),
    requestJson: async (url, options) => {
      requests.push({ url, ...options });
      if (response instanceof Error) throw response;
      return response;
    },
    askConfirmation: async (options) => { confirmations.push(options); return true; },
    showToast: (...args) => toasts.push(args), showNotification: (value) => notifications.push(value),
  } };
  const options = { selectedEntries: entries,
    setSelected: (update) => { selected = typeof update === 'function' ? update(selected) : update; },
    setPayload() {}, effectiveState: () => state, reload: () => { reloads++; } };
  const actions = domain === 'Bookmarks' ? useBookmarkActions(options) : createRecentActions(options);
  const guard = () => current;
  return { entries, keys, requests, confirmations, toasts, notifications,
    run: (items = entries, confirm = true) => domain === 'Bookmarks'
      ? actions.deleteEntries(items, guard) : actions.removeEntries(items, { isCurrent: guard, confirm }),
    selected: () => [...selected], reloads: () => reloads,
    select: (keys) => { selected = new Set(keys); },
    state: (value) => { state = value; }, current: (value) => { current = value; }, response: (value) => { response = value; },
  };
}

for (const domain of ['Bookmarks', 'Recent']) {
  test(`${domain}: Delete uses its own existing endpoint, complete identity and CSRF, never file trash`, async (t) => {
    const s = setup(t, domain);
    await s.run();
    assert.equal(s.requests.length, 1);
    const request = s.requests[0];
    assert.equal(request.method, 'POST');
    assert.equal(request.body.get('_csrf'), 'test-csrf');
    assert.equal(request.body.get('q'), 'original query');
    if (domain === 'Bookmarks') {
      assert.equal(request.url, '/api/v1/bookmarks/delete-selected');
      assert.deepEqual(request.body.getAll('bookmarkIds'), s.keys);
      assert.equal(request.body.get('parentId'), 'parent-one');
      assert.equal(request.body.has('paths'), false);
    } else {
      assert.equal(request.url, '/api/v1/recent/remove');
      assert.deepEqual(request.body.getAll('paths'), s.keys);
      assert.equal(request.body.get('page'), '3');
      assert.match(s.confirmations[0].message, /Files and directories will not be deleted/);
    }
    assert.equal(s.selected().length, 0);
    assert.equal(s.reloads(), 1);
  });

  test(`${domain}: target and request context are snapshotted before confirmation; newer selections survive`, async (t) => {
    const s = setup(t, domain), confirm = deferred();
    window.EnderVault.askConfirmation = () => confirm.promise;
    const operation = s.run();
    const keys = [...s.keys];
    s.entries.splice(0, s.entries.length, { id: 'unrelated-uuid', path: 'unrelated/path' });
    s.state({ directoryId: 'different-parent', query: 'different query', page: 9 });
    s.select([keys[0], 'new-selection']);
    confirm.resolve(true); await operation;
    assert.deepEqual(s.requests[0].body.getAll(domain === 'Bookmarks' ? 'bookmarkIds' : 'paths'), keys);
    assert.equal(s.requests[0].body.get('q'), 'original query');
    assert.equal(s.requests[0].body.get(domain === 'Bookmarks' ? 'parentId' : 'page'), domain === 'Bookmarks' ? 'parent-one' : '3');
    assert.deepEqual(s.selected(), ['new-selection']);
  });

  test(`${domain}: cancel, empty selection and invalid initial context send no mutation`, async (t) => {
    const s = setup(t, domain);
    window.EnderVault.askConfirmation = async () => false;
    await s.run(); await s.run([]);
    s.current(false); await s.run();
    assert.equal(s.requests.length, 0);
    assert.equal(s.reloads(), 0);
    assert.deepEqual(s.selected(), s.keys);
  });

  test(`${domain}: navigation or a failed/unavailable list during confirmation cancels unsubmitted Delete`, async (t) => {
    const s = setup(t, domain), confirm = deferred();
    window.EnderVault.askConfirmation = () => confirm.promise;
    const operation = s.run();
    s.current(false); confirm.resolve(true); await operation;
    assert.equal(s.requests.length, 0);
    assert.deepEqual(s.selected(), s.keys);
    assert.equal(s.reloads(), 0);
  });

  test(`${domain}: a submitted response after departure can notify but cannot reset or refresh the new page`, async (t) => {
    const s = setup(t, domain), response = deferred();
    s.response(response.promise);
    const operation = s.run();
    await Promise.resolve();
    assert.equal(s.requests.length, 1);
    s.current(false);
    s.select(['new-page-item']);
    response.resolve({ notification: { type: 'success', message: 'Completed' } });
    await operation;
    assert.deepEqual(s.selected(), ['new-page-item']);
    assert.equal(s.reloads(), 0);
    assert.equal(s.notifications.length, 1);
  });

  test(`${domain}: confirmation and request errors preserve selection and use the existing toast`, async (t) => {
    const s = setup(t, domain);
    window.EnderVault.askConfirmation = async () => { throw new Error('Confirmation unavailable'); };
    await s.run();
    assert.equal(s.requests.length, 0);
    window.EnderVault.askConfirmation = async () => true;
    s.response(new Error('Request unavailable')); await s.run();
    assert.deepEqual(s.toasts, [['error', 'Confirmation unavailable'], ['error', 'Request unavailable']]);
    assert.deepEqual(s.selected(), s.keys);
    assert.equal(s.reloads(), 0);
  });
}

test('Bookmarks single-item Delete retains its existing endpoint and does not identify by title', async (t) => {
  const s = setup(t, 'Bookmarks');
  await s.run([s.entries[1]]);
  assert.equal(s.requests[0].url, '/api/v1/bookmarks/delete');
  assert.equal(s.requests[0].body.get('id'), 'uuid-two');
  assert.equal(s.requests[0].body.has('bookmarkIds'), false);
  assert.deepEqual(s.selected(), ['uuid-one']);
});

test('Recent toolbar/context action keeps immediate record-only removal without keyboard confirmation', async (t) => {
  const s = setup(t, 'Recent');
  await s.run(s.entries, false);
  assert.equal(s.confirmations.length, 0);
  assert.equal(s.requests[0].url, '/api/v1/recent/remove');
});

test('Bookmarks condition key uses the directory and query, not scroll', () => {
  const initial = { directoryId: 'uuid-parent', query: 'same', scrollTop: 0 };
  assert.notEqual(bookmarkRequestKeyFor(initial), bookmarkRequestKeyFor({ ...initial, directoryId: '' }));
  assert.notEqual(bookmarkRequestKeyFor(initial), bookmarkRequestKeyFor({ ...initial, query: 'other' }));
  assert.equal(bookmarkRequestKeyFor(initial), bookmarkRequestKeyFor({ ...initial, scrollTop: 500 }));
});

test('Recent condition key includes every listing filter but excludes view and scroll', () => {
  const initial = { query: 'needle', page: 2, sort: 'recent', direction: 'desc', hidden: 'hide', pageSize: 200 };
  for (const [field, value] of [['query', 'other'], ['page', 3], ['sort', 'name'], ['direction', 'asc'],
    ['hidden', 'show'], ['pageSize', 50]]) {
    assert.notEqual(recentRequestKeyFor(initial), recentRequestKeyFor({ ...initial, [field]: value }), field);
  }
  assert.equal(recentRequestKeyFor(initial), recentRequestKeyFor({ ...initial, view: 'grid', scrollTop: 700 }));
});

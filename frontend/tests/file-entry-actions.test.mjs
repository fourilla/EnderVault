import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

// Production imports are resolved by Vite; let Node load the same local TS modules.
const resolver = registerHooks({
  resolve(specifier, context, next) {
    if (specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier)
        && context.parentURL?.includes('/frontend/src/')) {
      return next(new URL(specifier + '.ts', context.parentURL).href, context);
    }
    return next(specifier, context);
  },
});
const { createFileEntryActions } = await import('../src/shared/browser/file-entry-actions.ts');
resolver.deregister();

function setup(t, options = {}) {
  const previous = { window: globalThis.window, document: globalThis.document };
  t.after(() => {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  });
  const entries = [
    { path: 'one/deep/note + &.txt', parentPath: 'one/deep', name: 'note + &.txt', type: 'file', downloadUrl: '/single' },
    { path: 'two/note + &.txt', parentPath: 'two', name: 'note + &.txt', type: 'file' },
  ];
  let payload = { directories: [], entries };
  let selected = new Set(entries.map((entry) => entry.path));
  let response = {};
  let reloads = 0;
  const requests = [], tracked = [], urls = [], events = [], buffers = [];
  const request = async (url, options) => { requests.push({ url, ...options }); return response; };
  globalThis.document = { dispatchEvent: (event) => events.push(event.type) };
  globalThis.window = {
    location: { assign: (url) => urls.push(url) },
    EnderVault: {
      csrfPair: () => ({ name: '_csrf', value: 'test-csrf' }),
      requestJson: request, requestJsonResolvingConflicts: request,
      askConfirmation: async () => true, askTextInput: async () => 'renamed.txt',
      showNotification() {}, showToast() {}, copyText: async () => true,
    },
    EnderVaultServerTasks: { track: (task, options) => tracked.push({ task, options }) },
  };
  const actions = createFileEntryActions({
    selectedEntries: entries, setSelected: (value) => { selected = value; },
    setPayload: (update) => { payload = update(payload); }, reload: () => { reloads++; },
    onTransferBuffer: (buffer) => buffers.push(buffer), ...options,
  });
  return { actions, entries, requests, tracked, urls, events, buffers,
    respond: (value) => { response = value; }, selected: () => selected,
    payload: () => payload, reloads: () => reloads };
}

test('trash from Recent sends full vault paths and CSRF, then requests refresh through the task bridge', async (t) => {
  const state = setup(t);
  state.respond({ task: { id: 'trash-task', active: true } });
  await state.actions.moveEntriesToTrash();
  assert.equal(state.requests[0].url, '/api/v1/files/trash');
  assert.equal(state.requests[0].method, 'POST');
  assert.equal(state.requests[0].body.get('_csrf'), 'test-csrf');
  assert.equal(state.requests[0].body.get('path'), '');
  assert.deepEqual(state.requests[0].body.getAll('paths'), state.entries.map((entry) => entry.path));
  assert.equal(state.requests[0].body.has('items'), false);
  assert.equal(state.tracked[0].options.refreshUrl, '/files?path=');
  assert.equal(state.tracked[0].options.announceStart, true);
  assert.equal(state.selected().size, 0);
  assert.equal(state.reloads(), 0);
});

test('rename uses the clicked item parent, resolves conflicts and refreshes without navigation', async (t) => {
  const state = setup(t);
  await state.actions.renameEntry(state.entries[1]);
  assert.equal(state.requests[0].url, '/api/v1/files/rename');
  assert.equal(state.requests[0].body.get('path'), 'two');
  assert.equal(state.requests[0].body.get('item'), state.entries[1].name);
  assert.equal(state.requests[0].body.get('newName'), 'renamed.txt');
  assert.equal(state.requests[0].body.get('conflictPolicy'), 'ask');
  assert.equal(state.reloads(), 1);
  assert.deepEqual(state.urls, []);
});

test('favorite and buffer actions share API updates without touching a same-named sibling', async (t) => {
  const state = setup(t);
  state.respond({ active: true });
  await state.actions.toggleFavorite(state.entries[1]);
  assert.equal(state.payload().entries[1].favorite, true);
  assert.equal(state.payload().entries[0].favorite, undefined);
  assert.deepEqual(state.events, ['endervault:favorites-changed']);
  state.respond({ transferBuffer: { active: true, count: 1, items: [] } });
  await state.actions.addEntriesToBuffer([state.entries[1]]);
  assert.deepEqual(state.requests[1].body.getAll('paths'), [state.entries[1].path]);
  assert.equal(state.buffers[0].count, 1);
  assert.equal(state.selected().size, 0);
});

test('single downloads stay direct and multi-selection uses the correct surface ZIP endpoint', (t) => {
  const state = setup(t, { zipDownloadUrl: '/files/recent/download.zip' });
  state.actions.downloadEntries([state.entries[0]]);
  assert.equal(state.urls[0], '/single');
  state.actions.downloadEntries();
  const url = new URL(state.urls[1], 'https://nas.test');
  assert.equal(url.pathname, '/files/recent/download.zip');
  assert.deepEqual(url.searchParams.getAll('paths'), state.entries.map((entry) => entry.path));
});

test('cancel and empty selections do not send a mutation or refresh', async (t) => {
  const state = setup(t);
  window.EnderVault.askConfirmation = async () => false;
  await state.actions.moveEntriesToTrash();
  await state.actions.moveEntriesToTrash([]);
  await state.actions.addEntriesToBuffer([]);
  assert.deepEqual(state.requests, []);
  assert.equal(state.reloads(), 0);
  assert.equal(state.selected().size, 2);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const result = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/app/uploads/directory-upload.ts', import.meta.url)), formats: ['cjs'] },
} });
const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
const root = () => ({ name: 'root', files: ['a/x', 'b/x'].map((path) => ({
  path, file: new File(['abc'], 'x', { lastModified: 42 }),
})), directories: ['empty'] });

function setup(request, create) {
  const storage = new Map(), progress = [], calls = [];
  const window = {
    setTimeout: (callback) => setTimeout(callback, 0),
    localStorage: { getItem: (key) => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: (key) => storage.delete(key) },
    EnderVault: { requestJson: async (url, options) => {
      calls.push({ url, ...options, body: options.body ? JSON.parse(options.body) : undefined });
      return request(url, options);
    } },
    EnderVaultResumableUpload: { create },
  };
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, window, document: { querySelector: () => null } });
  return { storage, progress, calls, make: () => new module.exports.DirectoryUpload(root(), 'dest',
    (bytes, count) => progress.push([bytes, count]), () => {}) };
}
const group = (status = 'RECEIVING', completedPaths = []) => ({ id: 'group-id', status, completedPaths });

test('admission rejection keeps its reason without claiming staged files exist', async () => {
  const state = setup(() => { throw Object.assign(Error('New directory uploads are disabled.'), { status: 403 }); },
    () => { throw Error('must not upload'); });
  await assert.rejects(state.make().start(), (error) => {
    assert.equal(error.status, 403);
    assert.match(error.message, /disabled/);
    assert.equal(error.stagingRetained, false);
    return true;
  });
  assert.equal(state.storage.size, 0);
});

test('completedPaths skip members; only DIRECTORY_READY members lead to complete and PENDING clears resume', async () => {
  const members = [];
  const state = setup((url) => url.endsWith('/complete') ? group('PENDING') : group('RECEIVING', ['a/x']),
    (options) => { members.push(options); return { start: async () => ({ status: 'DIRECTORY_READY' }) }; });
  assert.equal((await state.make().start()).status, 'PENDING');
  assert.equal(members.length, 1);
  assert.match(members[0].admissionUrl, /relativePath=b%2Fx$/);
  assert.deepEqual(JSON.parse(members[0].context), ['dest', 'root', 'group-id', 'b/x']);
  assert.deepEqual(state.progress, [[3, 1], [6, 2]]);
  assert.equal(state.storage.size, 0);
  assert.deepEqual(state.calls[0].body.files.map((file) => file.path), ['a/x', 'b/x']);
  assert.deepEqual(state.calls[0].body.directories, ['empty']);
});

test('partial failure retains group ID and never calls complete; reselect resumes same manifest', async () => {
  let fail = true, sent = 0;
  const state = setup((url) => url.endsWith('/complete') ? group('COMPLETED') : group('RECEIVING', fail ? [] : ['a/x']),
    () => ({ start: async () => {
      if (fail && ++sent === 2) throw Error('offline');
      return { status: 'DIRECTORY_READY' };
    } }));
  await assert.rejects(state.make().start(), (error) => {
    assert.match(error.message, /offline/);
    assert.equal(error.stagingRetained, true);
    return true;
  });
  assert.equal(state.storage.size, 1);
  assert.equal(state.calls.some((call) => call.url.endsWith('/complete')), false);
  fail = false;
  assert.equal((await state.make().start()).status, 'COMPLETED');
  assert.equal(state.calls.filter((call) => call.body?.name).at(-1).body.resumeId, 'group-id');
  assert.equal(state.storage.size, 0);
});

test('stored terminal ID is removed before a new intentional upload admission', async () => {
  const state = setup((url, options) => options.method === 'GET' ? group('PENDING')
    : url.endsWith('/complete') ? group('COMPLETED') : group(),
  () => ({ start: async () => ({ status: 'DIRECTORY_READY' }) }));
  const upload = state.make();
  state.storage.set(upload.key, 'old-id');
  await upload.start();
  assert.equal(state.calls.find((call) => call.body?.name).body.resumeId, undefined);
});

test('COMMITTING resume polls without uploading or calling complete', async () => {
  let polls = 0;
  const state = setup((_url, options) => options.method === 'GET' && ++polls > 1 ? group('COMPLETED') : group('COMMITTING'),
    () => { throw Error('must not upload'); });
  const upload = state.make();
  state.storage.set(upload.key, 'group-id');
  assert.equal((await upload.start()).status, 'COMPLETED');
  assert.equal(state.calls.some((call) => call.url.endsWith('/complete')), false);
});

test('whole-root cancel aborts active member before deleting group and never completes', async () => {
  const order = [];
  let active, settle;
  const started = new Promise((resolve) => { active = resolve; });
  const state = setup((_url, options) => { if (options.method === 'DELETE') { order.push('delete'); return group('CANCELED'); } return group(); },
    () => ({ start: () => new Promise((resolve) => { settle = resolve; active(); }),
      abort: async () => { order.push('abort'); settle({ status: 'CANCELED' }); } }));
  const upload = state.make();
  const running = upload.start();
  await started;
  await upload.abort();
  assert.equal(await running, null);
  assert.deepEqual(order, ['abort', 'delete']);
  assert.equal(state.calls.some((call) => call.url.endsWith('/complete')), false);
  assert.equal(state.storage.size, 0);
});

test('cancel during root admission waits for its ID then deletes without starting members', async () => {
  let admit, entered;
  const admitted = new Promise((resolve) => { entered = resolve; });
  const state = setup((_url, options) => options.method === 'DELETE' ? group('CANCELED')
    : new Promise((resolve) => { admit = resolve; entered(); }),
  () => { throw Error('must not upload'); });
  const upload = state.make();
  const running = upload.start();
  await admitted;
  const cancel = upload.abort();
  admit(group());
  await cancel;
  assert.equal(await running, null);
  assert.deepEqual(state.calls.map((call) => call.method), ['POST', 'DELETE']);
});

test('cancel during resume status lookup deletes the known group without new admission', async () => {
  let status, entered;
  const checking = new Promise((resolve) => { entered = resolve; });
  const state = setup((_url, options) => options.method === 'DELETE' ? group('CANCELED')
    : new Promise((resolve) => { status = resolve; entered(); }),
  () => { throw Error('must not upload'); });
  const upload = state.make();
  state.storage.set(upload.key, 'group-id');
  const running = upload.start();
  await checking;
  const cancel = upload.abort();
  status(group());
  await cancel;
  assert.equal(await running, null);
  assert.deepEqual(state.calls.map((call) => call.method), ['GET', 'DELETE']);
});

for (const terminal of ['COMPLETED', 'PENDING', 'CANCELED']) {
  for (const conflict of [false, true]) {
    test(`cancel honors ${terminal} after ${conflict ? '409 and status reconciliation' : 'terminal DELETE response'}`, async () => {
      let entered, settle;
      const admitted = new Promise((resolve) => { entered = resolve; });
      let reads = 0;
      const state = setup((_url, options) => {
        if (options.method === 'DELETE') {
          if (conflict) throw Object.assign(Error('commit in progress'), { status: 409 });
          return group(terminal);
        }
        if (options.method === 'GET') return group(++reads === 1 ? 'COMMITTING' : terminal);
        return new Promise((resolve) => { settle = resolve; entered(); });
      }, () => { throw Error('must not start a member'); });
      const upload = state.make();
      const running = upload.start();
      await admitted;
      const cancel = upload.abort();
      settle(group('COMMITTING'));
      assert.equal((await cancel).status, terminal);
      assert.equal(await running, null);
      assert.equal(state.storage.size, 0);
      assert.equal(reads, conflict ? 2 : 0);
    });
  }
}

test('unconfirmed cancellation retains resume ID and rejects instead of claiming canceled', async () => {
  const state = setup(() => group(), () => ({ start: async () => { throw Error('offline'); }, abort: async () => {} }));
  const upload = state.make();
  await assert.rejects(upload.start(), /offline/);
  await assert.rejects(upload.abort(), /not confirmed/);
  assert.equal(state.storage.size, 1);
});

test('submitted manifest order and resume key are independent of selection order', async () => {
  const state = setup((url) => url.endsWith('/complete') ? group('COMPLETED') : group(),
    () => ({ start: async () => ({ status: 'DIRECTORY_READY' }) }));
  const upload = state.make();
  upload.root.files.reverse();
  upload.root.directories = ['z', 'a'];
  await upload.start();
  assert.deepEqual(state.calls[0].body.files.map((file) => file.path), ['a/x', 'b/x']);
  assert.deepEqual(state.calls[0].body.directories, ['a', 'z']);
});

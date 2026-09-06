import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const script = readFileSync(new URL('../../src/main/resources/static/js/resumable-upload-client.js', import.meta.url), 'utf8');
function setup(fetch, Upload = class { constructor() { throw Error('Must not connect tus'); } }) {
  const storage = new Map();
  const window = { tus: { Upload }, localStorage: {
    getItem: (key) => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: (key) => storage.delete(key),
  } };
  vm.runInNewContext(script, { window, fetch, document: { querySelector: () => null }, TextEncoder, Uint8Array, ArrayBuffer });
  return { client: window.EnderVaultResumableUpload, storage };
}
const response = (body) => ({ ok: true, json: async () => body });

test('ready admission reads DIRECTORY_READY and returns without constructing a tus upload', async () => {
  const calls = [], progress = [];
  const { client, storage } = setup(async (url, options) => {
    calls.push([url, options]);
    return response(options.method === 'POST'
      ? { sessionId: 'member', ready: true, statusUrl: '/status' }
      : { status: 'DIRECTORY_READY' });
  });
  const file = new File(['hello'], 'same.txt', { lastModified: 1 });
  const handle = client.create({ file, context: 'root/a/same.txt', admissionUrl: '/admit',
    onProgress: (sent, total) => progress.push([sent, total]) });
  assert.equal((await handle.start()).status, 'DIRECTORY_READY');
  assert.deepEqual(calls.map(([url]) => url), ['/admit', '/status']);
  assert.deepEqual(progress, [[5, 5]]);
  assert.equal(storage.size, 0);
  assert.notEqual(await client.fingerprint(file, 'root/a/same.txt'), await client.fingerprint(file, 'root/b/same.txt'));
});

test('aborting active tus settles start even when tus abort emits no callback', async () => {
  let started;
  const startSignal = new Promise((resolve) => { started = resolve; });
  const calls = [];
  const { client } = setup(async (url, options) => {
    calls.push(options.method);
    return response({ sessionId: 'member', statusUrl: '/status' });
  }, class { start() { started(); } async abort() {} });
  const handle = client.create({ file: new File(['x'], 'x'), context: 'test', admissionUrl: '/admit' });
  const pending = handle.start();
  await startSignal;
  await handle.abort();
  assert.equal((await pending).status, 'CANCELED');
  assert.deepEqual(calls, ['POST', 'DELETE']);
});

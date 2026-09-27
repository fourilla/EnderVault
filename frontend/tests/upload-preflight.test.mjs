import assert from 'node:assert/strict';
import test from 'node:test';
import { confirmAdminUpload } from '../src/app/uploads/upload-preflight.ts';

function setup(request, confirm) {
  globalThis.document = { querySelector: () => ({ content: 'X-CSRF-TOKEN' }) };
  globalThis.window = { EnderVault: { csrfPair: () => ({ name: '_csrf', value: 'token' }),
    requestJson: request, askConfirmation: confirm } };
}

test('upload preflight checks all chunks before one confirmation and sends only unique root names', async () => {
  const calls = []; let confirmations = 0;
  setup(async (url, options) => {
    assert.equal(url, '/api/v1/files/upload-preflight');
    assert.equal(options.headers['X-CSRF-TOKEN'], 'token');
    const body = JSON.parse(options.body); calls.push(body);
    return { conflicts: [body.names[0]] };
  }, async (options) => {
    confirmations++;
    assert.equal(calls.length, 2);
    assert.match(options.message, /item0, item200/);
    return false;
  });
  const names = Array.from({ length: 201 }, (_, i) => `item${i}`);
  assert.equal(await confirmAdminUpload([...names, 'item0'], 'target'), false);
  assert.equal(confirmations, 1);
  assert.deepEqual(calls.map((call) => call.names.length), [200, 1]);
  assert.equal(calls[0].path, 'target');
});

test('nonconflicting uploads continue without a dialog; empty selections do nothing', async () => {
  let requests = 0;
  setup(async () => { requests++; return { conflicts: [] }; }, () => assert.fail('unexpected dialog'));
  assert.equal(await confirmAdminUpload([], ''), false);
  assert.equal(requests, 0);
  assert.equal(await confirmAdminUpload(['photo.png'], ''), true);
  assert.equal(requests, 1);
});

test('confirmation allows upload but failed preflight never silently approves it', async () => {
  setup(async () => ({ conflicts: ['photos'] }), async () => true);
  assert.equal(await confirmAdminUpload(['photos'], ''), true);
  setup(async () => { throw new Error('Session expired'); }, () => assert.fail('unexpected dialog'));
  await assert.rejects(confirmAdminUpload(['photos'], ''), /Session expired/);
});

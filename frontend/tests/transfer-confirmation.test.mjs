import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const code = readFileSync(new URL('../../src/main/resources/static/js/endervault-core.js', import.meta.url), 'utf8');
function setup(responses, confirm) {
  const calls = [];
  const window = { location: { href: 'http://localhost/files' } };
  vm.runInNewContext(code, { window, FormData, URL, fetch: async (_url, options) => {
    calls.push(options.body);
    const [status, payload] = responses.shift();
    return { ok: status === 200, status, headers: { get: () => 'application/json' }, json: async () => payload };
  } });
  window.EnderVault.askConfirmation = confirm;
  window.EnderVault.askFileConflictPolicy = async () => 'overwrite';
  return { client: window.EnderVault, calls };
}
const confirmation = { directoryTransferConfirmation: ['target/photos', 'target/docs'], transferBuffer: { active: true, count: 2, items: ['photos', 'docs'] } };

test('canceling directory confirmation does not submit again or clear the buffer', async () => {
  const { client, calls } = setup([[409, confirmation]], async (options) => {
    assert.match(options.message, /target\/photos, target\/docs/);
    return false;
  });
  const result = await client.requestJsonResolvingConflicts('/paste', { method: 'POST', body: new FormData() });
  assert.equal(calls.length, 1);
  assert.equal(result.transferBuffer, confirmation.transferBuffer);
  assert.equal(result.transferBuffer.active, true);
  assert.equal(result.transferBuffer.count, 2);
  assert.equal(result.task, undefined);
});

test('directory confirmation survives subsequent ordinary file conflict retry without approving it', async () => {
  const { client, calls } = setup([[409, confirmation], [409, { conflict: {} }], [200, { ok: true }]], async () => true);
  const body = new FormData();
  body.set('conflictPolicy', 'ask');
  body.set('_csrf', 'token');
  await client.requestJsonResolvingConflicts('/paste', { method: 'POST', body });
  assert.equal(calls.length, 3);
  assert.equal(calls[1].get('directoryTransferConfirmed'), 'true');
  assert.equal(calls[1].get('conflictPolicy'), 'ask');
  assert.equal(calls[2].get('directoryTransferConfirmed'), 'true');
  assert.equal(calls[2].get('conflictPolicy'), 'overwrite');
  assert.equal(calls[2].get('_csrf'), 'token');
  assert.equal(body.has('directoryTransferConfirmed'), false);
});

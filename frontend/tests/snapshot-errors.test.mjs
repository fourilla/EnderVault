import assert from 'node:assert/strict';
import test from 'node:test';
import { canRetainSnapshot } from '../src/shared/api/snapshot-errors.ts';
import { getSettings } from '../src/settings/settings-api.ts';

test('transient refresh failures allow the last matching snapshot to remain', () => {
  for (const reason of [new TypeError('offline'), new SyntaxError('invalid JSON'),
    { status: 200 }, { status: 408 }, { status: 429 }, { status: 500 }, { status: 503 }]) {
    assert.equal(canRetainSnapshot(reason), true);
  }
});

test('authentication loss, invalid requests, and missing resources discard snapshots', () => {
  for (const reason of [{ status: 400 }, { status: 401 }, { status: 403 }, { status: 404 },
    { status: 410 }, { status: 200, sessionExpired: true }]) {
    assert.equal(canRetainSnapshot(reason), false);
  }
});

test('settings GET exposes response status for refresh failure classification', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response('{}', {
    status: 403, headers: { 'Content-Type': 'application/json' },
  }));
  await assert.rejects(getSettings('/api/v1/settings/passkeys'), (error) => {
    assert.equal(error.status, 403);
    assert.equal(canRetainSnapshot(error), false);
    return true;
  });
});

test('settings GET detects a login redirect even if the final HTTP response is 200', async (t) => {
  const response = new Response('<html>login</html>', { headers: { 'Content-Type': 'text/html' } });
  Object.defineProperties(response, { redirected: { value: true }, url: { value: 'https://nas.test/login' } });
  t.mock.method(globalThis, 'fetch', async () => response);
  await assert.rejects(getSettings('/api/v1/settings/passkeys'), (error) => {
    assert.equal(error.sessionExpired, true);
    assert.equal(canRetainSnapshot(error), false);
    return true;
  });
});

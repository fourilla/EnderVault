import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import {
  AdminAppAccessDeniedError, AdminAppSessionExpiredError, fetchAdminAppBootstrap,
} from '../src/app/app-api.ts';

const originalWindow = globalThis.window;
before(() => { globalThis.window = { location: { origin: 'http://localhost:8080' } }; });
after(() => {
  if (originalWindow === undefined) delete globalThis.window;
  else globalThis.window = originalWindow;
});

const jsonResponse = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json' },
});

test('bootstrap loads JSON with same-origin credentials and the cancellation signal', async (t) => {
  const snapshot = { username: 'admin', favorites: [] };
  const controller = new AbortController();
  const request = t.mock.method(globalThis, 'fetch', async () => jsonResponse(snapshot));
  assert.deepEqual(await fetchAdminAppBootstrap(controller.signal), snapshot);
  assert.equal(request.mock.calls[0].arguments[0], '/api/v1/app/bootstrap');
  assert.deepEqual(request.mock.calls[0].arguments[1], {
    headers: { Accept: 'application/json' }, credentials: 'same-origin', signal: controller.signal,
  });
});

test('JSON 401 is session expiry, not a recoverable server outage', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => jsonResponse({}, 401));
  await assert.rejects(fetchAdminAppBootstrap(), AdminAppSessionExpiredError);
});

test('403 is permission denial, not a recoverable server outage', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => jsonResponse({}, 403));
  await assert.rejects(fetchAdminAppBootstrap(), AdminAppAccessDeniedError);
});

test('a redirect to the login document identifies an expired session', async (t) => {
  const response = new Response('<!doctype html>', { headers: { 'content-type': 'text/html' } });
  Object.defineProperties(response, {
    url: { value: 'http://localhost:8080/login?expired' }, redirected: { value: true },
  });
  t.mock.method(globalThis, 'fetch', async () => response);
  await assert.rejects(fetchAdminAppBootstrap(), AdminAppSessionExpiredError);
});

test('server errors and unexpected HTML are not classified as authentication loss', async (t) => {
  const responses = [jsonResponse({}, 503), new Response('<html>Proxy error</html>', {
    headers: { 'content-type': 'text/html' },
  }), new Response('{invalid', { headers: { 'content-type': 'application/json' } })];
  t.mock.method(globalThis, 'fetch', async () => responses.shift());
  for (let index = 0; index < 3; index++) {
    await assert.rejects(fetchAdminAppBootstrap(), (error) => {
      assert.ok(error instanceof Error);
      assert.ok(!(error instanceof AdminAppSessionExpiredError));
      assert.ok(!(error instanceof AdminAppAccessDeniedError));
      return true;
    });
  }
});

test('transport and abort errors retain their identity for provider handling', async (t) => {
  let failure = new TypeError('Failed to fetch');
  t.mock.method(globalThis, 'fetch', async () => { throw failure; });
  await assert.rejects(fetchAdminAppBootstrap(), (error) => error === failure);
  failure = new DOMException('Aborted', 'AbortError');
  await assert.rejects(fetchAdminAppBootstrap(), (error) => error === failure);
});

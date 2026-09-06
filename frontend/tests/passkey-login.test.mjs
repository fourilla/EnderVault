import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const code = await readFile(new URL('../../src/main/resources/static/js/passkeys.js', import.meta.url), 'utf8');

test('standalone passkey login still decodes options, sends assertion and CSRF, and navigates after legacy management removal', async () => {
  let click, options, finish;
  const requests = [], navigations = [];
  const done = new Promise((resolve) => { finish = resolve; });
  const button = {
    dataset: { optionsUrl: '/passkeys/login/options', finishUrl: '/passkeys/login/finish' },
    classList: { toggle: (_name, busy) => { if (!busy) finish(); } },
  };
  const bytes = new Uint8Array([1, 2]).buffer;
  vm.runInNewContext(code, {
    Uint8Array,
    window: {
      atob, btoa, PublicKeyCredential: class {}, isSecureContext: true,
      EnderVault: {
        csrfPair: () => ({ value: 'csrf' }),
        navigateWithNotification: (body) => { navigations.push(body.redirectUrl); return true; },
        showToast: (_type, message) => assert.fail(message),
      },
    },
    document: { addEventListener: (_name, listener) => { click = listener; } },
    navigator: { credentials: { get: async (value) => {
      options = value;
      return { id: 'credential', rawId: bytes, type: 'public-key', getClientExtensionResults: () => ({}),
        response: { clientDataJSON: bytes, authenticatorData: bytes, signature: bytes, userHandle: null } };
    } } },
    fetch: async (url, request) => {
      requests.push({ url, request });
      return { ok: true, json: async () => requests.length === 1
        ? { publicKey: { challenge: 'AQI', allowCredentials: [{ id: 'AQI', type: 'public-key' }] } }
        : { ok: true, redirectUrl: '/files' } };
    },
  });
  click({ target: { closest: (selector) => selector === '[data-passkey-login]' ? button : null }, preventDefault() {} });
  await done;
  assert.deepEqual([...new Uint8Array(options.publicKey.challenge)], [1, 2]);
  assert.equal(requests.length, 2);
  for (const { request } of requests) {
    assert.equal(request.headers['X-CSRF-TOKEN'], 'csrf');
    assert.equal(request.credentials, 'same-origin');
  }
  const assertion = JSON.parse(requests[1].request.body).credential;
  assert.equal(assertion.response.signature, 'AQI');
  assert.equal(assertion.response.userHandle, null);
  assert.deepEqual(navigations, ['/files']);
  assert.equal(button.disabled, false);
});

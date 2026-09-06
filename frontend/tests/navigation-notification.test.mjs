import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const code = await readFile(new URL('../../src/main/resources/static/js/endervault-core.js', import.meta.url), 'utf8');
function setup(spa) {
  const saved = [], shown = [], navigated = [];
  const window = {
    sessionStorage: { setItem: (...args) => saved.push(args) },
    EnderVaultToasts: { show: (item) => shown.push(item) },
    location: { assign: (url) => navigated.push(url) },
  };
  vm.runInNewContext(code, { window, document: { dispatchEvent: () => !spa },
    CustomEvent: class { constructor(type, options) { Object.assign(this, { type }, options); } },
  });
  return { saved, shown, navigated, core: window.EnderVault };
}

test('SPA action redirects display their notification now instead of waiting for DOMContentLoaded', () => {
  const s = setup(true);
  assert.equal(s.core.navigateWithNotification({ redirectUrl: '/files', notification: { type: 'success', message: 'Saved' } }), true);
  assert.equal(s.shown[0].message, 'Saved');
  assert.equal(s.saved.length, 0);
  assert.equal(s.navigated.length, 0);
});

test('full document navigation retains the notification for the next document; missing redirects do nothing', () => {
  const s = setup(false);
  assert.equal(s.core.navigateWithNotification({ notification: { message: 'Unused' } }), false);
  s.core.navigateWithNotification({ redirectUrl: '/login', notification: { type: 'success', message: 'Signed out' } });
  assert.equal(JSON.parse(s.saved[0][1]).message, 'Signed out');
  assert.deepEqual(s.navigated, ['/login']);
  assert.equal(s.shown.length, 0);
});

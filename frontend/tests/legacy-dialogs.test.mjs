import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const result = await build({ configFile: false, logLevel: 'silent', build: {
  write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../src/shared/dialogs/legacy-dialogs.ts', import.meta.url)), formats: ['cjs'] },
} });
const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
function setup() {
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports });
  const ownerDocument = { body: { style: { overflow: '' } }, activeElement: null };
  class Dialog extends EventTarget {
    ownerDocument = ownerDocument;
    open = false;
    showModal() { this.open = true; }
    close() { this.open = false; }
  }
  return { bridge: module.exports.createLegacyDialogBridge(), Dialog, ownerDocument };
}

test('legacy dialog Escape uses common cleanup and permits reopening', () => {
  const { bridge, Dialog, ownerDocument } = setup();
  const dialog = new Dialog();
  bridge.open(dialog);
  bridge.open(dialog);
  dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
  assert.equal(dialog.open, false);
  assert.equal(ownerDocument.body.style.overflow, '');
  bridge.open(dialog);
  assert.equal(dialog.open, true);
  bridge.closeAll();
});

test('native completion releases the active slot and route cleanup removes queued dialogs', () => {
  const { bridge, Dialog, ownerDocument } = setup();
  const first = new Dialog(), second = new Dialog();
  bridge.open(first); bridge.open(second);
  assert.equal(second.open, false);
  first.close(); first.dispatchEvent(new Event('close'));
  assert.equal(second.open, true);
  bridge.closeAll();
  assert.equal(second.open, false);
  assert.equal(ownerDocument.body.style.overflow, '');
});

test('closing a queued legacy dialog prevents it appearing later', () => {
  const { bridge, Dialog } = setup();
  const first = new Dialog(), second = new Dialog();
  bridge.open(first); bridge.open(second);
  bridge.close(second); bridge.close(first);
  assert.equal(second.open, false);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { mountDialog } from '../src/shared/dialogs/dialog-lifecycle.ts';

function setup() {
  const focused = [];
  const document = { body: { style: { overflow: 'auto' } },
    activeElement: { isConnected: true, focus: (options) => focused.push(options) } };
  class Dialog extends EventTarget {
    ownerDocument = document;
    open = false;
    shown = 0;
    showModal() { this.open = true; this.shown++; }
    close() { this.open = false; }
    getBoundingClientRect() { return { left: 100, top: 100, right: 300, bottom: 300 }; }
  }
  const dialog = new Dialog();
  const reasons = [];
  const policy = { dismissOnBackdrop: true, dismissOnEscape: true, busy: false,
    onDismiss: (reason) => reasons.push(reason) };
  const pointer = (type, x, y = x) => {
    const event = new Event(type);
    Object.assign(event, { clientX: x, clientY: y, button: 0 });
    dialog.dispatchEvent(event);
  };
  return { Dialog, dialog, document, focused, reasons, policy, pointer };
}

test('open locks scrolling; disposal restores focus/scroll without requesting a business action', () => {
  const s = setup();
  const dispose = mountDialog(s.dialog, () => s.policy);
  assert.equal(s.dialog.open, true);
  assert.equal(s.document.body.style.overflow, 'hidden');
  dispose();
  dispose();
  assert.equal(s.dialog.open, false);
  assert.equal(s.document.body.style.overflow, 'auto');
  assert.deepEqual(s.focused, [{ preventScroll: true }]);
  assert.deepEqual(s.reasons, []);
});

test('backdrop dismissal requires both press and click outside the dialog bounds', () => {
  const s = setup();
  const dispose = mountDialog(s.dialog, () => s.policy);
  try {
    s.pointer('pointerdown', 150);
    s.pointer('click', 10);
    s.pointer('pointerdown', 10);
    s.pointer('click', 150);
    assert.deepEqual(s.reasons, []);
    s.pointer('pointerdown', 10);
    s.pointer('click', 10);
    assert.deepEqual(s.reasons, ['backdrop']);
  } finally { dispose(); }
});

test('busy and Escape policy are read live without reopening the dialog', () => {
  const s = setup();
  const dispose = mountDialog(s.dialog, () => s.policy);
  const escape = () => {
    const event = new Event('cancel', { cancelable: true });
    s.dialog.dispatchEvent(event);
    assert.equal(event.defaultPrevented, true);
  };
  try {
    s.policy.busy = true;
    escape();
    s.pointer('pointerdown', 10);
    s.pointer('click', 10);
    s.policy.busy = false;
    s.policy.dismissOnEscape = false;
    escape();
    assert.deepEqual(s.reasons, []);
    s.policy.dismissOnEscape = true;
    escape();
    escape();
    assert.deepEqual(s.reasons, ['escape']);
    assert.equal(s.dialog.shown, 1);
  } finally { dispose(); }
});

test('backdrop can be disabled independently and cancelled gestures do not dismiss', () => {
  const s = setup();
  const dispose = mountDialog(s.dialog, () => s.policy);
  try {
    s.policy.dismissOnBackdrop = false;
    s.pointer('pointerdown', 10);
    s.pointer('click', 10);
    s.policy.dismissOnBackdrop = true;
    s.pointer('pointerdown', 10);
    s.dialog.dispatchEvent(new Event('pointercancel'));
    s.pointer('click', 10);
    assert.deepEqual(s.reasons, []);
  } finally { dispose(); }
});

test('dialogs are sequenced and a queued unmounted dialog is never displayed', () => {
  const s = setup();
  const second = new s.Dialog(), third = new s.Dialog();
  const firstDispose = mountDialog(s.dialog, () => s.policy);
  const secondDispose = mountDialog(second, () => s.policy);
  const thirdDispose = mountDialog(third, () => s.policy);
  try {
    assert.equal(second.open, false);
    assert.equal(third.open, false);
    secondDispose();
    firstDispose();
    assert.equal(second.shown, 0);
    assert.equal(third.open, true);
    assert.equal(s.document.body.style.overflow, 'hidden');
  } finally { firstDispose(); secondDispose(); thirdDispose(); }
  assert.equal(s.document.body.style.overflow, 'auto');
});

test('StrictMode remount ignores stale close events and removed openers', () => {
  const s = setup();
  mountDialog(s.dialog, () => s.policy)();
  const dispose = mountDialog(s.dialog, () => s.policy);
  try {
    s.dialog.dispatchEvent(new Event('close'));
    assert.deepEqual(s.reasons, []);
    s.document.activeElement.isConnected = false;
  } finally { dispose(); }
  assert.equal(s.focused.length, 1);
});

test('external native close is reported once, cleanup does not duplicate it', () => {
  const s = setup();
  const dispose = mountDialog(s.dialog, () => s.policy);
  s.dialog.close();
  s.dialog.dispatchEvent(new Event('close'));
  s.dialog.dispatchEvent(new Event('close'));
  dispose();
  assert.deepEqual(s.reasons, ['native']);
});

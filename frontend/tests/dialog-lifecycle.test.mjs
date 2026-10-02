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
    initialFocus = null;
    querySelector() { return this.initialFocus; }
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

test('initial input is focused only after activation, including queued and nested dialogs', () => {
  const s = setup();
  const queued = new s.Dialog(), child = new s.Dialog();
  const calls = [];
  for (const [name, dialog] of [['parent', s.dialog], ['queued', queued], ['child', child]]) {
    dialog.initialFocus = { focus(options) {
      assert.equal(dialog.open, true);
      assert.deepEqual(options, { preventScroll: true });
      calls.push(name);
    } };
  }
  const first = mountDialog(s.dialog, () => s.policy);
  const second = mountDialog(queued, () => s.policy);
  const nested = mountDialog(child, () => s.policy, true);
  try {
    assert.deepEqual(calls, ['parent', 'child']);
    nested();
    assert.deepEqual(calls, ['parent', 'child']);
    first();
    assert.deepEqual(calls, ['parent', 'child', 'queued']);
  } finally { nested(); first(); second(); }
});

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

test('explicit nested dialog opens above its parent and keeps other dialogs queued', () => {
  const s = setup();
  const child = new s.Dialog(), queued = new s.Dialog();
  const parentDispose = mountDialog(s.dialog, () => s.policy);
  const queuedDispose = mountDialog(queued, () => s.policy);
  const childDispose = mountDialog(child, () => s.policy, true);
  try {
    assert.equal(child.open, true);
    assert.equal(s.dialog.open, true);
    assert.equal(queued.open, false);
    s.dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
    assert.deepEqual(s.reasons, []);
    childDispose();
    assert.equal(s.dialog.open, true);
    assert.equal(s.document.body.style.overflow, 'hidden');
    assert.equal(queued.open, false);
    parentDispose();
    assert.equal(queued.open, true);
  } finally { childDispose(); parentDispose(); queuedDispose(); }
  assert.equal(s.document.body.style.overflow, 'auto');
});

test('parent removal before nested child preserves scroll lock until the last dialog closes', () => {
  const s = setup();
  const parentDispose = mountDialog(s.dialog, () => s.policy);
  const child = new s.Dialog();
  const childDispose = mountDialog(child, () => s.policy, true);
  try {
    parentDispose();
    assert.equal(child.open, true);
    assert.equal(s.document.body.style.overflow, 'hidden');
    assert.equal(s.focused.length, 0);
  } finally { parentDispose(); childDispose(); }
  assert.equal(s.document.body.style.overflow, 'auto');
});

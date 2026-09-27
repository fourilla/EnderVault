import assert from 'node:assert/strict';
import test from 'node:test';
import { announcePending, observeNewDecisions } from '../src/pending-decisions/decision-auto-open.ts';

function setup(t) {
  const window = new EventTarget(), document = new EventTarget();
  const frames = new Map();
  let next = 0, blocked = false;
  window.requestAnimationFrame = (callback) => { frames.set(++next, callback); return next; };
  window.cancelAnimationFrame = (id) => frames.delete(id);
  document.visibilityState = 'visible';
  document.querySelector = () => blocked ? {} : null;
  const oldWindow = globalThis.window, oldDocument = globalThis.document;
  Object.assign(globalThis, { window, document });
  const opened = [];
  const dispose = observeNewDecisions((target) => { opened.push(target); return true; });
  t.after(() => { dispose(); Object.assign(globalThis, { window: oldWindow, document: oldDocument }); });
  return { opened, document, dispose, block: (value) => { blocked = value; },
    paint: () => { const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach((callback) => callback()); },
    task: (detail) => document.dispatchEvent(new CustomEvent('endervault:task-terminal', { detail })),
  };
}

test('only locally submitted review tasks auto-open once per decision, not per task', (t) => {
  const s = setup(t);
  const task = { type: 'FILE_COPY', status: 'PENDING', resultReference: 'review' };
  s.task(task); s.paint();
  assert.equal(s.opened.length, 0);
  s.task({ ...task, initiatedHere: true }); s.paint();
  s.task({ ...task, initiatedHere: true }); s.paint();
  s.task({ ...task, initiatedHere: true, resultReference: 'successor' }); s.paint();
  assert.deepEqual(s.opened.map((target) => target.id), ['review', 'successor']);
});

test('upload decision followed by merge review opens distinct stages', (t) => {
  const s = setup(t);
  announcePending('upload'); s.paint();
  s.task({ type: 'DIRECTORY_MERGE', status: 'PENDING', initiatedHere: true, resultReference: 'merge' }); s.paint();
  assert.deepEqual(s.opened, [{ kind: 'pending', id: 'upload' }, { kind: 'merge', id: 'merge' }]);
});

test('simultaneous and blocked decisions are not queued for surprise reopening', (t) => {
  const s = setup(t);
  announcePending('one'); announcePending('two'); s.paint();
  announcePending('two'); s.paint();
  s.block(true); announcePending('blocked'); s.paint();
  s.block(false); announcePending('blocked'); s.paint();
  s.document.visibilityState = 'hidden'; announcePending('hidden'); s.paint();
  s.document.visibilityState = 'visible'; announcePending('hidden'); s.paint();
  s.document.fullscreenElement = {}; announcePending('fullscreen'); s.paint();
  s.document.fullscreenElement = null; announcePending('fullscreen'); s.paint();
  assert.deepEqual(s.opened.map((target) => target.id), ['one']);
});

test('completion, cancellation and unrelated tasks do not open decisions; disposal cancels scheduled opens', (t) => {
  const s = setup(t);
  for (const status of ['COMPLETE', 'CANCELED', 'FAILED', 'RUNNING']) {
    s.task({ type: 'FILE_MOVE', status, initiatedHere: true, resultReference: status }); s.paint();
  }
  s.task({ type: 'ZIP_CREATE', status: 'PENDING', initiatedHere: true, resultReference: 'zip' }); s.paint();
  announcePending('late'); s.dispose(); s.paint();
  assert.equal(s.opened.length, 0);
});

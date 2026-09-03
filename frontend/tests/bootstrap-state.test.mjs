import assert from 'node:assert/strict';
import test from 'node:test';
import { bootstrapReducer, initialBootstrapState } from '../src/app/bootstrap-state.ts';

const snapshot = { username: 'admin', favorites: [], uploads: { maxConcurrentUploads: 2 } };
const start = (state, requestId) => bootstrapReducer(state, { type: 'start', requestId });
const succeed = (state, requestId, bootstrap = snapshot) => bootstrapReducer(state, {
  type: 'success', requestId, bootstrap,
});
const fail = (state, requestId, kind = 'unavailable') => bootstrapReducer(state, {
  type: 'failure', requestId, failure: { kind, message: kind },
});
const loaded = () => succeed(start(initialBootstrapState, 1), 1);

test('initial failure has no shell snapshot and supports retry', () => {
  const failed = fail(start(initialBootstrapState, 1), 1);
  assert.equal(failed.bootstrap, null);
  assert.equal(failed.loading, false);
  assert.equal(failed.failure.kind, 'unavailable');
  const retried = succeed(start(failed, 2), 2);
  assert.equal(retried.bootstrap, snapshot);
  assert.equal(retried.failure, null);
});

test('refresh, offline failure, and retry preserve the existing shell snapshot by identity', () => {
  let state = loaded();
  for (let requestId = 2; requestId < 5; requestId++) {
    state = start(state, requestId);
    assert.equal(state.bootstrap, snapshot);
    assert.equal(state.loading, true);
    state = fail(state, requestId);
    assert.equal(state.bootstrap, snapshot);
    assert.equal(state.loading, false);
  }
  const updated = { ...snapshot, favorites: [{ path: 'new.txt' }] };
  state = succeed(start(state, 5), 5, updated);
  assert.equal(state.bootstrap, updated);
  assert.equal(state.failure, null);
});

test('session expiry and permission denial invalidate the old authenticated snapshot', () => {
  for (const kind of ['session-expired', 'forbidden']) {
    const denied = fail(start(loaded(), 2), 2, kind);
    assert.equal(denied.bootstrap, null);
    assert.equal(denied.failure.kind, kind);
    assert.equal(denied.loading, false);
    const offlineRetry = fail(start(denied, 3), 3);
    assert.equal(offlineRetry.bootstrap, null);
    const reauthenticated = succeed(start(offlineRetry, 4), 4);
    assert.equal(reauthenticated.bootstrap, snapshot);
  }
});

test('a stale success cannot restore the shell after a newer authentication failure', () => {
  const waiting = start(start(loaded(), 2), 3);
  const denied = fail(waiting, 3, 'session-expired');
  assert.equal(succeed(denied, 2), denied);
  assert.equal(fail(denied, 2), denied);
  assert.equal(start(denied, 2), denied);
});

test('an older failed request cannot overwrite a newer successful refresh', () => {
  const updated = { ...snapshot, username: 'renamed-admin' };
  const current = succeed(start(start(loaded(), 2), 3), 3, updated);
  assert.equal(fail(current, 2), current);
  assert.equal(fail(current, 2, 'forbidden'), current);
  assert.equal(succeed(current, 2), current);
  assert.equal(current.bootstrap, updated);
});

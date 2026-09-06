import assert from 'node:assert/strict';
import test from 'node:test';
import { createDialogRequests } from '../src/shared/dialogs/dialog-requests.ts';

test('text and confirmation requests settle in order, duplicate/stale responses are ignored', async () => {
  const queue = createDialogRequests();
  const first = queue.askTextInput({ initialValue: 'name' });
  const id = queue.snapshot().id;
  const second = queue.askConfirmation({ danger: true });
  queue.settle(id, 'new name');
  queue.settle(id, true);
  assert.equal(queue.snapshot().kind, 'confirm');
  queue.settle(queue.snapshot().id, true);
  assert.equal(await first, 'new name');
  assert.equal(await second, true);
  assert.equal(queue.snapshot(), null);
});

test('route change or unmount resolves every pending operation as cancellation', async () => {
  const queue = createDialogRequests();
  const text = queue.askTextInput();
  const confirm = queue.askConfirmation();
  queue.cancelAll();
  assert.equal(await text, null);
  assert.equal(await confirm, false);
  const next = queue.askConfirmation();
  queue.settle(queue.snapshot().id, true);
  assert.equal(await next, true);
});

test('wrong response types cannot accidentally confirm a destructive action', async () => {
  const queue = createDialogRequests();
  const confirmation = queue.askConfirmation();
  queue.settle(queue.snapshot().id, 'true');
  assert.equal(await confirmation, false);
  const text = queue.askTextInput();
  queue.settle(queue.snapshot().id, true);
  assert.equal(await text, null);
});

test('conflict close preserves the callers default or pending choice', async () => {
  const queue = createDialogRequests();
  const pending = queue.askFileConflictPolicy({ closeValue: 'defer' });
  queue.settle(queue.snapshot().id, null);
  assert.equal(await pending, 'defer');
  const ordinary = queue.askFileConflictPolicy({ defaultPolicy: 'rename' });
  queue.cancelAll();
  assert.equal(await ordinary, 'default');
});

test('SPA navigation cancels page prompts but retains application-level upload conflict decisions', async () => {
  const queue = createDialogRequests();
  const name = queue.askTextInput();
  const pending = queue.askFileConflictPolicy({ closeValue: 'defer' });
  queue.cancelPageRequests();
  assert.equal(await name, null);
  assert.equal(queue.snapshot().kind, 'conflict');
  queue.settle(queue.snapshot().id, 'rename');
  assert.equal(await pending, 'rename');
});

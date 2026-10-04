import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

async function compile(name) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/pending-decisions/${name}`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  return (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
}

const policyModule = { exports: {} };
vm.runInNewContext(await compile('pending-decision-actions.ts'), { module: policyModule, exports: policyModule.exports });
const controllerCode = await compile('pending-decision-controller.ts');
const hookCode = await compile('usePendingDecisionActions.ts');
const flush = () => new Promise(setImmediate);
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const item = (id = 'pending', directory = false, extra = {}) => ({ id, directory, originalFilename: 'photos.v1', ...extra });

function harness(overrides = {}) {
  const confirmations = [], inputs = [], resolves = [], posts = [], polls = [], tracked = [], errors = [], events = [],
    removed = [], opened = [], busyChanges = [], timers = new Map();
  let decisions = [item()], redraws = 0, started = 0, timerId = 0;
  const options = { enabled: true, contextKey: 'query', nested: false,
    decision: id => decisions.find(entry => entry.id === id),
    resolved: id => removed.push(id), openMerge: id => opened.push(id),
    mergeStarted: () => started++, busyChanged: value => busyChanges.push(value) };
  const window = { location: { href: '/files?path=photos' },
    dispatchEvent: event => events.push(event.type),
    EnderVault: {
      askConfirmation: async value => { confirmations.push(value); return overrides.confirm ? overrides.confirm(value) : true; },
      askTextInput: async value => { inputs.push(value); return overrides.input ? overrides.input(value) : 'new name.txt'; },
      requestJson: async (url, value) => {
        polls.push({ url, ...value });
        return overrides.poll ? overrides.poll(url, value) : [{ status: 'COMPLETE', active: false }];
      },
    }, EnderVaultServerTasks: { track: (...args) => tracked.push(args) } };
  const modules = {
    '../shared/api/form-api': {
      postForm: async (...args) => { posts.push(args); return overrides.post ? overrides.post(...args) : { id: 'task' }; },
      toastError: (reason, fallback) => errors.push({ reason, fallback }),
    },
    '../directory-merges/merge-api': { mergeBase: '/api/v1/files/directory-merges' },
    './pending-decision-api': { resolvePendingDecision: async (...args) => {
      resolves.push(args);
      return overrides.resolve ? overrides.resolve(...args) : { removedId: args[0] };
    } },
    './pending-decision-actions': policyModule.exports,
  };
  const module = { exports: {} };
  vm.runInNewContext(controllerCode, { module, exports: module.exports, window, AbortController, Error,
    CustomEvent: class { constructor(type) { this.type = type; } },
    setTimeout: (run, delay) => { assert.equal(delay, 1000); timers.set(++timerId, run); return timerId; },
    clearTimeout: id => timers.delete(id),
    require: id => { assert.ok(id in modules, `Unexpected controller dependency: ${id}`); return modules[id]; },
  });
  const controller = module.exports.createPendingDecisionController(() => options, () => redraws++);
  return { controller, options, confirmations, inputs, resolves, posts, polls, tracked, errors, events,
    removed, opened, busyChanges, timers,
    get started() { return started; }, get redraws() { return redraws; },
    items(next) { decisions = next; },
    async tick() { const [id, run] = timers.entries().next().value; timers.delete(id); run(); await flush(); },
    create: module.exports.createPendingDecisionController };
}

test('single resolutions preserve confirmation/input options, filenames and explicit replace confirmation', async () => {
  for (const directory of [false, true]) for (const action of ['KEEP_BOTH', 'SAVE_AS', 'REPLACE', 'DISCARD']) {
    if (directory && action === 'REPLACE') continue;
    const h = harness(); h.items([item('pending', directory)]); h.options.nested = true;
    await h.controller.run('pending', action);
    assert.equal(h.resolves.length, 1);
    assert.equal(h.resolves[0][0], 'pending'); assert.equal(h.resolves[0][1], action);
    assert.equal(h.resolves[0][2].replaceConfirmed, action === 'REPLACE');
    assert.equal(h.resolves[0][2].filename, action === 'SAVE_AS' ? 'new name.txt' : undefined);
    if (action === 'SAVE_AS') {
      assert.equal(h.inputs[0].nested, true);
      assert.equal(h.inputs[0].label, directory ? 'Directory name' : 'File name');
      assert.equal(h.inputs[0].initialValue, 'photos.v1');
    }
    if (action === 'REPLACE' || action === 'DISCARD') {
      assert.equal(h.confirmations[0].nested, true); assert.equal(h.confirmations[0].danger, true);
      assert.match(h.confirmations[0].message, action === 'REPLACE' ? /destination file/
        : directory ? /directory and its contents/ : /staged file/);
    } else assert.equal(h.confirmations.length, 0);
    assert.deepEqual(h.removed, ['pending']); assert.deepEqual(h.busyChanges, [true, false]);
    assert.equal(h.controller.busy('pending'), false); assert.equal(h.errors.length, 0);
  }
});

test('canceling a confirmation or name input only releases the lock, with no mutation or success callback', async () => {
  for (const action of ['SAVE_AS', 'REPLACE', 'DISCARD']) {
    const h = harness({ confirm: () => false, input: () => null });
    await h.controller.run('pending', action);
    assert.equal(h.resolves.length, 0); assert.deepEqual(h.removed, []);
    assert.equal(h.controller.canRun('pending', action), true);
    assert.deepEqual(h.busyChanges, [true, false]);
  }
});

test('unsupported, missing, disabled and owned actions are blocked before dialogs or requests', async () => {
  const h = harness();
  await h.controller.run('missing', 'DISCARD');
  await h.controller.run('pending', 'UNKNOWN');
  h.options.enabled = false; await h.controller.run('pending', 'DISCARD');
  h.options.enabled = true;
  h.items([item('pending', true)]); await h.controller.run('pending', 'REPLACE');
  h.items([item('pending', true, { mergeId: 'review' })]);
  for (const action of ['KEEP_BOTH', 'SAVE_AS', 'REPLACE', 'DISCARD', 'MERGE']) await h.controller.run('pending', action);
  assert.equal(h.confirmations.length + h.inputs.length + h.resolves.length + h.posts.length, 0);
  assert.equal(h.controller.canRun('pending', 'REVIEW'), true);
  await h.controller.run('pending', 'REVIEW');
  assert.deepEqual(h.opened, ['review']);
});

test('ID locks block cross-action reentry before a React redraw, but do not serialize unrelated IDs', async () => {
  const pending = deferred();
  const h = harness({ resolve: id => id === 'a' ? pending.promise : { removedId: id } });
  h.items([item('a'), item('b')]);
  const first = h.controller.run('a', 'KEEP_BOTH');
  assert.equal(h.controller.busy('a'), true);
  await h.controller.run('a', 'DISCARD');
  await h.controller.run('a', 'KEEP_BOTH');
  await h.controller.run('b', 'KEEP_BOTH');
  assert.deepEqual(h.resolves.map(call => call[0]), ['a', 'b']);
  assert.equal(h.confirmations.length, 0);
  pending.resolve({ removedId: 'a' }); await first;
  assert.deepEqual(h.removed, ['b', 'a']); assert.equal(h.controller.busy('a'), false);
});

test('confirmation revalidates context, ownership, existence and enabled state before submitting', async () => {
  for (const change of [h => { h.options.contextKey = 'other'; }, h => h.items([]),
    h => h.items([item('pending', true, { mergeId: 'owner' })]), h => { h.options.enabled = false; },
    h => h.controller.dispose()]) {
    const answer = deferred(); const h = harness({ confirm: () => answer.promise });
    const running = h.controller.run('pending', 'DISCARD');
    change(h); answer.resolve(true); await running;
    assert.equal(h.resolves.length, 0); assert.deepEqual(h.removed, []);
    assert.equal(h.controller.busy('pending'), false);
  }
});

test('a late submitted success cannot update a new query or a disposed/reactivated view', async () => {
  for (const change of [h => { h.options.contextKey = 'other'; }, h => h.controller.dispose(),
    h => { h.controller.dispose(); h.controller.activate(); }]) {
    const answer = deferred(); const h = harness({ resolve: () => answer.promise });
    const running = h.controller.run('pending', 'KEEP_BOTH');
    change(h); answer.resolve({ removedId: 'pending' }); await running;
    assert.equal(h.resolves.length, 1); assert.deepEqual(h.removed, []);
  }
});

test('single resolution failure retains the item and unlocks it for a deliberate retry', async () => {
  const failure = new Error('Target changed');
  const h = harness({ resolve: () => { throw failure; } });
  await h.controller.run('pending', 'REPLACE');
  assert.equal(h.errors.length, 1); assert.equal(h.errors[0].reason, failure);
  assert.equal(h.controller.busy('pending'), false); assert.deepEqual(h.removed, []);
});

test('merge preparation uses the original API, tracks listing refresh and preserves Shell auto-open ownership', async () => {
  for (const status of ['COMPLETE', 'PENDING']) {
    const h = harness({ poll: () => [{ active: false, status, resultReference: status === 'PENDING' ? 'review' : null }] });
    h.items([item('pending & plus', true)]);
    await h.controller.run('pending & plus', 'MERGE'); await flush();
    assert.equal(h.posts.length, 1);
    assert.equal(h.posts[0][0], '/api/v1/files/directory-merges/pending/pending%20%26%20plus');
    assert.equal(h.tracked.length, 1);
    assert.equal(h.tracked[0][1].announceStart, true); assert.equal(h.tracked[0][1].refreshUrl, '/files?path=photos');
    assert.equal(h.started, 1); assert.deepEqual(h.opened, []);
    assert.deepEqual(h.events, ['endervault:notifications-changed']);
    assert.equal(h.controller.busy('pending & plus'), false); assert.equal(h.errors.length, 0);
  }
});

test('merge polling survives a row gaining an owner or disappearing from the filtered results', async () => {
  let calls = 0;
  const h = harness({ poll: () => ++calls === 1 ? [{ active: true }] : [{ active: false, status: 'PENDING', resultReference: 'review' }] });
  h.items([item('pending', true)]);
  await h.controller.run('pending', 'MERGE'); await flush();
  h.items([item('pending', true, { mergeId: 'review' })]);
  assert.equal(h.controller.preparing('pending'), true); assert.equal(h.controller.canRun('pending', 'REVIEW'), false);
  await h.controller.run('pending', 'REVIEW'); assert.deepEqual(h.opened, []);
  h.items([]); await h.tick();
  assert.equal(h.polls.length, 2); assert.equal(h.controller.busy('pending'), false);
  assert.equal(h.events.length, 1); assert.equal(h.posts.length, 1);
});

test('disposal cancels only local timers/reads and ignores late task status, never the submitted server task', async () => {
  const answer = deferred(); const h = harness({ poll: () => answer.promise });
  h.items([item('pending', true)]);
  await h.controller.run('pending', 'MERGE');
  h.controller.dispose();
  assert.equal(h.polls[0].signal.aborted, true);
  answer.resolve([{ active: false, status: 'PENDING', resultReference: 'review' }]); await flush();
  assert.deepEqual(h.events, []); assert.deepEqual(h.opened, []); assert.equal(h.errors.length, 0);
  assert.equal(h.posts.length, 1); assert.equal(h.tracked.length, 1);
  const scheduled = harness({ poll: () => [{ active: true }] }); scheduled.items([item('pending', true)]);
  await scheduled.controller.run('pending', 'MERGE'); await flush();
  assert.equal(scheduled.timers.size, 1); scheduled.controller.dispose(); assert.equal(scheduled.timers.size, 0);
});

test('a late merge submission still registers its server task but cannot close/open a newer view', async () => {
  const answer = deferred(); const h = harness({ post: () => answer.promise });
  h.items([item('pending', true)]);
  const running = h.controller.run('pending', 'MERGE'); h.controller.dispose();
  answer.resolve({ id: 'task' }); await running;
  assert.equal(h.tracked.length, 1); assert.equal(h.started, 0); assert.equal(h.polls.length, 0);
  assert.deepEqual(h.opened, []);
});

test('preparation request and task read/terminal failures release locks with the original error messages', async () => {
  for (const overrides of [{ post: () => { throw new Error('Post failed'); } },
    { poll: () => { throw new Error('Offline'); } }, { poll: () => [{ active: false, status: 'FAILED', message: 'Scan failed' }] }]) {
    const h = harness(overrides); h.items([item('pending', true)]);
    await h.controller.run('pending', 'MERGE'); await flush();
    assert.equal(h.errors.length, 1); assert.equal(h.controller.busy('pending'), false);
    assert.deepEqual(h.removed, []); assert.deepEqual(h.opened, []);
  }
});

test('the shared hook keeps one controller across polling and gives query revisits fresh context tokens', async () => {
  const slots = [], effects = [];
  let cursor = 0, disposed = 0, redraws = 0, read;
  const controller = { activate() {}, dispose: () => disposed++ };
  const react = {
    useState: () => { cursor++; return [0, () => redraws++]; },
    useRef: initial => { const index = cursor++; return slots[index] ??= { current: initial }; },
    useEffect: callback => { if (!effects.length) effects.push(callback); },
  };
  const module = { exports: {} };
  vm.runInNewContext(hookCode, { module, exports: module.exports, require: id => {
    if (id === 'react') return react;
    if (id.includes('DecisionDialogContext')) return { useDecisionDialog: () => ({ openMerge() {} }) };
    if (id.includes('pending-decision-controller')) return { createPendingDecisionController: (reader, changed) => {
      assert.equal(read, undefined); read = reader; changed(); return controller;
    } };
    assert.fail(`Unexpected hook dependency: ${id}`);
  } });
  const render = (contextKey, items) => { cursor = 0; return module.exports.usePendingDecisionActions({
    contextKey, items, enabled: true, resolved() {},
  }); };
  assert.equal(render('a', [item()]), controller);
  const initialContext = read().contextKey;
  const cleanup = effects[0]();
  assert.equal(render('a', [item('pending', true, { mergeId: 'review' })]), controller);
  assert.equal(read().decision('pending').mergeId, 'review'); assert.equal(read().contextKey, initialContext);
  render('b', []); render('a', [item()]);
  assert.notEqual(read().contextKey, initialContext);
  assert.equal(redraws, 1); cleanup(); assert.equal(disposed, 1);
});

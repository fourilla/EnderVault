import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

async function load(entry, globals, imports = {}) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(entry, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports,
    require: (id) => imports[id] || Object.entries(imports).find(([key]) => id.includes(key))?.[1] || {}, ...globals });
  return module.exports;
}

test('directory merge choices preserve pending discard and type-conflict restrictions', async () => {
  const api = await load('../src/directory-merges/merge-api.ts', {});
  assert.equal(api.mergeChoices('COPY', 'FILE_CONFLICT').join(','), 'OVERWRITE,KEEP_BOTH,SKIP');
  assert.equal(api.mergeChoices('MOVE', 'TYPE_CONFLICT').join(','), 'KEEP_BOTH,SKIP');
  assert.equal(api.mergeChoices('PENDING', 'TYPE_CONFLICT').join(','), 'KEEP_BOTH,DISCARD_UPLOAD');
});

test('bulk choice buttons request server-wide choices and never execute the merge', async () => {
  for (const [operation, conflict, expected] of [
    ['COPY', 'FILE_CONFLICT', ['OVERWRITE', 'KEEP_BOTH', 'SKIP']],
    ['PENDING', 'FILE_CONFLICT', ['OVERWRITE', 'KEEP_BOTH', 'DISCARD_UPLOAD']],
    ['MOVE', 'TYPE_CONFLICT', ['KEEP_BOTH', 'SKIP']],
  ]) {
    const saved = [];
    const data = { review: { id: 'review', operation, revision: 7, editable: true },
      entries: { items: [{ id: 'a', conflict }, { id: 'b', conflict }], total: 100, size: 50 } };
    const jsx = (type, props) => ({ type, props });
    const react = { useEffect: () => {}, useState: (value) => [value === null ? data : value, () => {}],
      createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
    const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react }, {
      react, 'react/jsx-runtime': { jsx, jsxs: jsx }, AppDialog: { AppDialog: 'dialog' },
      'merge-api': { mergeChoices: () => expected, runMerge: () => assert.fail('bulk choices must not execute'),
        saveAllMergeChoices: async (...args) => saved.push(args),
        saveMergeChoices: () => assert.fail('bulk must not send current page IDs') },
    });
    const buttons = [];
    const visit = (node, inside = false) => {
      if (Array.isArray(node)) return node.forEach((child) => visit(child, inside));
      if (!node || typeof node !== 'object') return;
      inside ||= node.props?.className === 'directory-merge-bulk';
      if (inside && node.type === 'button') buttons.push(node);
      if (inside) assert.notEqual(node.type, 'select');
      visit(node.props?.children, inside);
    };
    visit(component.DirectoryMergeDialog({ id: 'review', close: () => {}, changed: () => {} }));
    assert.equal(buttons.length, expected.length);
    for (const button of buttons) button.props.onClick();
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(saved.length, expected.length);
    saved.forEach(([id, revision, choices], index) => {
      assert.equal(id, 'review'); assert.equal(revision, 7);
      assert.equal(choices, expected[index]);
    });
  }
});

test('paused copy shows remaining work without choice controls and resumes copy explicitly', async () => {
  const data = { review: { operation: 'COPY', title: 'Directory copy', statusLabel: 'Copy paused (publication)',
    editable: false, fullyReviewed: true, run: { phase: 'PUBLISHING', paused: true } }, executionView: true,
    entries: { items: [{ id: 'a', relativePath: 'a.txt', stage: 'PUBLICATION_PENDING' },
      { id: 'b', relativePath: 'b.txt', stage: 'FINALIZATION_PENDING' }], total: 2, size: 50 } };
  const jsx = (type, props) => ({ type, props });
  const react = { useEffect: () => {}, useState: (value) => [value === null ? data : value, () => {}],
    createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react }, {
    react, 'react/jsx-runtime': { jsx, jsxs: jsx }, AppDialog: { AppDialog: 'dialog' },
    'merge-api': { mergeChoices: () => [], runMerge: () => assert.fail('must not auto-resume') },
  });
  const tree = component.DirectoryMergeDialog({ id: 'review', close: () => {}, changed: () => {} });
  const texts = []; const selects = [];
  const visit = (node) => {
    if (Array.isArray(node)) return node.forEach(visit);
    if (typeof node === 'string') { texts.push(node); return; }
    if (!node || typeof node !== 'object') return;
    if (node.type === 'select') selects.push(node);
    visit(node.props?.children);
  };
  visit(tree);
  assert.equal(selects.length, 0);
  assert.match(texts.join(' '), /Directory copy.*Copy paused.*2 remaining items/);
  assert.ok(texts.includes('Publication pending'));
  assert.ok(texts.includes('Finalization pending'));
  assert.ok(texts.includes('Resume copy'));
  assert.ok(!texts.includes('No conflicts.'));
});

test('apply stays disabled until the server reports every page fully reviewed', async () => {
  for (const fullyReviewed of [false, true]) {
    const data = { review: { operation: 'COPY', editable: true, fullyReviewed },
      entries: { items: [{ id: 'a', conflict: 'FILE_CONFLICT', choice: 'SKIP' }], total: 100, size: 50 } };
    const jsx = (type, props) => ({ type, props });
    const react = { useEffect: () => {}, useState: (value) => [value === null ? data : value, () => {}],
      createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
    const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react }, {
      react, 'react/jsx-runtime': { jsx, jsxs: jsx }, AppDialog: { AppDialog: 'dialog' },
      'merge-api': { mergeChoices: () => ['SKIP'] },
    });
    const buttons = [];
    const visit = (node) => {
      if (Array.isArray(node)) return node.forEach(visit);
      if (!node || typeof node !== 'object') return;
      if (node.type === 'button') buttons.push(node);
      visit(node.props?.children);
    };
    visit(component.DirectoryMergeDialog({ id: 'review', close: () => {}, changed: () => {} }));
    const apply = buttons.find((button) => [button.props.children].flat().includes('Apply copy'));
    assert.ok(apply);
    assert.equal(apply.props.disabled, !fullyReviewed);
    if (!fullyReviewed) assert.match(apply.props.title, /every conflict/);
  }
});

test('abandonment needs explicit nested confirmation and is unavailable for started transfers', async () => {
  for (const [canAbandon, confirmed, operation] of [[true, false, 'COPY'], [true, true, 'COPY'], [false, true, 'COPY'], [true, true, 'PENDING']]) {
    let abandoned = 0, closed = 0, changed = 0;
    const data = { review: { operation, editable: canAbandon, canAbandon, fullyReviewed: false },
      entries: { items: [], total: 0, size: 50 } };
    const jsx = (type, props) => ({ type, props });
    const react = { useEffect: () => {}, useState: (value) => [value === null ? data : value, () => {}],
      createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
    const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react,
      window: { EnderVault: { askConfirmation: async (options) => {
        assert.equal(options.nested, true);
        if (operation === 'PENDING') assert.match(options.message, /Uploaded files will stay in Pending decisions/);
        return confirmed;
      } } } }, {
      react, 'react/jsx-runtime': { jsx, jsxs: jsx }, AppDialog: { AppDialog: 'dialog' },
      'merge-api': { mergeChoices: () => [], abandonMerge: async () => { abandoned++; } },
    });
    const buttons = [];
    const visit = (node) => {
      if (Array.isArray(node)) return node.forEach(visit);
      if (!node || typeof node !== 'object') return;
      if (node.type === 'button') buttons.push(node);
      visit(node.props?.children);
    };
    visit(component.DirectoryMergeDialog({ id: 'review', close: () => closed++, changed: () => changed++ }));
    const button = buttons.find((item) => [item.props.children].flat().includes('Abandon transfer'));
    assert.equal(Boolean(button), canAbandon);
    button?.props.onClick();
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(abandoned, canAbandon && confirmed ? 1 : 0);
    assert.equal(closed, abandoned);
    assert.equal(changed, abandoned);
  }
});

test('remaining transfer abandonment confirms preservation and never offers resume during cleanup', async () => {
  for (const operation of ['COPY', 'MOVE', 'PENDING']) for (const phase of ['PUBLISHING', 'ABANDONING']) {
    let stopped = 0, confirmed = 0, closed = 0;
    const data = { review: { operation, editable: false, canAbandonRemainingTransfer: true,
      fullyReviewed: true, run: { phase, paused: phase === 'PUBLISHING' } }, executionView: true,
      entries: { items: [], total: 0, size: 50 } };
    const jsx = (type, props) => ({ type, props });
    const react = { useEffect: () => {}, useState: (value) => [value === null ? data : value, () => {}],
      createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
    const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react,
      window: { EnderVault: { askConfirmation: async (options) => {
        confirmed++; assert.equal(options.nested, true);
        assert.match(options.message, operation === 'PENDING' ? /staged upload will return to Pending decisions/
          : operation === 'MOVE' ? /No more originals will be deleted/ : /Original files and already copied files will be kept/); return true;
      } } } }, { react, 'react/jsx-runtime': { jsx, jsxs: jsx }, AppDialog: { AppDialog: 'dialog' },
      'merge-api': { mergeChoices: () => [], abandonRemainingTransfer: async () => { stopped++; } } });
    const buttons = [];
    const visit = (node) => {
      if (Array.isArray(node)) return node.forEach(visit);
      if (!node || typeof node !== 'object') return;
      if (node.type === 'button') buttons.push(node);
      visit(node.props?.children);
    };
    visit(component.DirectoryMergeDialog({ id: 'review', close: () => closed++, changed: () => {} }));
    const action = operation === 'PENDING' ? 'merge' : operation.toLowerCase();
    const label = phase === 'ABANDONING' ? 'Finish abandonment' : `Abandon remaining ${action}`;
    buttons.find(b => [b.props.children].flat().includes(label)).props.onClick();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(stopped, 1); assert.equal(closed, 1);
    assert.equal(confirmed, phase === 'ABANDONING' ? 0 : 1);
    if (phase === 'ABANDONING') assert.equal(buttons.some(b => [b.props.children].flat().includes(`Resume ${action}`)), false);
  }
});

test('saving merge choices sends CSRF and revision but never source snapshots', async () => {
  let sent;
  const api = await load('../src/directory-merges/merge-api.ts', {
    window: { EnderVault: { csrfPair: () => ({ value: 'token' }), requestJson: async (...args) => { sent = args; } } },
    document: { querySelector: () => ({ content: 'X-CSRF-CUSTOM' }) },
  });
  await api.saveMergeChoices('plan', 7, { item: 'SKIP' });
  assert.equal(sent[0], '/api/v1/files/directory-merges/plan/choices');
  assert.equal(sent[1].headers['X-CSRF-CUSTOM'], 'token');
  assert.deepEqual(JSON.parse(sent[1].body), { revision: 7, choices: { item: 'SKIP' } });
  await api.saveAllMergeChoices('plan', 8, 'KEEP_BOTH');
  assert.equal(sent[0], '/api/v1/files/directory-merges/plan/choices/all');
  assert.equal(sent[1].headers['X-CSRF-CUSTOM'], 'token');
  assert.deepEqual(JSON.parse(sent[1].body), { revision: 8, choice: 'KEEP_BOTH' });
});

test('closing a merge dialog only dismisses it and never resolves or executes', async () => {
  let closed = 0;
  let mutations = 0;
  const jsx = (type, props) => ({ type, props });
  const react = { useEffect: () => {}, useState: (value) => [value, () => {}],
    createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react }, {
    react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    AppDialog: { AppDialog: 'dialog' },
    'merge-api': { runMerge: () => mutations++, saveMergeChoices: () => mutations++ },
  });
  const tree = component.DirectoryMergeDialog({ id: 'plan', close: () => closed++, changed: () => {} });
  tree.props.onDismiss();
  const visit = (node) => {
    if (Array.isArray(node)) return node.forEach(visit);
    if (!node || typeof node !== 'object') return;
    if (node.type === 'button' && node.props['aria-label'] === 'Close') node.props.onClick();
    visit(node.props?.children);
  };
  visit(tree);
  assert.equal(closed, 2);
  assert.equal(mutations, 0);
});

test('merge execution reuses listing refresh while replanning does not refresh files', async () => {
  const tracked = [];
  const requests = [];
  const api = await load('../src/directory-merges/merge-api.ts', {
    CustomEvent: class {},
    window: { dispatchEvent: () => {}, EnderVaultServerTasks: { track: (...args) => tracked.push(args) } },
  }, { 'form-api': { postForm: async (...args) => { requests.push(args); return { id: 'task' }; } } });
  const review = { id: 'review', revision: 3, destinationPath: 'target/photos' };
  await api.runMerge(review, false);
  assert.equal(tracked[0][1].refreshUrl, '/files?path=target');
  assert.equal(requests[0][1].revision, 3);
  await api.runMerge(review, true);
  assert.equal(tracked[1][1].refreshUrl, undefined);
  await api.runMerge({ ...review, destinationPath: 'photos' }, false);
  assert.equal(tracked[2][1].refreshUrl, '/files?path=');
});

test('late review response cannot repopulate a dismissed or replaced dialog', async () => {
  const effects = [];
  const writes = [];
  let resolve;
  const response = new Promise((done) => { resolve = done; });
  const jsx = (type, props) => ({ type, props });
  const react = { useEffect: (effect) => effects.push(effect), useState: (value) => [value, (next) => writes.push(next)],
    createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react, AbortController }, {
    react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    AppDialog: { AppDialog: 'dialog' }, 'merge-api': { mergeGet: () => response },
  });
  component.DirectoryMergeDialog({ id: 'old-review', close: () => {}, changed: () => {} });
  const cleanup = effects[0]();
  writes.length = 0;
  cleanup();
  resolve({ review: { id: 'old-review' } });
  await response;
  await Promise.resolve();
  assert.deepEqual(writes, []);
});

test('pending row renders the same preparation action while its controller awaits an owner task', async () => {
  const jsx = (type, props) => ({ type, props });
  const policy = await load('../src/pending-decisions/pending-decision-actions.ts', {});
  const react = { createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const component = await load('../src/pending-decisions/PendingDecisionActions.tsx', { React: react }, {
    react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    'pending-decision-actions': policy,
  });
  const decision = { id: 'pending', directory: true, originalFilename: 'photos', destinationPath: '' };
  const actions = { preparing: () => true, canRun: () => false };
  for (const current of [decision, { ...decision, mergeId: 'review' }]) {
    const tree = component.PendingDecisionActions({ decision: current, actions });
    const button = tree.props.children[0];
    assert.equal(button.props['aria-label'], 'Merge directory');
    assert.equal(button.props.disabled, true);
    assert.equal(button.props.children[0].props.className, 'fas fa-spinner fa-spin');
  }
});

test('the unified pending page has one table and no page-owned review dialog', async () => {
  const jsx = (type, props) => ({ type, props });
  let stateIndex = 0;
  const react = { useEffect: () => {}, useMemo: (factory) => factory(),
    useState: (value) => [stateIndex++ === 1 ? { query: '', decisions: [] } : value, () => {}],
    createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const component = await load('../src/pending-decisions/PendingDecisionsApp.tsx', { React: react }, {
    react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    useHashTarget: { useHashTarget: () => {} },
    'react-router-dom': { useSearchParams: () => [new URLSearchParams(), () => {}] },
    RouteSearch: { useRouteSearch: () => {} },
    StableTable: { StableTable: 'table' },
    useItemSelection: { useItemSelection: () => ({ selected: new Set(), selectedItems: [],
      selectAll() {}, clearSelection() {} }) },
    useSelectionShortcuts: { useSelectionShortcuts() {} },
    useBrowserContextMenu: { useBrowserContextMenu() {} },
    ListingHistoryContext: { useLocationGuard: () => () => true },
    'pending-decision-menu-actions': { pendingDecisionMenuActions: () => [] },
    usePendingDecisionActions: { usePendingDecisionActions: () => ({}) },
  });
  const tree = component.PendingDecisionsApp();
  const tables = [];
  const visit = (node) => {
    if (Array.isArray(node)) return node.forEach(visit);
    if (!node || typeof node !== 'object') return;
    if (node.type === 'table') tables.push(node);
    visit(node.props?.children);
  };
  visit(tree);
  assert.equal(tables.length, 1);
});

test('closing a review replaces its hash without navigation history or scroll reset', async () => {
  const navigations = [];
  const jsx = (type, props) => ({ type, props });
  const react = { useEffect: () => {}, useState: () => [{ kind: 'merge', id: 'review' }, () => {}],
    useRef: () => ({ current: 'review' }), useCallback: (fn) => fn, createContext: () => ({ Provider: 'provider' }),
    createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const component = await load('../src/pending-decisions/DecisionDialogContext.tsx', { React: react }, {
    react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    'react-router-dom': {
      useLocation: () => ({ pathname: '/admin/pending-decisions', search: '?test=1', hash: '#merge-review' }),
      useNavigate: () => (...args) => navigations.push(args),
    }, TopbarPopoverContext: { useTopbarPopover: () => ({ closeAll: () => {} }) },
    DirectoryMergeDialog: { DirectoryMergeDialog: 'review-dialog' },
    'decision-auto-open': { observeNewDecisions: () => () => {} },
  });
  const visit = (node) => {
    if (Array.isArray(node)) return node.forEach(visit);
    if (!node || typeof node !== 'object') return;
    if (node.type === 'review-dialog') node.props.close();
    visit(node.props?.children);
  };
  visit(component.DecisionDialogProvider({ children: null }));
  assert.equal(navigations.length, 1);
  assert.equal(navigations[0][0], '/admin/pending-decisions?test=1');
  assert.equal(navigations[0][1].replace, true);
  assert.equal(navigations[0][1].preventScrollReset, true);
});

test('completed and missing reviews dismiss, while network failures remain retryable', async () => {
  for (const outcome of ['complete', 'missing', 'network']) {
    let index = 0;
    let effects = [];
    const values = [];
    let closed = 0;
    let changed = 0;
    const jsx = (type, props) => ({ type, props });
    const react = { useEffect: (effect) => effects.push(effect), useState: (initial) => {
      const key = index++;
      if (!(key in values)) values[key] = initial;
      return [values[key], (next) => { values[key] = next; }];
    }, createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
    const component = await load('../src/directory-merges/DirectoryMergeDialog.tsx', { React: react, AbortController }, {
      react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
      AppDialog: { AppDialog: 'dialog' }, 'merge-api': {
        mergeChoices: () => [], mergeGet: async () => {
          if (outcome === 'complete') return { review: { run: { phase: 'COMPLETE' } }, entries: { items: [] } };
          throw Object.assign(new Error('unavailable'), { status: outcome === 'missing' ? 404 : 500 });
        },
      },
    });
    const props = { id: 'review', close: () => closed++, changed: () => changed++ };
    component.DirectoryMergeDialog(props);
    const cleanup = effects[0]();
    await new Promise((resolve) => setImmediate(resolve));
    index = 0; effects = [];
    const tree = component.DirectoryMergeDialog(props);
    effects[1]();
    assert.equal(closed, outcome === 'network' ? 0 : 1);
    assert.equal(changed, closed);
    assert.equal(tree === null, outcome !== 'network');
    cleanup();
  }
});

test('merge task completion refreshes shell notifications immediately and cleans up its listener', async () => {
  const effects = [];
  const document = new EventTarget();
  let refreshed = 0;
  const react = { createContext: () => ({ Provider: 'provider' }), useEffect: (effect) => effects.push(effect),
    createElement: (type, props) => ({ type, props }) };
  const component = await load('../src/app/ShellStatusContext.tsx', { React: react, document }, {
    react, 'react/jsx-runtime': { jsx: (type, props) => ({ type, props }) },
    AdminAppContext: { useAdminApp: () => ({ bootstrap: {} }) },
    usePolledJson: { usePolledJson: () => ({ refresh: () => refreshed++ }) },
  });
  component.ShellStatusProvider({ children: null });
  const cleanup = effects[0]();
  const emit = (type) => document.dispatchEvent(Object.assign(new Event('endervault:task-terminal'), { detail: { type } }));
  emit('DIRECTORY_MERGE'); emit('FILE_COPY'); emit('FILE_MOVE'); emit('FILE_TRASH');
  assert.equal(refreshed, 3);
  cleanup(); emit('DIRECTORY_MERGE');
  assert.equal(refreshed, 3);
});

test('shell review requests preserve the page and never replace an already open review', async () => {
  let selected = null;
  const current = { current: null };
  const effects = [];
  const navigations = [];
  let popoverCloses = 0;
  const jsx = (type, props) => ({ type, props });
  const react = { createContext: () => ({ Provider: 'provider' }), useCallback: (fn) => fn,
    useEffect: (effect) => effects.push(effect), useRef: () => current,
    useState: () => [selected, (next) => { selected = next; }],
    createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const location = { pathname: '/files', search: '?path=photos', hash: '' };
  const component = await load('../src/pending-decisions/DecisionDialogContext.tsx', { React: react }, {
    react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    'react-router-dom': { useLocation: () => location, useNavigate: () => (...args) => navigations.push(args) },
    TopbarPopoverContext: { useTopbarPopover: () => ({ closeAll: () => popoverCloses++ }) },
    DirectoryMergeDialog: { DirectoryMergeDialog: 'review-dialog' },
  });
  let tree = component.DecisionDialogProvider({ children: 'files-content' });
  effects[1]();
  assert.equal(selected, null);
  assert.equal(tree.props.value.openMerge('first'), true);
  assert.equal(tree.props.value.openMerge('second'), false);
  assert.equal(selected.id, 'first');
  assert.equal(popoverCloses, 1);
  tree = component.DecisionDialogProvider({ children: 'files-content' });
  assert.equal(tree.props.children[0], 'files-content');
  tree.props.children[1].props.close();
  assert.equal(selected, null);
  assert.deepEqual(navigations, []);
  assert.equal(tree.props.value.openMerge('second'), true);
  location.pathname = '/admin/pending-decisions'; location.search = ''; location.hash = '#merge-deep-link';
  effects.length = 0;
  component.DecisionDialogProvider({ children: null });
  effects[1]();
  assert.equal(selected.id, 'deep-link');
  location.pathname = '/files'; location.hash = '';
  effects.length = 0;
  component.DecisionDialogProvider({ children: null });
  effects[1]();
  assert.equal(selected, null);
});

test('notification bell navigates but a typed merge item opens locally without parsing its href', async () => {
  const navigations = [];
  const opened = [];
  let prevented = 0;
  const jsx = (type, props) => ({ type, props });
  const react = { createElement: (type, props, ...children) => jsx(type, { ...props, children }) };
  const component = await load('../src/app/NotificationCenterControl.tsx', { React: react }, {
    'react/jsx-runtime': { jsx, jsxs: jsx },
    ShellStatusContext: { useShellStatus: () => ({ notifications: { data: {
      actionableCount: 2, reviewAllHref: '/admin/pending-decisions', items: [
        { id: 'one', href: '/unrelated-link', target: { kind: 'DIRECTORY_MERGE', id: 'review' } },
        { id: 'two', href: '/admin/pending-decisions#decision-pending', target: { kind: 'PENDING_FILE_DECISION', id: 'pending' } },
      ],
    } } }) },
    'react-router-dom': { useNavigate: () => (...args) => navigations.push(args) },
    DecisionDialogContext: { useDecisionDialog: () => ({ openMerge: (id) => opened.push(id), openPending: (id) => opened.push(id) }) },
    TopbarPopoverContext: { useTopbarPopover: () => ({ closeAll: () => {} }) },
    AppNavigationLink: { AppNavigationLink: 'link' }, ShellPopover: { ShellPopover: 'popover' },
  });
  const tree = component.NotificationCenterControl();
  const links = [];
  const visit = (node) => {
    if (Array.isArray(node)) return node.forEach(visit);
    if (!node || typeof node !== 'object') return;
    if (node.type === 'link' && node.props.className === 'notification-center-item') links.push(node);
    visit(node.props?.children);
  };
  visit(tree);
  const event = { button: 0, preventDefault: () => prevented++ };
  links[0].props.onClick(event);
  assert.deepEqual(opened, ['review']);
  assert.equal(prevented, 1);
  assert.deepEqual(navigations, []);
  links[0].props.onClick({ ...event, ctrlKey: true });
  links[1].props.onClick(event);
  assert.equal(prevented, 2);
  assert.deepEqual(opened, ['review', 'pending']);
  tree.props.onTriggerClick();
  assert.equal(navigations[0][0], '/admin/pending-decisions');
});

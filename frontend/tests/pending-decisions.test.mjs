import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

test('pending directories hide replacement while files retain it', async () => {
  const result = await build({
    configFile: false,
    logLevel: 'silent',
    build: {
      write: false,
      minify: false,
      lib: { entry: fileURLToPath(new URL('../src/pending-decisions/PendingDecisionActions.tsx', import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) },
    },
  });
  const output = (Array.isArray(result) ? result[0] : result).output;
  const code = output.find((item) => item.type === 'chunk').code;
  for (const directory of [false, true]) {
    const calls = [];
    const decision = { id: 'pending-1', directory, originalFilename: 'photos.v1' };
    const jsx = (type, props) => ({ type, props });
    const react = {
      useState: (initial) => [initial === null ? [decision] : initial, () => {}],
      useEffect: () => {},
      createElement: (type, props, ...children) => jsx(type, { ...props, children }),
    };
    const module = { exports: {} };
    vm.runInNewContext(code, {
      module, exports: module.exports, React: react,
      window: { EnderVault: { askConfirmation: async (options) => { calls.push(options); return false; } } },
      require: (id) => {
        if (id === 'react') return react;
        if (id === 'react-router-dom') return { useLocation: () => ({ hash: '', key: 'page' }), useNavigate: () => () => {} };
        if (id === 'react/jsx-runtime') return { jsx, jsxs: jsx, Fragment: 'fragment' };
        if (id.includes('useHashTarget')) return { useHashTarget: () => {} };
        if (id.includes('BrowserEntries')) return { icon: () => null };
        if (id.includes('PageHeader')) return { PageHeader: () => null };
        return {};
      },
    });
    const buttons = [];
    const visit = (node) => {
      if (Array.isArray(node)) return node.forEach(visit);
      if (!node || typeof node !== 'object') return;
      if (node.type === 'button') buttons.push(node);
      visit(node.props?.children);
    };
    visit(module.exports.PendingDecisionActions({ decision, resolved: () => {} }));
    assert.equal(buttons.some((button) => button.props.title === 'Replace existing file'), !directory);
    assert.ok(buttons.some((button) => button.props.title === 'Keep both'));
    assert.ok(buttons.some((button) => button.props.title === 'Save as'));
    buttons.find((button) => button.props.title === 'Discard staged item').props.onClick();
    assert.match(calls[0].message, directory ? /directory and its contents/ : /staged file/);
  }
});

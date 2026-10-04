import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import * as React from 'react';
import * as jsx from 'react/jsx-runtime';

const pages = [
  { file: 'file-requests/FileRequestsApp', name: 'FileRequestsApp', load: 'loadFileRequests', token: 3,
    payload: { defaults: { title: 'Default', maxFiles: 10 } }, draft: 1 },
  { file: 'activity-logs/ActivityLogsApp', name: 'ActivityLogsApp', load: 'loadActivityLogs', token: 3,
    payload: { query: { text: 'Applied', size: 50 }, selectedFile: 'today' }, draft: 1 },
  { file: 'metadata/MetadataInspectorApp', name: 'MetadataInspectorApp', load: 'loadMetadataInspector', token: 4,
    payload: { areas: [{ name: 'drafts' }] }, draft: 1 },
  { file: 'sessions/ActiveSessionsApp', name: 'ActiveSessionsApp', load: 'loadActiveSessions', token: 4,
    payload: { sessions: [] } },
  { file: 'vpn/VpnStatusApp', name: 'VpnStatusApp', load: 'loadVpnStatus', token: 3,
    payload: { publicIp: 'Unavailable' } },
];

for (const page of pages) {
  const result = await build({ configFile: false, logLevel: 'silent', build: {
    write: false, minify: false,
    lib: { entry: fileURLToPath(new URL(`../src/${page.file}.tsx`, import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) },
  } });
  const code = (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;
  test(`${page.name} retries only its read request and ignores aborted responses`, async () => {
    const states = [], refs = [], effects = [], requests = [];
    let stateIndex = 0, refIndex = 0;
    const location = { search: '?q=applied' };
    const Panel = () => null;
    const module = { exports: {} };
    const react = {
      useState(initial) {
        const slot = stateIndex++;
        if (!(slot in states)) states[slot] = initial;
        return [states[slot], value => { states[slot] = typeof value === 'function' ? value(states[slot]) : value; }];
      },
      useRef(initial) { const slot = refIndex++; return refs[slot] ??= { current: initial }; },
      useEffect(run, deps) { effects.push({ run, deps }); },
    };
    vm.runInNewContext(code, { module, exports: module.exports, Error, AbortController, URLSearchParams,
      require(id) {
        if (id === 'react') return react;
        if (id === 'react/jsx-runtime') return jsx;
        if (id === 'react-router-dom') return { useLocation: () => location, useNavigate: () => () => assert.fail('no navigation'), Link: () => null,
          useSearchParams: () => [new URLSearchParams(location.search), () => assert.fail('no navigation')] };
        if (id.endsWith('/RouteSearch')) return { useRouteSearch() {} };
        if (id.endsWith('/PageErrorPanel')) return { PageErrorPanel: Panel };
        if (id.endsWith('/AdminAppContext')) return { useAdminApp: () => ({ refreshBootstrap: () => assert.fail('no mutation') }) };
        if (id.endsWith('/useHashTarget')) return { useHashTarget() {} };
        if (id.endsWith('/BrowserEntries')) return { icon: () => null };
        if (id.endsWith('-api')) return new Proxy({}, { get(_target, key) {
          if (key === page.load) return (...args) => new Promise((resolve, reject) => requests.push({ args, resolve, reject }));
          return () => assert.fail(`Unexpected action: ${String(key)}`);
        } });
        return {};
      },
    });
    const render = () => { stateIndex = 0; refIndex = 0; effects.length = 0; return module.exports[page.name](); };
    const find = node => {
      if (!React.isValidElement(node)) return undefined;
      if (node.type === Panel) return node;
      return React.Children.toArray(node.props.children).map(find).find(Boolean);
    };
    const settle = () => new Promise(resolve => setImmediate(resolve));
    render();
    effects[0].run();
    requests[0].reject(new Error('Offline'));
    await settle();
    const panel = find(render());
    assert.equal(panel.props.message, 'Offline');
    assert.equal(panel.props.stale, false);
    panel.props.actions.props.onClick();
    assert.equal(states[page.token], 1);
    render();
    const cleanup = effects[0].run();
    requests[1].resolve(page.payload);
    await settle();
    assert.ok(states[0]);
    const draft = page.draft === undefined ? undefined : { edited: true };
    if (page.draft !== undefined) states[page.draft] = draft;
    // Re-run the same read effect to model a refresh of the current query.
    effects[0].run();
    requests[2].resolve(page.payload);
    await settle();
    if (page.draft !== undefined) assert.equal(states[page.draft], draft);
    const saved = states[0];
    cleanup();
    const canceled = effects[0].run();
    canceled();
    requests[3].resolve({ unexpected: true });
    await settle();
    assert.equal(states[0], saved);
    if (page.load === 'loadFileRequests' || page.load === 'loadActivityLogs') {
      assert.equal(requests[1].args[0], '?q=applied');
    }
  });
}

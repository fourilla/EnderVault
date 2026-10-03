import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { build } from 'vite';
import * as jsx from 'react/jsx-runtime';

const domains = [
  { scope: 'bookmarks', file: 'bookmarks/bookmark-api', page: 'bookmarks/BookmarksApp',
    endpoint: '/api/v1/bookmarks', load: 'loadBookmarks',
    argument: (query) => ({ directoryId: 'folder-id', query }), payload: { links: [], directories: [] } },
  { scope: 'recent', file: 'recent/recent-api', page: 'recent/RecentApp',
    endpoint: '/api/v1/recent', load: 'loadRecentPayload',
    argument: (query) => ({ query, page: 2, hidden: 'hide', sort: 'name', direction: 'asc', pageSize: 50 }),
    payload: { entries: [], directories: [] } },
  { scope: 'sticky-notes', file: 'sticky-notes/sticky-note-catalog-api', page: 'sticky-notes/StickyNoteListApp',
    endpoint: '/api/v1/sticky-notes/catalog', load: 'loadStickyNoteCatalog',
    argument: (query) => query, payload: { notes: [] } },
];

for (const domain of domains) {
  const result = await build({ configFile: false, logLevel: 'silent',
    build: { write: false, minify: false,
      lib: { entry: fileURLToPath(new URL(`../src/${domain.file}.ts`, import.meta.url)), formats: ['cjs'] },
      rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
  });
  const code = (Array.isArray(result) ? result[0] : result).output.find((item) => item.type === 'chunk').code;
  const setup = (fetch) => {
    const module = { exports: {} };
    vm.runInNewContext(code, { module, exports: module.exports, URLSearchParams, fetch,
      require: (id) => {
        assert.equal(id, '../shared/api/form-api');
        return { notify() { assert.fail('Read requests must not emit mutation notifications'); } };
      },
    });
    return module.exports[domain.load];
  };

  test(`${domain.scope} search forwards phrases and typed expressions without extra requests`, async () => {
    const queries = ['"summer holiday"', 'name:"summer holiday"',
      `(name:report || name:manual) ${domain.scope === 'recent' ? 'accessed' : 'updated'}:>=2026-10-03T09:00:00+09:00`,
      '"name:a && (b)||c"'];
    const signal = new AbortController().signal;
    let expected, calls = 0;
    const load = setup(async (url, options) => {
      calls++;
      const parsed = new URL(url, 'https://nas.test');
      assert.equal(parsed.pathname, domain.endpoint);
      assert.equal(parsed.searchParams.get('q'), expected);
      if (domain.scope === 'bookmarks') assert.equal(parsed.searchParams.get('directory'), 'folder-id');
      if (domain.scope === 'recent') {
        assert.equal(parsed.searchParams.get('hidden'), 'hide');
        assert.equal(parsed.searchParams.get('page'), '2');
        assert.equal(parsed.searchParams.get('size'), '50');
      }
      assert.equal(options.signal, signal);
      assert.equal(options.credentials, 'same-origin');
      assert.equal(options.headers.Accept, 'application/json');
      return { ok: true, json: async () => domain.payload };
    });
    for (const query of queries) {
      expected = query;
      assert.equal(await load(domain.argument(query), signal), domain.payload);
    }
    assert.equal(calls, queries.length);
  });

  test(`${domain.scope} displays structured query errors without fallback or retry`, async () => {
    let calls = 0;
    const load = setup(async () => {
      calls++;
      return { ok: false, json: async () => ({ ok: false, position: 2,
        notification: { message: 'Unknown search field: typo' } }) };
    });
    await assert.rejects(load(domain.argument('  typo:value'), new AbortController().signal),
      { message: 'Unknown search field: typo' });
    assert.equal(calls, 1);
  });

  test(`${domain.scope} cancellation keeps its original identity`, async () => {
    const signal = new AbortController().signal;
    const failure = new DOMException('Canceled', 'AbortError');
    const load = setup(async (_url, options) => {
      assert.equal(options.signal, signal);
      throw failure;
    });
    await assert.rejects(load(domain.argument('name:report'), signal), (reason) => reason === failure);
  });
}

test('domain pages register their own server schema and Recent uses the effective hidden preference', () => {
  for (const domain of domains) {
    const source = readFileSync(new URL(`../src/${domain.page}.tsx`, import.meta.url), 'utf8');
    assert.match(source, new RegExp(`schemaScope: '${domain.scope}'`));
    assert.doesNotMatch(source, /useSearchAssistance|SearchSuggestions/, 'Reuse the Shell input assistance');
  }
  const recent = readFileSync(new URL('../src/recent/RecentApp.tsx', import.meta.url), 'utf8');
  assert.match(recent, /suggestionHidden: current.hidden \?\? payload\?\.preferences.hidden \?\? 'hide'/);
});

const catalogPage = await build({ configFile: false, logLevel: 'silent',
  build: { write: false, minify: false,
    lib: { entry: fileURLToPath(new URL('../src/sticky-notes/StickyNoteListApp.tsx', import.meta.url)), formats: ['cjs'] },
    rolldownOptions: { external: (_id, importer) => Boolean(importer) } },
});
const catalogPageCode = (Array.isArray(catalogPage) ? catalogPage[0] : catalogPage).output
  .find((item) => item.type === 'chunk').code;

function catalogHarness() {
  const states = [], effects = [], requests = [];
  let cursor = 0, query = '';
  const module = { exports: {} };
  vm.runInNewContext(catalogPageCode, { module, exports: module.exports, AbortController,
    document: { addEventListener() {}, removeEventListener() {} },
    require(id) {
      if (id === 'react/jsx-runtime') return jsx;
      if (id === 'react') return {
        useState(initial) {
          const slot = cursor++;
          if (!(slot in states)) states[slot] = initial;
          return [states[slot], (next) => {
            states[slot] = typeof next === 'function' ? next(states[slot]) : next;
          }];
        },
        useEffect: (run) => effects.push(run),
      };
      if (id === 'react-router-dom') return {
        useSearchParams: () => [new URLSearchParams({ q: query }), () => {}],
      };
      if (id.endsWith('/RouteSearch')) return { useRouteSearch() {} };
      if (id === './sticky-note-catalog-api') return {
        loadStickyNoteCatalog: (query, signal) => new Promise((resolve, reject) => {
          requests.push({ query, signal, resolve, reject });
        }),
      };
      return {};
    },
  });
  return { states, requests,
    load(next) {
      query = next;
      cursor = 0;
      effects.length = 0;
      module.exports.StickyNoteListApp();
      return effects[1]();
    },
  };
}

for (const outcome of ['resolve', 'reject']) {
  test(`sticky-note search ignores an old ${outcome} after a different query finishes`, async () => {
    const h = catalogHarness();
    const cleanup = h.load('content:old');
    cleanup();
    h.load('content:new');
    assert.equal(h.requests[0].signal.aborted, true);
    const notes = [{ id: 'new' }];
    h.requests[1].resolve({ notes });
    await new Promise(setImmediate);
    if (outcome === 'resolve') h.requests[0].resolve({ notes: [{ id: 'old' }] });
    else h.requests[0].reject(new Error('Old query failed'));
    await new Promise(setImmediate);
    assert.equal(h.states[1], notes);
    assert.equal(h.states[3], '');
  });
}

test('sticky-note search ignores a response delivered after unmount', async () => {
  const h = catalogHarness();
  const cleanup = h.load('name:old');
  cleanup();
  h.requests[0].resolve({ notes: [{ id: 'old' }] });
  await new Promise(setImmediate);
  assert.equal(h.states[1], null);
  assert.equal(h.states[3], '');
});

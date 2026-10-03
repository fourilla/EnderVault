import assert from 'node:assert/strict';
import test from 'node:test';
import { applySuggestion, completionContext, completionHint, quotedValue, suggestionsFor } from '../src/shared/search/query-completion.ts';
import { createSearchAssistanceClient } from '../src/shared/search/search-assistance-api.ts';

const schema = { scope: 'files', defaultFields: ['name'], defaultOperator: 'AND',
  limits: { maxLength: 4096, maxTokens: 256, maxTerms: 128, maxDepth: 16 },
  fields: [
    { key: 'name', label: 'Name', type: 'TEXT', values: [], operators: ['CONTAINS'], timeZone: null },
    { key: 'path', label: 'Path', type: 'PATH', values: [], operators: ['CONTAINS'], timeZone: null },
    { key: 'type', label: 'Entry type', type: 'ENUM', values: ['file', 'directory'], operators: ['EQUALS'], timeZone: null },
    { key: 'modified', label: 'Modified', type: 'DATE_TIME', values: [], operators: ['RANGE'], timeZone: 'Asia/Seoul' },
  ] };
const context = (query, caret = query.length, end = caret) => completionContext(query, caret, end, schema);

test('empty and partial field completion comes only from the server registry', () => {
  assert.deepEqual(suggestionsFor(context(''), schema).map((option) => option.label), ['name:', 'path:', 'type:', 'modified:']);
  assert.equal(suggestionsFor(context('Na'), schema)[0].replacement, 'name:');
  const custom = { ...schema, fields: [{ ...schema.fields[0], key: 'custom', type: 'ENUM', values: ['new-value'] }] };
  assert.equal(suggestionsFor(completionContext('cu', 2, 2, custom), custom)[0].replacement, 'custom:');
  assert.deepEqual(suggestionsFor(completionContext('custom:', 7, 7, custom), custom).map((item) => item.label), ['new-value']);
});

test('field completion changes only the cursor token, retaining later conditions', () => {
  const query = '(na || type:file) path:reports';
  const token = context(query, 3);
  const chosen = applySuggestion(query, token, suggestionsFor(token, schema)[0]);
  assert.deepEqual(chosen, { value: '(name: || type:file) path:reports', caret: 6 });
  const existing = 'type:file name:report';
  assert.deepEqual(applySuggestion(existing, context(existing, 2), suggestionsFor(context(existing, 2), schema)[0]),
    { value: existing, caret: 5 });
});

for (const query of ['type:fi', 'type: fi', 'type:"fi"', 'name:"summer holiday" && (type:fi)']) {
  test(`enum assistance supports the value token: ${query}`, () => {
    const caret = query.endsWith(')') ? query.length - 1 : query.length;
    const token = context(query, caret);
    assert.equal(token.field.key, 'type');
    assert.equal(token.prefix, 'fi');
    const options = suggestionsFor(token, schema);
    assert.deepEqual(options.map((item) => item.label), ['file']);
    assert.equal(options[0].keepOpen, false);
    assert.match(applySuggestion(query, token, options[0]).value, /type:\s?file/);
  });
}

test('quoted literals, operators, URLs, drive paths and selected text are not reinterpreted as tags', () => {
  for (const query of ['"type:file"', '"summer && holiday"', 'https://example.test/a', 'C:\\folder', 'unknown:value']) {
    assert.equal(context(query), null, query);
  }
  for (const [query, caret] of [['a || b', 3], ['(a)', 0], ['a && b', 3]]) assert.equal(context(query, caret), null);
  assert.equal(context('name', 0, 4), null);
  assert.equal(context('a'.repeat(4097)), null);
});

test('text and date values give hints without fabricated values or file requests', () => {
  assert.match(completionHint(context('name:"summer holiday"')), /continuous phrase/);
  assert.match(completionHint(context('modified:>=2026-10-03')), /YYYY-MM-DD.*Asia\/Seoul/);
  assert.deepEqual(suggestionsFor(context('modified:'), schema), []);
  assert.equal(context('modified:2026-10-03T09:00:00+09:00').field.key, 'modified');
});

test('numeric and exact text hints use server metadata without unit conversion or fabricated values', () => {
  const numeric = { ...schema, fields: [
    { key: 'size', label: 'File size', type: 'NUMBER', operators: ['EQUALS', 'RANGE'], values: [], units: ['B', 'KB', 'KiB'], timeZone: null },
    { key: 'count', label: 'Count', type: 'NUMBER', operators: ['EQUALS'], values: [], units: [], timeZone: null },
    { key: 'extension', label: 'File extension', type: 'TEXT', operators: ['EQUALS'], values: [], units: [], timeZone: null },
  ] };
  const token = (query) => completionContext(query, query.length, query.length, numeric);
  assert.deepEqual(suggestionsFor(token(''), numeric).map((item) => item.label), ['size:', 'count:', 'extension:']);
  for (const query of ['size:>=1.5KiB', 'size:1KB..2KiB', 'size:"1KB"']) {
    assert.equal(token(query).field.key, 'size');
    assert.match(completionHint(token(query)), /whole bytes or B, KB, KiB.*>=.*\.\./);
    assert.deepEqual(suggestionsFor(token(query), numeric), []);
  }
  assert.match(completionHint(token('count:')), /integer/);
  assert.equal(completionHint(token('extension:pdf')), 'File extension: exact text');
  assert.deepEqual(suggestionsFor(token('extension:'), numeric), []);
  const custom = { ...numeric, fields: [{ ...numeric.fields[0], units: ['custom-server-unit'] }] };
  assert.match(completionHint(completionContext('size:', 5, 5, custom)), /custom-server-unit/);
});

test('path assistance locates one parent and filters its immediate directory response', () => {
  const token = context('path:photos/su');
  assert.equal(token.parent, 'photos');
  const entries = [
    { name: 'summer', path: 'photos/summer', type: 'directory' },
    { name: 'winter', path: 'photos/winter', type: 'directory' },
    { name: 'sun.jpg', path: 'photos/sun.jpg', type: 'file' },
  ];
  assert.deepEqual(suggestionsFor(token, schema, entries).map((entry) => entry.replacement), ['photos/summer/']);
  assert.equal(context('path:photos/').parent, 'photos');
  assert.equal(context('path:').parent, '');
  for (const query of ['path:../private', 'path:a/../b', 'path:https://example.test']) assert.equal(context(query), null);
});

test('paths with spaces and syntax characters are quoted and keep the caret inside the closing quote', () => {
  const query = 'type:file path:"photo albums/ho" name:report';
  const caret = query.indexOf('ho"') + 2;
  const token = context(query, caret);
  assert.equal(token.parent, 'photo albums');
  const option = suggestionsFor(token, schema, [{ name: 'holiday (1)', path: 'photo albums/holiday (1)', type: 'directory' }])[0];
  const next = applySuggestion(query, token, option);
  assert.equal(next.value, 'type:file path:"photo albums/holiday (1)/" name:report');
  assert.equal(next.value[next.caret], '"');
  assert.equal(context(next.value, next.caret).parent, 'photo albums/holiday (1)');
});

test('quote escaping agrees with the supported quote/backslash syntax', () => {
  assert.equal(quotedValue('a"b\\c'), '"a\\"b\\\\c"');
  assert.equal(context('path:"a\\"b/next"').parent, 'a"b');
  assert.equal(context('path:"a\\\\b/next"').parent, 'a/b');
  assert.equal(quotedValue('a||b'), '"a||b"');
});

test('suggestion lists are bounded while retaining server order', () => {
  const values = Array.from({ length: 100 }, (_, i) => `value-${i}`);
  const custom = { ...schema, fields: [{ ...schema.fields[2], values }] };
  assert.equal(suggestionsFor(completionContext('type:', 5, 5, custom), custom).length, 12);
});

test('incomplete token scanning terminates for arbitrary cursor positions without changing input', () => {
  let seed = 1739;
  const alphabet = 'ab :"\\()&|가';
  for (let i = 0; i < 1000; i++) {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    const query = Array.from({ length: seed % 70 }, () => {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      return alphabet[seed % alphabet.length];
    }).join('');
    const token = context(query, seed % (query.length + 1));
    if (token) assert.ok(token.start >= 0 && token.end <= query.length && token.start <= token.end);
  }
});

test('schema and directories reuse the JSON client with an AbortSignal, never the recursive search API', async () => {
  const calls = [];
  const client = createSearchAssistanceClient(async (url, options) => {
    calls.push({ url, options });
    return url.includes('/schemas/') ? schema : { entries: [] };
  });
  const signal = new AbortController().signal;
  await client.schema('files', signal);
  await client.directories('photos & albums', 'show', signal);
  assert.equal(calls[0].url, '/api/v1/search/schemas/files');
  assert.equal(calls[1].options.signal, signal);
  const url = new URL(calls[1].url, 'https://nas.test');
  assert.equal(url.pathname, '/api/v1/fs/entries');
  assert.equal(url.searchParams.get('path'), 'photos & albums');
  assert.equal(url.searchParams.get('hidden'), 'show');
  assert.equal(url.searchParams.get('types'), 'directory');
});

test('path cache is separated by parent and hidden policy, expires, and drops metadata', async () => {
  let clock = 0, count = 0;
  const client = createSearchAssistanceClient(async () => {
    count++;
    return { entries: [{ name: 'a', path: 'a', type: 'directory', size: 999, modifiedAt: 'secret' }] };
  }, () => clock);
  const signal = new AbortController().signal;
  assert.deepEqual(await client.directories('', 'hide', signal), [{ name: 'a', path: 'a', type: 'directory' }]);
  await client.directories('', 'hide', signal);
  assert.equal(count, 1);
  await client.directories('', 'show', signal);
  await client.directories('a', 'hide', signal);
  assert.equal(count, 3);
  clock = 30_000;
  await client.directories('', 'hide', signal);
  assert.equal(count, 4);
});

test('schema cache has a TTL and bounds its retained scopes', async () => {
  let clock = 0, count = 0;
  const client = createSearchAssistanceClient(async (url) => {
    count++;
    return { ...schema, scope: decodeURIComponent(url.split('/').at(-1)) };
  }, () => clock);
  const signal = new AbortController().signal;
  await client.schema('files', signal);
  await client.schema('files', signal);
  assert.equal(count, 1);
  clock = 300_000;
  await client.schema('files', signal);
  assert.equal(count, 2);
  for (let i = 0; i < 8; i++) await client.schema(`scope-${i}`, signal);
  await client.schema('files', signal);
  assert.equal(count, 11);
});

test('path cache limits parent count and total entries; oversized listings are not cached', async () => {
  let count = 0;
  const client = createSearchAssistanceClient(async (url) => {
    count++;
    const path = new URL(url, 'https://nas.test').searchParams.get('path');
    const size = path === 'huge' ? 2001 : path?.startsWith('large') ? 1500 : 1;
    return { entries: Array.from({ length: size }, (_, i) => ({ name: `${i}`, path: `${path}/${i}`, type: 'directory' })) };
  });
  const signal = new AbortController().signal;
  for (let i = 0; i < 9; i++) await client.directories(`${i}`, 'hide', signal);
  await client.directories('0', 'hide', signal);
  assert.equal(count, 10);
  for (const parent of ['large-a', 'large-b', 'large-a', 'huge', 'huge']) await client.directories(parent, 'hide', signal);
  assert.equal(count, 15);
});

test('aborted late responses cannot populate a cache, even if the transport ignores cancellation', async () => {
  let finish, calls = 0;
  const client = createSearchAssistanceClient(() => { calls++; return new Promise((resolve) => { finish = resolve; }); });
  const controller = new AbortController();
  const pending = client.directories('', 'hide', controller.signal);
  controller.abort();
  finish({ entries: [] });
  await assert.rejects(pending, { name: 'AbortError' });
  const next = client.directories('', 'hide', new AbortController().signal);
  finish({ entries: [] });
  await next;
  assert.equal(calls, 2);
  await assert.rejects(client.directories('', 'hide', controller.signal), { name: 'AbortError' });
});

test('assistance errors are not cached and do not trigger fallback searches or retries', async () => {
  let calls = 0;
  const client = createSearchAssistanceClient(async () => { calls++; throw new Error('Forbidden'); });
  const signal = new AbortController().signal;
  await assert.rejects(client.schema('files', signal), /Forbidden/);
  assert.equal(calls, 1);
  await assert.rejects(client.schema('files', signal), /Forbidden/);
  assert.equal(calls, 2);
});

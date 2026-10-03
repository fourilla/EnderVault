import type { CompletionContext, PathEntry, SearchSchema, SearchSuggestion } from './types';

interface Token { start: number; end: number; raw: string; kind: 'word' | 'quote' | 'boundary' }

// This tolerant scanner locates an unfinished input token; the server validates the expression.
function tokensFor(query: string): Token[] {
  const tokens: Token[] = [];
  let index = 0;
  const boundary = (at: number) => query[at] === '(' || query[at] === ')'
    || query.slice(at, at + 2) === '&&' || query.slice(at, at + 2) === '||';
  while (index < query.length) {
    if (/\s/u.test(query[index])) { index++; continue; }
    const start = index;
    let kind: Token['kind'] = 'word';
    if (boundary(index)) {
      kind = 'boundary';
      index += query[index] === '(' || query[index] === ')' ? 1 : 2;
    } else if (query[index] === '"') {
      kind = 'quote';
      index++;
      while (index < query.length) {
        const next = query[index++];
        if (next === '"') break;
        if (next === '\\' && (query[index] === '"' || query[index] === '\\')) index++;
      }
    } else {
      while (index < query.length && !/\s/u.test(query[index]) && query[index] !== '"' && !boundary(index)) index++;
    }
    tokens.push({ start, end: index, raw: query.slice(start, index), kind });
  }
  return tokens;
}

function decoded(raw: string): string {
  if (!raw.startsWith('"')) return raw;
  let value = '';
  for (let index = 1; index < raw.length; index++) {
    if (raw[index] === '"') break;
    if (raw[index] === '\\' && (raw[index + 1] === '"' || raw[index + 1] === '\\')) index++;
    value += raw[index];
  }
  return value;
}

export function completionContext(query: string, caret: number, selectionEnd: number, schema: SearchSchema): CompletionContext | null {
  if (caret !== selectionEnd || caret < 0 || caret > query.length || query.length > schema.limits.maxLength) return null;
  const tokens = tokensFor(query);
  const containing = tokens.find((token) => token.start <= caret && caret < token.end);
  const current = containing?.raw === ')' && containing.start === caret
    ? tokens.find((token) => token.end === caret && token.kind !== 'boundary') ?? containing
    : containing ?? tokens.find((token) => token.end === caret && token.kind !== 'boundary');
  if (current?.kind === 'boundary') return null;
  const previous = tokens.filter((token) => token.end <= (current?.start ?? caret)).at(-1);
  if (!current && previous?.raw === ')' && previous.end === caret) return null;
  let start = current?.start ?? caret;
  const end = current?.end ?? caret;
  let field;
  if (current?.kind === 'word') {
    const colon = current.raw.indexOf(':');
    if (colon >= 0) {
      field = schema.fields.find((item) => item.key.toLowerCase() === current.raw.slice(0, colon).toLowerCase());
      if (!field) return null;
      if (caret <= start + colon) return { kind: 'field', start, end: start + colon + 1, prefix: query.slice(start, caret) };
      start += colon + 1;
    }
  }
  if (!field && previous?.kind === 'word' && previous.raw.endsWith(':')) {
    field = schema.fields.find((item) => item.key.toLowerCase() === previous.raw.slice(0, -1).toLowerCase());
  }
  if (!field) {
    if (current?.kind === 'quote') return null;
    return { kind: 'field', start, end, prefix: query.slice(start, caret) };
  }
  const prefix = decoded(query.slice(start, caret));
  let parent;
  if (field.type === 'PATH') {
    const path = prefix.replaceAll('\\', '/').replace(/^\/+/, '');
    if (path.includes(':') || path.split('/').some((part) => part === '.' || part === '..')) return null;
    parent = path.slice(0, path.lastIndexOf('/') + 1).replace(/\/$/, '');
  }
  return { kind: 'value', start, end, prefix, field, parent };
}

export function quotedValue(value: string): string {
  return /\s|["()]|&&|\|\|/u.test(value)
    ? `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"` : value;
}

export function suggestionsFor(context: CompletionContext | null, schema: SearchSchema | null, entries: PathEntry[] = []): SearchSuggestion[] {
  if (!context || !schema) return [];
  const prefix = context.prefix.toLowerCase();
  if (context.kind === 'field') {
    return schema.fields.filter((field) => field.key.toLowerCase().startsWith(prefix)).slice(0, 12)
      .map((field) => ({ label: `${field.key}:`, detail: field.type, replacement: `${field.key}:`, keepOpen: true }));
  }
  if (context.field?.type === 'ENUM') {
    return context.field.values.filter((value) => value.toLowerCase().startsWith(prefix)).slice(0, 12)
      .map((value) => ({ label: value, detail: context.field!.label, replacement: quotedValue(value), keepOpen: false }));
  }
  if (context.field?.type === 'PATH') {
    const pathPrefix = prefix.replaceAll('\\', '/').replace(/^\/+/, '');
    return entries.filter((entry) => entry.type === 'directory' && entry.path.toLowerCase().startsWith(pathPrefix)).slice(0, 12)
      .map((entry) => {
        const replacement = quotedValue(`${entry.path}/`);
        return { label: entry.name, detail: `${entry.path}/`, replacement, keepOpen: true,
          caretOffset: replacement.endsWith('"') ? replacement.length - 1 : replacement.length };
      });
  }
  return [];
}

export function applySuggestion(query: string, context: CompletionContext, suggestion: SearchSuggestion) {
  const value = query.slice(0, context.start) + suggestion.replacement + query.slice(context.end);
  return { value, caret: context.start + (suggestion.caretOffset ?? suggestion.replacement.length) };
}

export function completionHint(context: CompletionContext | null): string | null {
  const field = context?.field;
  if (!field) return null;
  if (field.type === 'TEXT') return field.operators.includes('CONTAINS')
    ? `${field.label}: partial text or "continuous phrase"` : `${field.label}: exact text`;
  if (field.type === 'PATH') return `${field.label}: Vault-relative path`;
  if (field.type === 'DATE_TIME') return `${field.label}: YYYY-MM-DD or ISO time with offset; >=, <=, .. (${field.timeZone})`;
  if (field.type === 'NUMBER') return field.units?.length
    ? `${field.label}: whole bytes or ${field.units.join(', ')}; =, <, <=, >, >=, ..`
    : `${field.label}: integer; =, <, <=, >, >=, ..`;
  return null;
}

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
    } else {
      kind = query[index] === '"' ? 'quote' : 'word';
      let quoted = false;
      while (index < query.length) {
        if (!quoted && (/\s/u.test(query[index]) || boundary(index))) break;
        const next = query[index++];
        if (next === '"') quoted = !quoted;
        else if (quoted && next === '\\' && (query[index] === '"' || query[index] === '\\')) index++;
      }
    }
    tokens.push({ start, end: index, raw: query.slice(start, index), kind });
  }
  return tokens;
}

function valueParts(raw: string): { value: string; glob: boolean; equalityEnd: number } {
  let value = '';
  let quoted = false;
  let glob = false;
  let equalityEnd = 0;
  for (let index = 0; index < raw.length; index++) {
    if (raw[index] === '"') { quoted = !quoted; continue; }
    if (!quoted && !value.length && raw[index] === '=') equalityEnd = index + 1;
    if (quoted && raw[index] === '\\' && (raw[index + 1] === '"' || raw[index + 1] === '\\')) index++;
    else if (!quoted && (raw[index] === '*' || raw[index] === '?')) glob = true;
    value += raw[index];
  }
  return { value, glob, equalityEnd };
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
  let exact = false;
  if (current?.kind === 'word') {
    // Unary NOT belongs to the expression, so replacing its field/value must retain it.
    const conditionStart = current.raw.match(/^!*/u)![0].length;
    start += conditionStart;
    if (caret < start) return null;
    const colon = current.raw.indexOf(':', conditionStart);
    if (colon >= 0) {
      field = schema.fields.find((item) => item.key.toLowerCase() === current.raw.slice(conditionStart, colon).toLowerCase());
      if (!field) return null;
      const valueStart = current.start + colon + 1;
      if (caret < valueStart) return { kind: 'field', start, end: valueStart, prefix: query.slice(start, caret) };
      start = valueStart;
    }
  }
  if (!field && previous?.kind === 'word' && previous.raw.endsWith(':')) {
    field = schema.fields.find((item) => item.key.toLowerCase() === previous.raw.replace(/^!*/u, '').slice(0, -1).toLowerCase());
    // A value starting with ! is literal; only condition prefixes above are unary NOT.
    start = current?.start ?? caret;
  }
  if (!field) {
    if (current?.kind === 'quote' || query[start] === '"') return null;
    return { kind: 'field', start, end, prefix: query.slice(start, caret) };
  }
  const value = valueParts(query.slice(start, end));
  if (value.equalityEnd) { exact = true; start += value.equalityEnd; }
  const prefix = valueParts(query.slice(start, caret)).value;
  const glob = value.glob;
  let parent;
  if (field.type === 'PATH' && !glob) {
    const path = prefix.replaceAll('\\', '/').replace(/^\/+/, '');
    if (path.includes(':') || path.split('/').some((part) => part === '.' || part === '..')) return null;
    parent = path.slice(0, path.lastIndexOf('/') + 1).replace(/\/$/, '');
  }
  return { kind: 'value', start, end, prefix, field, parent, exact, glob };
}

export function quotedValue(value: string): string {
  return /\s|["()*?]|&&|\|\||^[!=]/u.test(value)
    ? `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"` : value;
}

export function suggestionsFor(context: CompletionContext | null, schema: SearchSchema | null, entries: PathEntry[] = []): SearchSuggestion[] {
  if (!context || !schema) return [];
  const prefix = context.prefix.toLowerCase();
  if (context.kind === 'field') {
    return schema.fields.filter((field) => field.key.toLowerCase().startsWith(prefix)).slice(0, 12)
      .map((field) => ({ label: `${field.key}:`, detail: field.type, replacement: `${field.key}:`, keepOpen: true }));
  }
  // A pattern cannot identify one directory parent or a concrete enum completion.
  if (context.glob) return [];
  if (context.field?.type === 'ENUM') {
    return context.field.values.filter((value) => value.toLowerCase().startsWith(prefix)).slice(0, 12)
      .map((value) => ({ label: value, detail: context.field!.label, replacement: quotedValue(value), keepOpen: false }));
  }
  if (context.field?.type === 'PATH') {
    const pathPrefix = prefix.replaceAll('\\', '/').replace(/^\/+/, '');
    return entries.filter((entry) => entry.type === 'directory' && entry.path.toLowerCase().startsWith(pathPrefix)).slice(0, 12)
      .map((entry) => {
        const path = context.exact ? entry.path : `${entry.path}/`;
        const replacement = quotedValue(path);
        return { label: entry.name, detail: path, replacement, keepOpen: !context.exact,
          caretOffset: !context.exact && replacement.endsWith('"') ? replacement.length - 1 : replacement.length };
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
  const equals = field.operators.includes('EQUALS');
  const glob = field.operators.includes('GLOB');
  const range = field.operators.includes('RANGE');
  if (field.type === 'TEXT' || field.type === 'PATH') {
    const contains = field.operators.includes('CONTAINS');
    const description = field.type === 'PATH' ? 'Vault-relative path'
      : contains ? 'partial text or "continuous phrase"' : 'exact text';
    return `${field.label}: ${description}${equals ? contains ? '; = exact match' : '; optional =' : ''}${glob ? '; * / ? pattern, quote for literal characters' : ''}`;
  }
  if (field.type === 'ENUM') return `${field.label}: exact value${equals ? '; optional =' : ''}`;
  if (field.type === 'DATE_TIME') return `${field.label}: YYYY-MM-DD or ISO time with offset; ${equals ? '=, ' : ''}${range ? '>=, <=, .. ' : ''}(${field.timeZone})`;
  if (field.type === 'NUMBER') return field.units?.length
    ? `${field.label}: whole bytes or ${field.units.join(', ')}${equals ? '; =' : ''}${range ? ', <, <=, >, >=, ..' : ''}`
    : `${field.label}: integer${equals ? '; =' : ''}${range ? ', <, <=, >, >=, ..' : ''}`;
  return null;
}

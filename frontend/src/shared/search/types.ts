export interface SearchField {
  key: string;
  label: string;
  type: 'TEXT' | 'PATH' | 'ENUM' | 'DATE_TIME';
  operators: string[];
  values: string[];
  timeZone: string | null;
}

export interface SearchSchema {
  scope: string;
  defaultFields: string[];
  defaultOperator: string;
  fields: SearchField[];
  limits: { maxLength: number; maxTokens: number; maxTerms: number; maxDepth: number };
}

export interface PathEntry { name: string; path: string; type: string }

export interface CompletionContext {
  kind: 'field' | 'value';
  start: number;
  end: number;
  prefix: string;
  field?: SearchField;
  parent?: string;
}

export interface SearchSuggestion {
  label: string;
  detail: string;
  replacement: string;
  keepOpen: boolean;
  caretOffset?: number;
}

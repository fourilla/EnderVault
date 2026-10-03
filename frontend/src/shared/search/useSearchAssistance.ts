import { useEffect, useMemo, useState } from 'react';
import { completionContext, completionHint, suggestionsFor } from './query-completion';
import { createSearchAssistanceClient } from './search-assistance-api';
import type { PathEntry, SearchSchema } from './types';

interface AssistanceInput {
  scope?: string;
  owner: string;
  query: string;
  caret: number;
  selectionEnd: number;
  hidden: string;
  enabled: boolean;
}

export function useSearchAssistance({ scope, owner, query, caret, selectionEnd, hidden, enabled }: AssistanceInput) {
  const client = useMemo(() => createSearchAssistanceClient(), []);
  const schemaKey = enabled && scope ? JSON.stringify([owner, scope]) : '';
  const [schemaState, setSchemaState] = useState<{ key: string; schema?: SearchSchema; error?: boolean }>({ key: '' });
  const schema = schemaKey && schemaState.key === schemaKey ? schemaState.schema ?? null : null;
  useEffect(() => {
    if (!schemaKey || !scope) return;
    const controller = new AbortController();
    setSchemaState({ key: schemaKey });
    void client.schema(scope, controller.signal).then((next) => {
      if (!controller.signal.aborted) setSchemaState({ key: schemaKey, schema: next });
    }).catch(() => {
      if (!controller.signal.aborted) setSchemaState({ key: schemaKey, error: true });
    });
    return () => controller.abort();
  }, [client, schemaKey, scope]);

  const context = schema ? completionContext(query, caret, selectionEnd, schema) : null;
  const pathKey = enabled && context?.parent != null ? JSON.stringify([schemaKey, query, caret, hidden, context.parent]) : '';
  const parent = context?.parent;
  const [pathState, setPathState] = useState<{ key: string; entries?: PathEntry[]; error?: boolean }>({ key: '' });
  useEffect(() => {
    if (!pathKey || parent == null) return;
    const controller = new AbortController();
    setPathState({ key: pathKey });
    const timer = setTimeout(() => {
      void client.directories(parent, hidden, controller.signal).then((entries) => {
        if (!controller.signal.aborted) setPathState({ key: pathKey, entries });
      }).catch(() => {
        if (!controller.signal.aborted) setPathState({ key: pathKey, error: true });
      });
    }, 200);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [client, hidden, parent, pathKey]);

  const paths = pathState.key === pathKey ? pathState.entries ?? [] : [];
  const schemaError = schemaKey && schemaState.key === schemaKey && schemaState.error;
  const pathError = pathKey && pathState.key === pathKey && pathState.error;
  return {
    context,
    options: suggestionsFor(context, schema, paths),
    hint: completionHint(context),
    loading: Boolean(schemaKey && !schema && !schemaError || pathKey && pathState.key !== pathKey
      || pathKey && !pathState.entries && !pathError),
    unavailable: Boolean(schemaError || pathError),
  };
}

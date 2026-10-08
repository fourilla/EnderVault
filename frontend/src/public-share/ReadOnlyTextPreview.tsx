import { useLayoutEffect, useRef, useState } from 'react';
import type { SharedDetail } from './types';

export function ReadOnlyTextPreview({ name, extension, text }: {
  name: string;
  extension: string;
  text: NonNullable<SharedDetail['text']>;
}) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [enhancementFailed, setEnhancementFailed] = useState(false);

  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root || !text.loaded) return;
    let disposed = false;
    let cleanup: (() => void) | undefined;
    setEnhancementFailed(false);
    void import('../file-tools/main').then((tools) => {
      if (!disposed) cleanup = tools.initializeReadOnlyTextPreview(root);
    }).catch(() => {
      if (!disposed) setEnhancementFailed(true);
    });
    return () => {
      disposed = true;
      cleanup?.();
    };
  }, [name, extension, text.loaded, text.content]);

  if (!text.loaded) return <div className="tool-message"><p>{text.message || 'Text preview unavailable.'}</p></div>;
  return <div ref={rootRef} className="shared-text-preview" data-shared-text-preview
    data-text-extension={extension} data-text-name={name} data-native-context-menu>
    <textarea className="shared-text-fallback text-editor-area" data-shared-text-source
      readOnly spellCheck={false} defaultValue={text.content || ''} aria-label={`Text preview for ${name}`} />
    {enhancementFailed && <p className="tool-message" role="status">Syntax highlighting is unavailable.</p>}
  </div>;
}

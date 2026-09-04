import { useEffect, useRef } from 'react';
import type { FileDetailPayload } from './types';

function CsrfInput() {
  const csrf = window.EnderVault?.csrfPair();
  return csrf ? <input type="hidden" name={csrf.name} value={csrf.value} /> : null;
}

export function TextTool({ payload }: { payload: FileDetailPayload }) {
  const rootRef = useRef<HTMLFormElement>(null);
  const { detail, text, tool, urls } = payload;

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    let disposed = false;
    let destroy: ((root?: ParentNode) => void) | undefined;
    void (async () => {
      if (tool.markdown) await import('../markdown/main');
      const fileTools = await import('../file-tools/main');
      if (disposed) return;
      destroy = fileTools.destroyFileTools;
      fileTools.initializeFileTools(root);
    })().catch((reason) => {
      window.EnderVault?.showToast('error', reason instanceof Error ? reason.message : 'Text editor unavailable.');
    });
    return () => {
      disposed = true;
      destroy?.(root);
    };
  }, [detail.path, tool.markdown]);

  if (!text) return null;
  return (
    <form
      ref={rootRef}
      className="text-editor"
      method="post"
      action="/api/v1/files/text/save"
      data-text-load-url={`/api/v1/files/text/load?path=${encodeURIComponent(detail.path)}`}
      data-text-draft-url="/api/v1/files/text/draft"
      data-text-draft-restore-url="/api/v1/files/text/draft/restore"
      data-text-draft-discard-url="/api/v1/files/text/draft/discard"
      data-text-draft-save-as-url="/api/v1/files/text/draft/save-as"
      data-text-extension={detail.extension}
      data-text-name={detail.name}
      data-text-editor
    >
      <CsrfInput />
      <input type="hidden" name="path" value={detail.path} />
      <input type="hidden" name="editorToken" value="" data-text-editor-token readOnly />
      <input type="hidden" name="draftId" value="" data-text-draft-id readOnly />
      <input type="hidden" name="forceOverwrite" value="false" data-text-force-overwrite readOnly />
      <div className="text-editor-toolbar">
        {tool.markdown && (
          <div className="text-editor-view-tabs" role="tablist" aria-label="Markdown view">
            <button className="text-editor-view-tab is-active" type="button" role="tab"
              aria-selected="true" data-markdown-source-tab>Source</button>
            <button className="text-editor-view-tab" type="button" role="tab"
              aria-selected="false" data-markdown-preview-tab>Preview</button>
          </div>
        )}
        <button className="icon-button action-icon" type="submit" disabled={!text.editable}
          data-text-save-button title="Save text" aria-label="Save text">
          <i className="fas fa-floppy-disk" aria-hidden="true" />
        </button>
      </div>
      {tool.markdown && (
        <div className="markdown-editor-preview" data-markdown-editor-preview hidden>
          <p className="markdown-preview-status muted" data-markdown-preview-status aria-live="polite">
            Select Preview to render this Markdown document.
          </p>
          <article className="markdown-body" data-markdown-preview-body />
        </div>
      )}
      {text.loaded ? (
        <textarea className="text-editor-area" name="content" spellCheck={false}
          defaultValue={text.content} />
      ) : (
        <div className="tool-message text-load-panel" data-text-load-panel>
          <p>{text.message}</p>
          <div className="tool-actions">
            {text.manualLoadAvailable && <button className="button-link" type="button" data-text-load-button>Load text</button>}
            <a className="button-link ghost" href={urls.download}>Download original</a>
          </div>
        </div>
      )}
      <div className="text-editor-statusbar">
        <span className="text-draft-state" data-text-draft-state aria-live="polite">
          Checking for a saved draft...
        </span>
      </div>
      <dialog className="text-draft-dialog" data-text-draft-dialog aria-labelledby="textDraftTitle">
        <div className="text-draft-card">
          <header><div><h3 id="textDraftTitle">Saved text draft</h3>
            <p data-text-draft-message>A recoverable draft exists for this file.</p></div></header>
          <p className="text-draft-source-warning" data-text-draft-source-warning hidden>
            The original file changed after this draft was created.
          </p>
          <div className="text-draft-actions">
            <button type="button" data-text-draft-restore>Restore draft</button>
            <button className="ghost" type="button" data-text-draft-discard>Discard draft</button>
            <button type="button" data-text-draft-takeover hidden>Take over editing</button>
            <button className="ghost" type="button" data-text-draft-view-original>View original</button>
          </div>
        </div>
      </dialog>
    </form>
  );
}

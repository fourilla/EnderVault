import { useEffect, useRef } from 'react';
import { loadScript, loadStyle } from './external-assets';
import type { FileDetailPayload } from './types';

const action = (name: string, icon: string, title: string, label = title) => (
  <button className="ghost icon-button action-icon" type="button" disabled
    data-image-action={name} title={title} aria-label={label}>
    {icon ? <i className={icon} aria-hidden="true" /> : <span className="image-viewer-one-to-one" aria-hidden="true">1:1</span>}
  </button>
);

export function ImageTool({ payload }: { payload: FileDetailPayload }) {
  const rootRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    let disposed = false;
    loadStyle('/webjars/viewerjs/1.11.7/dist/viewer.min.css');
    void (async () => {
      await loadScript('/webjars/viewerjs/1.11.7/dist/viewer.min.js');
      await loadScript('/js/image-viewer.js');
      if (!disposed) window.EnderVaultImageViewers?.init(root);
    })().catch((reason) => window.EnderVault?.showToast(
      'error', reason instanceof Error ? reason.message : 'Enhanced image viewer unavailable.',
    ));
    return () => {
      disposed = true;
      window.EnderVaultImageViewers?.destroy(root);
    };
  }, [payload.detail.path]);

  return (
    <div className="image-viewer" data-image-viewer ref={rootRef}>
      <div className="image-viewer-toolbar" aria-label="Image viewer controls">
        <div className="image-viewer-controls">
          {action('zoom-out', 'fas fa-magnifying-glass-minus', 'Zoom out')}
          {action('zoom-in', 'fas fa-magnifying-glass-plus', 'Zoom in')}
          {action('one-to-one', '', 'Actual size', 'Show at actual size')}
          {action('reset', 'fas fa-arrows-rotate', 'Fit and reset', 'Fit image and reset transformations')}
          <span className="image-viewer-control-divider" aria-hidden="true" />
          {action('rotate-left', 'fas fa-rotate-left', 'Rotate left')}
          {action('rotate-right', 'fas fa-rotate-right', 'Rotate right')}
          {action('flip-horizontal', 'fas fa-arrows-left-right', 'Flip horizontally')}
          {action('flip-vertical', 'fas fa-arrows-up-down', 'Flip vertically')}
        </div>
        {action('fullscreen', 'fas fa-expand', 'Open fullscreen viewer')}
      </div>
      <div className="image-viewer-stage" data-image-viewer-stage>
        <img className="image-viewer-source" src={payload.urls.previewContent || ''}
          alt={payload.detail.name} draggable="false" data-image-viewer-source />
      </div>
      <div className="image-viewer-statusbar" aria-live="polite">
        <span data-image-dimensions>Loading image...</span><span data-image-zoom>Fit</span>
      </div>
    </div>
  );
}

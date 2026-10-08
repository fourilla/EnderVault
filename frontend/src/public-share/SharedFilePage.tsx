import { useState } from 'react';
import { ImageViewer } from '../shared/file-tools/ImageViewer';
import { SharedComicViewer } from '../shared-file/SharedComicViewer';
import { ReadOnlyTextPreview } from './ReadOnlyTextPreview';
import type { SharedDetail } from './types';

const previewTypes = new Set(['image', 'video', 'audio', 'text', 'comic']);

function SharedPreview({ detail }: { detail: SharedDetail }) {
  const source = detail.previewContentUrl;
  switch (detail.toolType) {
    case 'image':
      return source ? <ImageViewer sourceUrl={source} name={detail.name} /> : null;
    case 'video':
      return source ? <div className="preview-surface" data-native-context-menu>
        <video className="preview-media" controls preload="metadata" src={source} />
      </div> : null;
    case 'audio':
      return source ? <div className="audio-tool" data-native-context-menu>
        <div className="audio-tool-heading">
          <span className="audio-tool-icon" aria-hidden="true"><i className="fas fa-music" /></span>
          <div className="audio-tool-title"><strong title={detail.name}>{detail.name}</strong></div>
        </div>
        <audio className="audio-tool-player" controls preload="metadata" src={source}
          aria-label={`Audio player for ${detail.name}`}>Your browser does not support HTML audio playback.</audio>
      </div> : null;
    case 'comic':
      return detail.comicManifestUrl ? <SharedComicViewer manifestUrl={detail.comicManifestUrl} /> : null;
    case 'text':
      return detail.text ? <ReadOnlyTextPreview name={detail.name} extension={detail.extension} text={detail.text} /> : null;
    default:
      return null;
  }
}

export function SharedFilePage({ detail }: { detail: SharedDetail }) {
  const [previewOpened, setPreviewOpened] = useState(false);
  const canPreview = detail.previewEnabled && previewTypes.has(detail.toolType || '');
  return <section className="shared-summary">
    <header className="shared-file-header">
      <div className="shared-file-title"><p className="eyebrow">Shared file</p><h1>{detail.name}</h1></div>
      <a className="button-link shared-download-button" href={detail.downloadUrl} title="Download" aria-label="Download">
        <i className="fas fa-download" aria-hidden="true" /><span>Download</span>
      </a>
    </header>
    {canPreview && <details className="shared-preview-panel" data-shared-preview
      onToggle={(event) => { if (event.currentTarget.open) setPreviewOpened(true); }}>
      <summary className="section-heading shared-preview-summary">
        <h2>Preview</h2><span className="section-heading-actions">
          <span className="status-badge">{detail.toolType === 'text' ? 'Text Preview' : detail.toolLabel || 'Preview'}</span>
          <i className="fas fa-chevron-down shared-preview-chevron" aria-hidden="true" />
        </span>
      </summary>
      <div className="shared-preview-content">{previewOpened && <SharedPreview detail={detail} />}</div>
    </details>}
    <dl className="details shared-file-details">
      <div><dt>Type</dt><dd>{detail.mediaType}</dd></div>
      <div><dt>Size</dt><dd>{detail.sizeLabel}</dd></div>
      <div><dt>Modified</dt><dd>{detail.modifiedLabel}</dd></div>
    </dl>
  </section>;
}

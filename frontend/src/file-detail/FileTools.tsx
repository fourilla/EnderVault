import type { FileDetailPayload } from './types';
import { ArchiveTool } from './ArchiveTool';
import { ComicTool } from './ComicTool';
import { ImageTool } from './ImageTool';
import { TextTool } from './TextTool';

export function FileTools({ payload }: { payload: FileDetailPayload }) {
  const { tool, urls, detail } = payload;
  if (detail.directory) return null;
  return <section className="detail-panel">
    <header className="section-heading"><h2>File Tools</h2><span className="status-badge">{tool.label}</span></header>
    {tool.type === 'video' && <div className="preview-surface">
      <video className="preview-media" controls poster={urls.cardMedia || undefined} src={urls.previewContent || undefined} />
    </div>}
    {tool.type === 'pdf' && <div className="preview-surface">
      <iframe className="preview-frame" src={urls.previewContent || undefined} title="Preview" />
    </div>}
    {tool.type === 'image' && <ImageTool payload={payload} />}
    {tool.type === 'audio' && <div className="audio-tool">
      <div className="audio-tool-heading"><span className="audio-tool-icon" aria-hidden="true"><i className="fas fa-music" /></span>
        <div className="audio-tool-title"><strong title={detail.name}>{detail.name}</strong></div></div>
      <audio className="audio-tool-player" controls preload="metadata" src={urls.previewContent || undefined}
        aria-label={`Audio player for ${detail.name}`}>Your browser does not support HTML audio playback.</audio>
    </div>}
    {tool.type === 'comic' && <ComicTool payload={payload} />}
    {tool.type === 'text' && <TextTool payload={payload} />}
    {tool.type === 'archive' && <ArchiveTool payload={payload} />}
    {tool.type === 'hex' && <div className="tool-message"><p>Hex viewer is planned for files without a dedicated tool.</p>
      <a className="button-link ghost" href={urls.download}>Download original</a></div>}
  </section>;
}

import { createRoot } from 'react-dom/client';
import { ImageViewer } from '../shared/file-tools/ImageViewer';
import { mountWhenPreviewOpened } from './preview-disclosure';

const container = document.getElementById('shared-image-root');
const source = container?.querySelector<HTMLImageElement>('img[data-shared-image-source]');
const sourceUrl = source?.getAttribute('src');

if (container && source && sourceUrl) {
  mountWhenPreviewOpened(container, () => {
    createRoot(container).render(<ImageViewer sourceUrl={sourceUrl} name={source.alt} />);
  });
}

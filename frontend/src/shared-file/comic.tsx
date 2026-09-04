import { createRoot } from 'react-dom/client';
import { SharedComicViewer } from './SharedComicViewer';
import { mountWhenPreviewOpened } from './preview-disclosure';

const container = document.getElementById('shared-comic-root');
const manifestUrl = container?.dataset.manifestUrl;
if (container && manifestUrl) {
  mountWhenPreviewOpened(container, () => {
    createRoot(container).render(<SharedComicViewer manifestUrl={manifestUrl} />);
  });
}

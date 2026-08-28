import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserApp } from './BrowserApp';

const root = document.getElementById('files-root');

if (root) {
  createRoot(root).render(
    <StrictMode>
      <BrowserApp />
    </StrictMode>,
  );
}

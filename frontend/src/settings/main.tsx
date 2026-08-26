import '@vitejs/plugin-react/preamble';
import 'vite/modulepreload-polyfill';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { SettingsApp } from './SettingsApp';
import './settings-app.css';

const rootElement = document.getElementById('settings-root');

if (!rootElement) {
  throw new Error('Settings React root was not found.');
}

createRoot(rootElement).render(
  <StrictMode>
    <SettingsApp />
  </StrictMode>,
);

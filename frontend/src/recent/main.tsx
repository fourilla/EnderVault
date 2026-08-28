import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RecentApp } from './RecentApp';

const root = document.getElementById('recent-root');
if (root) createRoot(root).render(<StrictMode><RecentApp /></StrictMode>);

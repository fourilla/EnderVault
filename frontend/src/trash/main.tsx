import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { TrashApp } from './TrashApp';
import './trash-app.css';

const root = document.getElementById('trash-root');
if (root) createRoot(root).render(<StrictMode><TrashApp /></StrictMode>);

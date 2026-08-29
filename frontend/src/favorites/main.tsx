import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { FavoritesApp } from './FavoritesApp';
import './favorites-app.css';

const root = document.getElementById('favorites-root');
if (root) createRoot(root).render(<StrictMode><FavoritesApp /></StrictMode>);

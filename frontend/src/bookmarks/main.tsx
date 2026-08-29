import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BookmarksApp } from './BookmarksApp';
import './bookmarks-app.css';

const root = document.getElementById('bookmarks-root');
if (root) createRoot(root).render(<StrictMode><BookmarksApp /></StrictMode>);

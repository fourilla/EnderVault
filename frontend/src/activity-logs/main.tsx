import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ActivityLogsApp } from './ActivityLogsApp';
import './activity-logs-app.css';

const root = document.getElementById('activity-logs-root');
if (root) createRoot(root).render(<StrictMode><ActivityLogsApp /></StrictMode>);

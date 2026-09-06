import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { AdvancedSettings } from './sections/AdvancedSettings';
import { AccountSettings } from './sections/AccountSettings';
import { BookmarkSettings } from './sections/BookmarkSettings';
import { FileRequestSettings } from './sections/FileRequestSettings';
import { GeneralSettings } from './sections/GeneralSettings';
import { PasskeySettings } from './sections/PasskeySettings';
import { SessionSettings } from './sections/SessionSettings';
import { TelegramSettings } from './sections/TelegramSettings';
import { VpnSettings } from './sections/VpnSettings';
import { OverflowMarquee } from './components/OverflowMarquee';
import './settings-app.css';

type InternalSectionId =
  | 'appearance'
  | 'files'
  | 'file-tools'
  | 'archive-safety'
  | 'bookmarks'
  | 'share-links'
  | 'file-requests'
  | 'uploads'
  | 'thumbnails'
  | 'remote-downloads'
  | 'vpn'
  | 'account'
  | 'passkeys'
  | 'sessions'
  | 'access-identity'
  | 'tasks-staging'
  | 'metadata-activity'
  | 'telegram-alerts'
  | 'deployment';

type SettingsLink = {
  id: string;
  icon: string;
  title: string;
  description: string;
  internal: InternalSectionId;
};

type SettingsGroup = { id: string; title: string; links: SettingsLink[] };

const settingsGroups: SettingsGroup[] = [
  {
    id: 'application', title: 'Application', links: [
      { id: 'appearance', internal: 'appearance', icon: 'fas fa-display', title: 'Appearance & Browser', description: 'Browser defaults and sticky note appearance' },
      { id: 'files', internal: 'files', icon: 'fas fa-folder-tree', title: 'Files & Storage', description: 'Conflicts, recent items, and trash retention' },
      { id: 'file-tools', internal: 'file-tools', icon: 'fas fa-screwdriver-wrench', title: 'File Tools', description: 'Text, comic, and draft limits' },
      { id: 'archive-safety', internal: 'archive-safety', icon: 'fas fa-file-zipper', title: 'Archive Safety', description: 'Extraction limits and archive manifest caching' },
      { id: 'thumbnails', internal: 'thumbnails', icon: 'fas fa-images', title: 'Thumbnails', description: 'Enabled formats, cache, and generator workers' },
      { id: 'bookmarks', internal: 'bookmarks', icon: 'fas fa-bookmark', title: 'Bookmarks', description: 'Link behavior, metadata fetching, and favicon cache' },
    ],
  },
  {
    id: 'sharing', title: 'Sharing', links: [
      { id: 'share-links', internal: 'share-links', icon: 'fas fa-share-nodes', title: 'Share Links', description: 'Public read-only links, tokens, and expiration' },
      { id: 'file-requests', internal: 'file-requests', icon: 'fas fa-inbox', title: 'File Requests', description: 'Upload link defaults, admission limits, and access logs' },
    ],
  },
  {
    id: 'transfer', title: 'Transfers & Network', links: [
      { id: 'uploads', internal: 'uploads', icon: 'fas fa-cloud-arrow-up', title: 'Uploads', description: 'Resumable chunks, concurrency, retention, and cleanup' },
      { id: 'remote-downloads', internal: 'remote-downloads', icon: 'fas fa-cloud-arrow-down', title: 'Remote Downloads', description: 'Direct URL policy, workers, retries, and history' },
      { id: 'vpn', internal: 'vpn', icon: 'fas fa-shield-halved', title: 'VPN Egress', description: 'Startup route, private proxy, and tunnel health policy' },
    ],
  },
  {
    id: 'security', title: 'Security', links: [
      { id: 'account', internal: 'account', icon: 'fas fa-user-lock', title: 'Admin Account', description: 'Admin ID, password, and password login policy' },
      { id: 'passkeys', internal: 'passkeys', icon: 'fas fa-key', title: 'Passkey Devices', description: 'Trusted devices and passkey registration' },
      { id: 'sessions', internal: 'sessions', icon: 'fas fa-laptop', title: 'Sessions', description: 'Concurrent login and idle expiration policy' },
      { id: 'access-identity', internal: 'access-identity', icon: 'fas fa-fingerprint', title: 'Access & Identity', description: 'Public URL, reverse proxies, and passkey identity' },
    ],
  },
  {
    id: 'operations', title: 'Operations', links: [
      { id: 'tasks-staging', internal: 'tasks-staging', icon: 'fas fa-list-check', title: 'Tasks & Staging', description: 'Pending decisions, temporary artifacts, and task workers' },
      { id: 'metadata-activity', internal: 'metadata-activity', icon: 'fas fa-clipboard-list', title: 'Metadata & Activity Logs', description: 'Inspector limits and activity log retention' },
      { id: 'telegram-alerts', internal: 'telegram-alerts', icon: 'fab fa-telegram', title: 'Telegram Alerts', description: 'Bot connection and activity notification types' },
      { id: 'deployment', internal: 'deployment', icon: 'fas fa-server', title: 'Deployment', description: 'Read-only storage, metadata, and VPN control topology' },
    ],
  },
];

const internalSections = Object.fromEntries(
  settingsGroups.flatMap((group) => group.links)
    .map((link) => [link.internal, link]),
) as Record<InternalSectionId, SettingsLink>;

const sectionFromSearch = (search: string): InternalSectionId => {
  const requested = new URLSearchParams(search).get('section');
  return requested && requested in internalSections ? requested as InternalSectionId : 'appearance';
};

export function SettingsApp() {
  const location = useLocation();
  const navigate = useNavigate();
  const [section, setSection] = useState<InternalSectionId>(() => sectionFromSearch(location.search));
  const [dirty, setDirty] = useState(false);
  const active = internalSections[section];

  const confirmNavigation = useCallback(async () => {
    if (!dirty) return true;
    return window.EnderVault!.askConfirmation({
      title: 'Discard unsaved changes?',
      message: 'The current settings have not been saved. Leave this section and discard them?',
      confirmLabel: 'Discard changes',
      danger: true,
    });
  }, [dirty]);

  const openInternal = useCallback(async (next: InternalSectionId) => {
    if (next === section || !(await confirmNavigation())) return;
    setDirty(false);
    setSection(next);
    const search = new URLSearchParams(location.search);
    search.set('section', next);
    navigate({ pathname: location.pathname, search: `?${search.toString()}` }, {
      state: { settingsSection: next },
    });
  }, [confirmNavigation, location.pathname, location.search, navigate, section]);

  useEffect(() => {
    if (!location.hash) return;
    const targetId = decodeURIComponent(location.hash.slice(1));
    const frame = window.requestAnimationFrame(() => {
      document.getElementById(targetId)?.scrollIntoView({ block: 'start' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [location.hash, section]);

  useEffect(() => {
    const next = sectionFromSearch(location.search);
    if (next === section) return undefined;
    let current = true;
    if (!dirty) {
      setSection(next);
      return undefined;
    }
    void confirmNavigation().then((confirmed) => {
      if (!current) return;
      if (confirmed) {
        setDirty(false);
        setSection(next);
        return;
      }
      const search = new URLSearchParams(location.search);
      search.set('section', section);
      navigate({ pathname: location.pathname, search: `?${search.toString()}` }, {
        replace: true,
        state: { settingsSection: section },
      });
    });
    return () => { current = false; };
  }, [confirmNavigation, dirty, location.pathname, location.search, navigate, section]);

  const editor = useMemo(() => {
    const props = { onDirtyChange: setDirty };
    switch (section) {
      case 'appearance': return <GeneralSettings key={section} scope="appearance" {...props} />;
      case 'files': return <GeneralSettings key={section} scope="files" {...props} />;
      case 'file-tools': return <GeneralSettings key={section} scope="file-tools" {...props} />;
      case 'archive-safety': return <AdvancedSettings key={section} groupIds={['archive']} {...props} />;
      case 'share-links': return <AdvancedSettings key={section} groupIds={['sharing']} {...props} />;
      case 'uploads': return <AdvancedSettings key={section} groupIds={['uploads']} {...props} />;
      case 'thumbnails': return <AdvancedSettings key={section} groupIds={['thumbnails']} {...props} />;
      case 'remote-downloads': return <GeneralSettings key={section} scope="remote-downloads" {...props} />;
      case 'access-identity': return <AdvancedSettings key={section} groupIds={['access']} {...props} />;
      case 'tasks-staging': return <AdvancedSettings key={section} groupIds={['staging', 'tasks']} {...props} />;
      case 'metadata-activity': return <AdvancedSettings key={section} groupIds={['metadata', 'activity']} {...props} />;
      case 'deployment': return <AdvancedSettings key={section} includeDeployment {...props} />;
      case 'bookmarks': return <BookmarkSettings key={section} {...props} />;
      case 'file-requests': return <FileRequestSettings key={section} {...props} />;
      case 'vpn': return <VpnSettings key={section} {...props} />;
      case 'account': return <AccountSettings key={section} {...props} />;
      case 'passkeys': return <PasskeySettings key={section} {...props} />;
      case 'sessions': return <SessionSettings key={section} {...props} />;
      case 'telegram-alerts': return <TelegramSettings key={section} {...props} />;
    }
  }, [section]);

  return (
    <section className="dashboard-panel settings-spa-shell" aria-label="Application settings">
      <aside className="settings-spa-sidebar" aria-label="Settings sections">
        <header className="settings-spa-sidebar-heading">
          <h2>Settings</h2>
          <p>Application configuration and access policy</p>
        </header>
        <nav className="settings-spa-nav">
          {settingsGroups.map((group) => (
            <section key={group.id} className="settings-spa-nav-group" aria-labelledby={`settings-nav-${group.id}`}>
              <h3 id={`settings-nav-${group.id}`}>{group.title}</h3>
              {group.links.map((link) => (
                <button
                  key={link.id}
                  type="button"
                  className={`settings-spa-nav-item${section === link.internal ? ' is-active' : ''}`}
                  aria-current={section === link.internal ? 'page' : undefined}
                  onClick={() => void openInternal(link.internal!)}
                >
                  <i className={link.icon} aria-hidden="true" />
                  <span><strong>{link.title}</strong><small><OverflowMarquee text={link.description} /></small></span>
                </button>
              ))}
            </section>
          ))}
        </nav>
      </aside>

      <div className="settings-spa-content">
        <header className="settings-spa-content-heading">
          <span className="settings-spa-heading-icon"><i className={active.icon} aria-hidden="true" /></span>
          <div><h1>{active.title} Settings</h1><p>{active.description}</p></div>
        </header>
        {editor}
      </div>
    </section>
  );
}

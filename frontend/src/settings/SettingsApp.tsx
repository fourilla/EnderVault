import { useCallback, useEffect, useMemo, useState, type MouseEvent } from 'react';
import { AdvancedSettings } from './sections/AdvancedSettings';
import { AccountSettings } from './sections/AccountSettings';
import { BookmarkSettings } from './sections/BookmarkSettings';
import { FileRequestSettings } from './sections/FileRequestSettings';
import { GeneralSettings } from './sections/GeneralSettings';
import { PasskeySettings } from './sections/PasskeySettings';
import { SessionSettings } from './sections/SessionSettings';
import { TelegramSettings } from './sections/TelegramSettings';
import { VpnSettings } from './sections/VpnSettings';

type InternalSectionId = 'general' | 'advanced' | 'bookmarks' | 'file-requests' | 'vpn' | 'account' | 'passkeys' | 'sessions' | 'telegram-alerts';

type SettingsLink = {
  id: string;
  href: string;
  icon: string;
  title: string;
  description: string;
  internal?: InternalSectionId;
};

type SettingsGroup = { id: string; title: string; links: SettingsLink[] };

const settingsGroups: SettingsGroup[] = [
  {
    id: 'application', title: 'Application', links: [
      { id: 'general', internal: 'general', href: '/admin/settings?section=general', icon: 'fas fa-sliders', title: 'General', description: 'Browser, storage, recent items, file tools, and remote downloads' },
      { id: 'advanced', internal: 'advanced', href: '/admin/settings?section=advanced', icon: 'fas fa-gears', title: 'Advanced', description: 'Sharing, transfer engines, maintenance, and deployment' },
      { id: 'bookmarks', internal: 'bookmarks', href: '/admin/settings?section=bookmarks', icon: 'fas fa-bookmark', title: 'Bookmarks', description: 'Link behavior, metadata fetching, and favicon cache' },
      { id: 'file-requests', internal: 'file-requests', href: '/admin/settings?section=file-requests', icon: 'fas fa-inbox', title: 'File Requests', description: 'Upload link defaults, admission limits, and access logs' },
      { id: 'vpn', internal: 'vpn', href: '/admin/settings?section=vpn', icon: 'fas fa-shield-halved', title: 'VPN Egress', description: 'Private outbound proxy and tunnel health policy' },
    ],
  },
  {
    id: 'security', title: 'Security', links: [
      { id: 'account', internal: 'account', href: '/admin/settings?section=account', icon: 'fas fa-user-lock', title: 'Admin Account', description: 'Admin ID, password, and password login policy' },
      { id: 'passkeys', internal: 'passkeys', href: '/admin/settings?section=passkeys', icon: 'fas fa-key', title: 'Passkeys', description: 'Trusted devices and passkey registration' },
      { id: 'sessions', internal: 'sessions', href: '/admin/settings?section=sessions', icon: 'fas fa-laptop', title: 'Sessions', description: 'Concurrent login and idle expiration policy' },
    ],
  },
  {
    id: 'notifications', title: 'Notifications', links: [
      { id: 'telegram-alerts', internal: 'telegram-alerts', href: '/admin/settings?section=telegram-alerts', icon: 'fab fa-telegram', title: 'Telegram Alerts', description: 'Bot connection and activity notification types' },
    ],
  },
];

const internalSections = Object.fromEntries(
  settingsGroups.flatMap((group) => group.links)
    .filter((link): link is SettingsLink & { internal: InternalSectionId } => Boolean(link.internal))
    .map((link) => [link.internal, link]),
) as Record<InternalSectionId, SettingsLink>;

const sectionFromLocation = (): InternalSectionId => {
  const requested = new URLSearchParams(window.location.search).get('section');
  return requested && requested in internalSections ? requested as InternalSectionId : 'general';
};

export function SettingsApp() {
  const [section, setSection] = useState<InternalSectionId>(sectionFromLocation);
  const [dirty, setDirty] = useState(false);
  const active = internalSections[section];

  const confirmNavigation = useCallback(async () => {
    if (!dirty) return true;
    return window.EnderVault?.askConfirmation?.({
      title: 'Discard unsaved changes?',
      message: 'The current settings have not been saved. Leave this section and discard them?',
      confirmLabel: 'Discard changes',
      danger: true,
    }) ?? Promise.resolve(window.confirm('Discard unsaved settings changes?'));
  }, [dirty]);

  const openInternal = useCallback(async (next: InternalSectionId) => {
    if (next === section || !(await confirmNavigation())) return;
    setDirty(false);
    setSection(next);
    const url = new URL(window.location.href);
    url.searchParams.set('section', next);
    url.hash = '';
    window.history.pushState({ settingsSection: next }, '', url);
  }, [confirmNavigation, section]);

  const openLegacy = useCallback(async (event: MouseEvent<HTMLAnchorElement>, href: string) => {
    if (!dirty) return;
    event.preventDefault();
    if (await confirmNavigation()) window.location.assign(href);
  }, [confirmNavigation, dirty]);

  useEffect(() => {
    const url = new URL(window.location.href);
    if (!url.searchParams.has('section')) {
      url.searchParams.set('section', section);
      window.history.replaceState({ settingsSection: section }, '', url);
    }
  }, [section]);

  useEffect(() => {
    if (!window.location.hash) return;
    const targetId = decodeURIComponent(window.location.hash.slice(1));
    const frame = window.requestAnimationFrame(() => {
      document.getElementById(targetId)?.scrollIntoView({ block: 'start' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [section]);

  useEffect(() => {
    const onPopState = () => {
      const next = sectionFromLocation();
      if (!dirty) {
        setSection(next);
        return;
      }
      void confirmNavigation().then((confirmed) => {
        if (confirmed) {
          setDirty(false);
          setSection(next);
        } else {
          const url = new URL(window.location.href);
          url.searchParams.set('section', section);
          window.history.pushState({ settingsSection: section }, '', url);
        }
      });
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [confirmNavigation, dirty, section]);

  const editor = useMemo(() => {
    const props = { onDirtyChange: setDirty };
    switch (section) {
      case 'advanced': return <AdvancedSettings {...props} />;
      case 'bookmarks': return <BookmarkSettings {...props} />;
      case 'file-requests': return <FileRequestSettings {...props} />;
      case 'vpn': return <VpnSettings {...props} />;
      case 'account': return <AccountSettings {...props} />;
      case 'passkeys': return <PasskeySettings {...props} />;
      case 'sessions': return <SessionSettings {...props} />;
      case 'telegram-alerts': return <TelegramSettings {...props} />;
      default: return <GeneralSettings {...props} />;
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
              {group.links.map((link) => link.internal ? (
                <button
                  key={link.id}
                  type="button"
                  className={`settings-spa-nav-item${section === link.internal ? ' is-active' : ''}`}
                  aria-current={section === link.internal ? 'page' : undefined}
                  onClick={() => void openInternal(link.internal!)}
                >
                  <i className={link.icon} aria-hidden="true" />
                  <span><strong>{link.title}</strong><small>{link.description}</small></span>
                </button>
              ) : (
                <a key={link.id} className="settings-spa-nav-item" href={link.href} onClick={(event) => void openLegacy(event, link.href)}>
                  <i className={link.icon} aria-hidden="true" />
                  <span><strong>{link.title}</strong><small>{link.description}</small></span>
                  <i className="fas fa-arrow-up-right-from-square settings-spa-nav-external" aria-hidden="true" />
                </a>
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

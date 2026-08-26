type SettingsLink = {
  href: string;
  icon: string;
  title: string;
  description: string;
};

type SettingsGroup = {
  id: string;
  title: string;
  description: string;
  links: SettingsLink[];
};

const settingsGroups: SettingsGroup[] = [
  {
    id: 'application',
    title: 'Application',
    description: 'Runtime options and operational defaults.',
    links: [
      {
        href: '/admin/settings/general',
        icon: 'fas fa-sliders',
        title: 'General settings',
        description: 'Browser defaults, sticky note appearance, recent items, file tools, and remote download behavior.',
      },
      {
        href: '/admin/settings/advanced',
        icon: 'fas fa-gears',
        title: 'Advanced settings',
        description: 'Sharing, transfer engines, archive safety, maintenance, access identity, and deployment values.',
      },
      {
        href: '/admin/settings/bookmarks',
        icon: 'fas fa-bookmark',
        title: 'Bookmark settings',
        description: 'Link click behavior, metadata fetching, SSRF limits, and favicon cache options.',
      },
      {
        href: '/admin/settings/file-requests',
        icon: 'fas fa-inbox',
        title: 'File request settings',
        description: 'Upload link defaults, token rules, concurrency, access logging, and per-IP admission limits.',
      },
      {
        href: '/admin/settings/vpn',
        icon: 'fas fa-shield-halved',
        title: 'VPN egress',
        description: 'Configure the private outbound proxy and monitor tunnel health.',
      },
    ],
  },
  {
    id: 'security',
    title: 'Security',
    description: 'Account access and trusted login devices.',
    links: [
      {
        href: '/admin/settings/account',
        icon: 'fas fa-user-lock',
        title: 'Admin account',
        description: 'Change admin ID, password, and ID/password login policy.',
      },
      {
        href: '/admin/settings/passkeys',
        icon: 'fas fa-key',
        title: 'Passkeys',
        description: 'Register, review, and remove trusted devices for passkey login.',
      },
      {
        href: '/admin/settings/sessions',
        icon: 'fas fa-laptop',
        title: 'Session settings',
        description: 'Configure concurrent login limits and idle session expiration.',
      },
    ],
  },
  {
    id: 'notifications',
    title: 'Notifications',
    description: 'Activity alerts and delivery channels.',
    links: [
      {
        href: '/admin/settings/telegram-alerts',
        icon: 'fab fa-telegram',
        title: 'Telegram alerts',
        description: 'Configure activity notifications, bot connection, and test messages.',
      },
    ],
  },
];

function SettingsLinkRow({ link }: { link: SettingsLink }) {
  return (
    <a className="settings-link-row" href={link.href}>
      <i className={link.icon} aria-hidden="true" />
      <span>
        <strong>{link.title}</strong>
        <small>{link.description}</small>
      </span>
      <i className="fas fa-chevron-right settings-link-arrow" aria-hidden="true" />
    </a>
  );
}

export function SettingsApp() {
  return (
    <section className="dashboard-panel settings-detail-panel settings-index-panel" aria-label="Settings categories">
      <header className="section-heading">
        <div>
          <h2>Configuration</h2>
          <p>Choose a settings area to edit.</p>
        </div>
      </header>

      {settingsGroups.map((group) => (
        <section className="settings-detail-section" aria-labelledby={`settings-${group.id}`} key={group.id}>
          <div className="settings-section-copy">
            <h2 id={`settings-${group.id}`}>{group.title}</h2>
            <p>{group.description}</p>
          </div>
          <div className="settings-link-list">
            {group.links.map((link) => (
              <SettingsLinkRow key={link.href} link={link} />
            ))}
          </div>
        </section>
      ))}
    </section>
  );
}

import { useEffect, useState } from 'react';
import { AppNavigationLink } from '../app/AppNavigationLink';
import { useAdminApp } from '../app/AdminAppContext';
import { toastError } from '../shared/api/form-api';
import { PageHeader } from '../shared/layout/PageHeader';
import type { VpnCommand, VpnRuntimeStatus } from './types';
import { loadVpnStatus, runVpnCommand } from './vpn-api';

export function VpnStatusApp() {
  const { refreshBootstrap } = useAdminApp();
  const [vpn, setVpn] = useState<VpnRuntimeStatus | null>(null);
  const [busy, setBusy] = useState<VpnCommand | ''>('');
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    void loadVpnStatus(controller.signal)
      .then((next) => {
        setVpn(next);
        setError('');
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) {
          setError(reason instanceof Error ? reason.message : 'VPN status could not be loaded.');
        }
      });
    return () => controller.abort();
  }, []);

  const run = async (command: VpnCommand) => {
    if (busy || !vpn) return;
    let force = false;
    if ((command === 'disconnect' || command === 'reconnect') && vpn.activeVpnTasks > 0) {
      const confirmed = await window.EnderVault?.askConfirmation({
        title: command === 'disconnect' ? 'Disconnect VPN' : 'Reconnect VPN',
        message: `${vpn.activeVpnTasks} VPN remote download task(s) are active and may fail. Continue?`,
        confirmLabel: command === 'disconnect' ? 'Disconnect' : 'Reconnect',
        danger: true,
      });
      if (!confirmed) return;
      force = true;
    }

    setBusy(command);
    try {
      const body = await runVpnCommand(command, force);
      if (body.vpn) setVpn(body.vpn);
      setError('');
      await refreshBootstrap();
    } catch (reason) {
      toastError(reason, command === 'refresh'
        ? 'VPN status could not be refreshed.'
        : 'VPN control command failed.');
    } finally {
      setBusy('');
    }
  };

  const copyPublicIp = async () => {
    if (!vpn || vpn.publicIp === 'Unavailable') return;
    if (await window.EnderVault?.copyText(vpn.publicIp)) {
      window.EnderVault?.showToast('success', 'VPN public IP copied.');
    } else {
      window.EnderVault?.showToast('error', 'Copy failed.');
    }
  };

  return (
    <div className="dashboard-workspace vpn-status-workspace">
      <PageHeader
        title="VPN Status"
        description="Monitor and control the private Gluetun outbound tunnel."
        actions={<AppNavigationLink className="ghost icon-text-button" href="/admin/settings?section=vpn">
          <i className="fas fa-gear" aria-hidden="true" /><span>Settings</span>
        </AppNavigationLink>}
      />

      {error && <section className="dashboard-panel browser-load-error" role="alert">{error}</section>}
      {!vpn && !error && (
        <section className="browser-load-progress" role="status" aria-live="polite">
          <i className="fas fa-spinner fa-spin" aria-hidden="true" /><span>Loading VPN status...</span>
        </section>
      )}
      {vpn && (
        <section className="vpn-status-grid">
          <article className="dashboard-panel">
            <header className="section-heading">
              <div><h2>Connection Details</h2><p>Control status and health are independent checks.</p></div>
              <span className={`status-badge ${vpn.health.statusClass}`}>{vpn.health.label}</span>
            </header>
            <dl className="vpn-detail-list">
              <div><dt>Tunnel</dt><dd><span className={`status-badge ${vpn.statusClass}`}>{vpn.label}</span></dd></div>
              <div>
                <dt>VPN public IP</dt>
                <dd className="vpn-copy-field">
                  <span className="vpn-public-ip">{vpn.publicIp}</span>
                  <button className="ghost icon-button action-icon" type="button" onClick={() => void copyPublicIp()}
                    disabled={vpn.publicIp === 'Unavailable'} title="Copy VPN public IP" aria-label="Copy VPN public IP">
                    <i className="fas fa-copy" aria-hidden="true" />
                  </button>
                </dd>
              </div>
              <div><dt>Outbound route</dt><dd><span className={`status-badge ${vpn.vpnRouteSelected ? 'active' : 'info'}`}>{vpn.routeLabel}</span></dd></div>
              <div><dt>Proxy endpoint</dt><dd>{vpn.health.proxyEndpoint}</dd></div>
              <div><dt>Last checked</dt><dd>{vpn.checkedAtLabel}</dd></div>
              <div><dt>Control latency</dt><dd>{vpn.latencyLabel}</dd></div>
              <div className="vpn-detail-wide vpn-active-tasks"><dt>Active VPN tasks</dt><dd>{vpn.activeVpnTasks}</dd></div>
              <div className="vpn-detail-wide"><dt>Control detail</dt><dd>{vpn.detail}</dd></div>
              <div className="vpn-detail-wide"><dt>Health detail</dt><dd>{vpn.health.detail}</dd></div>
            </dl>
          </article>

          <article className="dashboard-panel vpn-control-panel">
            <header className="section-heading">
              <div><h2>Connection Control</h2><p>Disconnecting never falls back to the Direct route.</p></div>
            </header>
            <div className="vpn-control-actions">
              <button className="ghost icon-text-button" type="button" disabled={Boolean(busy)} onClick={() => void run('refresh')}>
                <i className={`fas fa-rotate${busy === 'refresh' ? ' fa-spin' : ''}`} aria-hidden="true" /><span>Refresh</span>
              </button>
              <button className="primary icon-text-button" type="button"
                disabled={Boolean(busy) || !vpn.controllable || vpn.running} onClick={() => void run('connect')}>
                <i className="fas fa-play" aria-hidden="true" /><span>Connect</span>
              </button>
              <button className="ghost icon-text-button" type="button"
                disabled={Boolean(busy) || !vpn.controllable} onClick={() => void run('reconnect')}>
                <i className="fas fa-arrows-rotate" aria-hidden="true" /><span>Reconnect</span>
              </button>
              <button className="danger icon-text-button" type="button"
                disabled={Boolean(busy) || !vpn.controllable || !vpn.running} onClick={() => void run('disconnect')}>
                <i className="fas fa-stop" aria-hidden="true" /><span>Disconnect</span>
              </button>
            </div>
            <p className="vpn-control-note">Stopping or reconnecting the tunnel may fail active VPN-routed work.</p>
          </article>
        </section>
      )}
    </div>
  );
}

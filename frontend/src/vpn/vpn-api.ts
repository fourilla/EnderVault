import { notify, postForm } from '../shared/api/form-api';
import type { VpnCommand, VpnControlPayload, VpnRuntimeStatus } from './types';

export async function loadVpnStatus(signal?: AbortSignal): Promise<VpnRuntimeStatus> {
  const response = await fetch('/api/v1/vpn/status', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as VpnRuntimeStatus & { message?: string };
  if (!response.ok || !body.health) {
    throw new Error(body.message || 'VPN status could not be loaded.');
  }
  return body;
}

export async function runVpnCommand(command: VpnCommand, force = false): Promise<VpnControlPayload> {
  const body = await postForm(`/api/v1/vpn/${command}`, {
    force: force ? 'true' : undefined,
  }) as VpnControlPayload;
  notify(body);
  window.dispatchEvent(new CustomEvent('endervault:outbound-route-changed'));
  return body;
}

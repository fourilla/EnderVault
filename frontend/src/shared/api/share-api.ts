import { notify, postForm } from './form-api';

export async function createShareAndCopy(path: string) {
  const body = await postForm('/api/v1/shares', { path });
  const url = body.shareLink?.url || body.notification?.actionValue || '';
  if (url && await window.EnderVault?.copyText(url)) {
    window.EnderVault?.showToast('success', 'Share link created and copied.');
  } else {
    notify(body);
  }
}

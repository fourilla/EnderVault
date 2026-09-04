import { notify, postForm } from '../shared/api/form-api';
import type { MetadataPagePayload, MetadataRepairPayload, MetadataScanPayload } from './types';

export async function loadMetadataInspector(signal: AbortSignal): Promise<MetadataPagePayload> {
  const response = await fetch('/api/v1/metadata', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  const body = await response.json() as MetadataPagePayload & { message?: string };
  if (!response.ok || !Array.isArray(body.areas)) {
    throw new Error(body.message || 'Metadata inspector could not be loaded.');
  }
  return body;
}

export async function startMetadataScan(areas: string[]): Promise<MetadataScanPayload> {
  const body = await postForm('/api/v1/metadata/scan', { areas }) as MetadataScanPayload;
  notify(body);
  return body;
}

export async function repairMetadataIssues(
  issues: string[],
  repairAll = false,
): Promise<MetadataRepairPayload> {
  const body = await postForm('/api/v1/metadata/repair', {
    issues,
    repairAll: repairAll ? 'true' : undefined,
  }) as MetadataRepairPayload;
  notify(body);
  return body;
}

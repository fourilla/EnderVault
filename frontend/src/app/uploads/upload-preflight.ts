export async function confirmAdminUpload(names: string[], path: string): Promise<boolean> {
  const unique = [...new Set(names)];
  if (!unique.length) return false;
  const client = window.EnderVault;
  if (!client) throw new Error('Upload services are unavailable.');
  const csrf = client.csrfPair();
  const header = document.querySelector<HTMLMetaElement>('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  const conflicts: string[] = [];
  for (let offset = 0; offset < unique.length; offset += 200) {
    const result = await client.requestJson('/api/v1/files/upload-preflight', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(csrf ? { [header]: csrf.value } : {}) },
      body: JSON.stringify({ path, names: unique.slice(offset, offset + 200) }),
    }) as { conflicts: string[] };
    conflicts.push(...result.conflicts);
  }
  if (!conflicts.length) return true;
  return client.askConfirmation({
    title: 'Existing upload destinations',
    message: `These names already exist in ${path || '/'}: ${conflicts.join(', ')}. Continue uploading? `
      + 'This does not approve overwriting. Any conflicts after upload still require a decision.',
    confirmLabel: 'Continue upload',
  });
}

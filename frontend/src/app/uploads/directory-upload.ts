import { rootSignature, validateRoot, type DirectoryRoot } from './collect-uploads';

interface DirectoryStatus {
  id: string;
  status: 'RECEIVING' | 'COMMITTING' | 'PENDING' | 'COMPLETED' | 'CANCELING' | 'CANCELED';
  completedPaths: string[];
  expiresAt?: string;
  pendingDecisionId?: string;
  committedPath?: string;
}

async function request(url: string, method = 'GET', body?: unknown): Promise<DirectoryStatus> {
  const token = document.querySelector<HTMLMetaElement>('meta[name="_csrf"]')?.content;
  const header = document.querySelector<HTMLMetaElement>('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  return window.EnderVault!.requestJson(url, {
    method, credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', ...(token ? { [header]: token } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

export class DirectoryUpload {
  private canceled = false;
  private member?: EnderVaultUploadHandle;
  private id?: string;
  private key: string;
  private starting?: Promise<DirectoryStatus | null>;

  constructor(private root: DirectoryRoot, private destination: string,
    private progress: (bytes: number, count: number) => void,
    private state: (message: string) => void) {
    validateRoot(root);
    this.key = `endervault-directory-upload:${rootSignature(root, destination)}`;
  }

  private remember(id?: string) {
    try {
      if (id) window.localStorage.setItem(this.key, id);
      else window.localStorage.removeItem(this.key);
    } catch { /* Reselection resume is unavailable when storage is disabled. */ }
  }

  start() {
    this.starting = this.run().catch((error: unknown) => {
      if (error && typeof error === 'object') {
        Object.assign(error, { stagingRetained: Boolean(this.id) });
      }
      throw error;
    });
    return this.starting;
  }

  async abort() {
    this.canceled = true;
    await this.member?.abort();
    // Admission may still be in flight; wait until no member can start before deleting the root.
    await this.starting?.catch(() => undefined);
    if (!this.id) return { status: 'CANCELED' as const };
    const url = `/api/v1/files/directory-uploads/${encodeURIComponent(this.id)}`;
    let result: DirectoryStatus;
    try {
      result = await request(url, 'DELETE');
    } catch (error) {
      if ((error as { status?: number }).status !== 409) throw error;
      result = await request(url);
    }
    while (['COMMITTING', 'CANCELING'].includes(result.status)) {
      this.state(result.status === 'COMMITTING' ? 'Commit already in progress; checking outcome...' : 'Canceling directory...');
      await new Promise((resolve) => window.setTimeout(resolve, 1000));
      result = await request(url);
    }
    if (!['COMPLETED', 'PENDING', 'CANCELED'].includes(result.status)) {
      throw new Error('Directory cancellation was not confirmed. Reselect the directory to retry.');
    }
    this.remember();
    return result;
  }

  private async run(): Promise<DirectoryStatus | null> {
    let resumeId: string | undefined;
    try { resumeId = window.localStorage.getItem(this.key) || undefined; } catch { /* Storage is optional. */ }
    const base = '/api/v1/files/directory-uploads';
    if (resumeId) {
      this.id = resumeId;
      try {
        const previous = await request(`${base}/${encodeURIComponent(resumeId)}`);
        if (['COMPLETED', 'PENDING', 'CANCELED'].includes(previous.status)) {
          this.remember();
          resumeId = undefined;
          this.id = undefined;
        }
      } catch (error) {
        if (![404, 410].includes((error as { status?: number }).status || 0)) throw error;
        this.remember();
        resumeId = undefined;
        this.id = undefined;
      }
    }
    if (this.canceled) return null;
    let group = await request(`${base}?path=${encodeURIComponent(this.destination)}`, 'POST', {
      name: this.root.name,
      files: this.root.files.map(({ path, file }) => ({ path, size: file.size, lastModified: file.lastModified || 0 }))
        .sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0),
      directories: [...this.root.directories].sort(), ...(resumeId ? { resumeId } : {}),
    });
    this.id = group.id;
    this.remember(group.id);
    const url = `${base}/${encodeURIComponent(group.id)}`;
    if (this.canceled) return null;
    if (group.status === 'RECEIVING') {
      const completed = new Set(group.completedPaths);
      let bytes = this.root.files.reduce((sum, item) => sum + (completed.has(item.path) ? item.file.size : 0), 0);
      let count = this.root.files.filter((item) => completed.has(item.path)).length;
      this.progress(bytes, count);
      for (const { path, file } of this.root.files) {
        if (this.canceled) return null;
        if (completed.has(path)) continue;
        this.member = window.EnderVaultResumableUpload!.create({
          file, admissionUrl: `${url}/files?relativePath=${encodeURIComponent(path)}`,
          context: JSON.stringify([this.destination, this.root.name, group.id, path]),
          onProgress: (sent: number) => this.progress(bytes + sent, count),
        });
        const result = await this.member.start();
        this.member = undefined;
        if (this.canceled) return null;
        if (result?.status !== 'DIRECTORY_READY') throw new Error(result?.message || `Could not stage ${path}.`);
        bytes += file.size;
        count++;
        this.progress(bytes, count);
      }
      if (this.canceled) return null;
      this.state('Finalizing directory...');
      group = await request(`${url}/complete`, 'POST');
    }
    while (['COMMITTING', 'CANCELING'].includes(group.status) && !this.canceled) {
      this.state(group.status === 'COMMITTING' ? 'Finalizing directory...' : 'Canceling directory...');
      await new Promise((resolve) => window.setTimeout(resolve, 1000));
      if (!this.canceled) group = await request(url);
    }
    if (['COMPLETED', 'PENDING', 'CANCELED'].includes(group.status)) this.remember();
    return this.canceled ? null : group;
  }
}

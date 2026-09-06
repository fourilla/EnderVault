export interface DirectoryRoot {
  name: string;
  files: Array<{ path: string; file: File }>;
  directories: string[];
}

export interface UploadSelection {
  files: File[];
  roots: DirectoryRoot[];
}

const MAX_ENTRIES = 10000;

function checkPath(path: string) {
  const parts = path.split('/');
  if (path.length > 1024 || parts.length > 64
    || parts.some((part) => !part || part === '.' || part === '..' || /[\\\x00-\x1f:]/.test(part))) {
    throw new Error('Invalid directory path, or path exceeds 64 levels / 1024 characters.');
  }
}

export function validateRoot(root: DirectoryRoot) {
  checkPath(root.name);
  if (root.name.includes('/')) throw new Error('Invalid directory root name.');
  const paths = new Set<string>();
  for (const path of [...root.directories, ...root.files.map((member) => member.path)]) {
    checkPath(path);
    if (paths.has(path)) throw new Error('Duplicate directory upload path.');
    paths.add(path);
  }
  if (paths.size + 1 > MAX_ENTRIES) throw new Error('Directory uploads are limited to 10000 entries.');
  const filePaths = new Set(root.files.map((member) => member.path));
  const allEntries = new Set(paths);
  for (const path of paths) {
    const parts = path.split('/');
    while (parts.length > 1) {
      parts.pop();
      if (filePaths.has(parts.join('/'))) throw new Error('A file cannot contain another upload entry.');
      allEntries.add(parts.join('/'));
    }
  }
  if (allEntries.size + 1 > MAX_ENTRIES) throw new Error('Directory uploads are limited to 10000 entries.');
  return allEntries.size + 1;
}

// FileList exposes relative file paths, but cannot represent empty directories.
export function collectFileList(files: Iterable<File>): UploadSelection {
  const selection: UploadSelection = { files: [], roots: [] };
  const roots = new Map<string, DirectoryRoot>();
  let count = 0;
  for (const file of files) {
    if (++count > MAX_ENTRIES) throw new Error('Uploads are limited to 10000 entries per selection.');
    if (!file.webkitRelativePath) { selection.files.push(file); continue; }
    const [name, ...parts] = file.webkitRelativePath.split('/');
    if (!parts.length) throw new Error('Directory selection did not expose relative paths.');
    let root = roots.get(name);
    if (!root) { root = { name, files: [], directories: [] }; roots.set(name, root); }
    root.files.push({ path: parts.join('/'), file });
  }
  selection.roots = [...roots.values()];
  const entries = selection.roots.reduce((sum, root) => sum + validateRoot(root), selection.files.length);
  if (entries > MAX_ENTRIES) throw new Error('Uploads are limited to 10000 entries per selection.');
  return selection;
}

export async function collectDrop(transfer: DataTransfer): Promise<UploadSelection> {
  // Capture protected drag data synchronously, before the first asynchronous read.
  const sources = [...transfer.items].filter((item) => item.kind === 'file').map((item) => ({
    entry: item.webkitGetAsEntry?.(), file: item.getAsFile(),
  }));
  const fallback = [...transfer.files];
  if (!sources.length) return collectFileList(fallback);
  const result: UploadSelection = { files: [], roots: [] };
  const looseFiles: File[] = [];
  let count = 0;
  const visit = async (entry: FileSystemEntry, path: string, root: DirectoryRoot) => {
    if (++count > MAX_ENTRIES) throw new Error('Uploads are limited to 10000 entries per selection.');
    checkPath(path || root.name);
    if (entry.isFile) {
      const file = await new Promise<File>((resolve, reject) => (entry as FileSystemFileEntry).file(resolve, reject));
      root.files.push({ path, file });
    } else if (entry.isDirectory) {
      const reader = (entry as FileSystemDirectoryEntry).createReader();
      let empty = true;
      for (;;) {
        const entries = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
        if (!entries.length) break;
        empty = false;
        for (const child of entries) {
          checkPath(child.name);
          if (child.name.includes('/')) throw new Error('Invalid directory entry name.');
          await visit(child, path ? `${path}/${child.name}` : child.name, root);
        }
      }
      if (empty && path) root.directories.push(path);
    } else throw new Error('Unsupported directory entry.');
  };
  for (const source of sources) {
    if (source.entry?.isDirectory) {
      const root: DirectoryRoot = { name: source.entry.name, files: [], directories: [] };
      await visit(source.entry, '', root);
      validateRoot(root);
      result.roots.push(root);
    } else if (source.file) {
      if (++count > MAX_ENTRIES) throw new Error('Uploads are limited to 10000 entries per selection.');
      looseFiles.push(source.file);
    } else throw new Error('The browser could not read a dropped item.');
  }
  const loose = collectFileList(looseFiles);
  result.files.push(...loose.files);
  result.roots.push(...loose.roots);
  return result;
}

export function rootSignature(root: DirectoryRoot, destination: string) {
  return JSON.stringify([destination, root.name,
    root.files.map(({ path, file }) => [path, file.size, file.lastModified || 0])
      .sort((a, b) => String(a[0]) < String(b[0]) ? -1 : String(a[0]) > String(b[0]) ? 1 : 0),
    [...root.directories].sort()]);
}

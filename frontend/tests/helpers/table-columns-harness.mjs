import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { build } from 'vite';

const result = await build({ configFile: false, logLevel: 'silent', build: { write: false, minify: false,
  lib: { entry: fileURLToPath(new URL('../../src/shared/browser/useTableColumns.ts', import.meta.url)), formats: ['cjs'] },
  rolldownOptions: { external: (_id, importer) => Boolean(importer) } } });
const code = (Array.isArray(result) ? result[0] : result).output.find(item => item.type === 'chunk').code;

export function tableColumnsHarness() {
  const bootstrap = { browser: { showTableActions: true } }, module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports,
    require: () => ({ useAdminApp: () => ({ bootstrap }) }) });
  return { ...module.exports, setShown(value) { bootstrap.browser.showTableActions = value; } };
}

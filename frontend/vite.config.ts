import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/react/',
  server: {
    port: 5173,
    strictPort: true,
    host: '127.0.0.1',
    cors: true,
  },
  build: {
    manifest: true,
    outDir: '../target/generated-resources/frontend/static/react',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        styles: 'src/styles/main.ts',
        shell: 'src/shell/main.ts',
        fileTools: 'src/file-tools/main.ts',
        markdown: 'src/markdown/main.ts',
        files: 'src/files/main.tsx',
        recent: 'src/recent/main.tsx',
        bookmarks: 'src/bookmarks/main.tsx',
        favorites: 'src/favorites/main.tsx',
        settings: 'src/settings/main.tsx',
      },
    },
  },
});

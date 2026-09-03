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
        adminApp: 'src/app/main.tsx',
        fileTools: 'src/file-tools/main.ts',
        sharedImage: 'src/shared-file/image.tsx',
        markdown: 'src/markdown/main.ts',
      },
    },
  },
});

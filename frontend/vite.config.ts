import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the backend, so the browser talks to one origin:
// no CORS, and the refresh-token cookie (SameSite=Strict, Path=/api/v1/auth) just works.
// Point it at your backend with PULSEOPS_BACKEND_URL (default http://localhost:8080).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.PULSEOPS_BACKEND_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
  },
});

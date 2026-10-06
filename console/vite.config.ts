import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vitest/config';

// The build knows nothing of any environment: the Console calls /api/v1 on the origin that served
// it (ADR-015). The proxy below exists only in the development server (`npm run dev`) and is not
// part of what `vite build` writes.
const engine = process.env.RUNLINE_ENGINE_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [svelte()],
  build: {
    // Files whose names carry a hash go under assets/, which the Engine caches for good.
    assetsDir: 'assets',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': { target: engine, ws: true },
    },
  },
  // Svelte's browser build, so that a component can be mounted in the test's DOM.
  resolve: process.env.VITEST ? { conditions: ['browser'] } : undefined,
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
});

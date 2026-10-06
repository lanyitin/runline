import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vitest/config';
import { catalogs } from './src/i18n/catalogs.ts';
import { failBuildOnCatalogProblems } from './tools/catalog-plugin.ts';
import { forbidMarkupInsertion } from './tools/untrusted-content.ts';

// The build knows nothing of any environment: the Console calls /api/v1 on the origin that served
// it (ADR-015). The proxy below exists only in the development server (`npm run dev`) and is not
// part of what `vite build` writes.
const engine = process.env.RUNLINE_ENGINE_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [forbidMarkupInsertion(), failBuildOnCatalogProblems(catalogs), svelte()],
  build: {
    // Files whose names carry a hash go under assets/, which the Engine caches for good.
    assetsDir: 'assets',
    emptyOutDir: true,
    // The Engine's content security policy allows fonts from the origin only, not `data:`: nothing,
    // the fonts above all, may be inlined into the CSS as a data URL.
    assetsInlineLimit: 0,
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
    include: [
      'src/**/*.test.ts',
      'tools/**/*.test.ts',
      'test-support/**/*.test.ts',
      'contract/fake-engine.contract.test.ts',
    ],
  },
});

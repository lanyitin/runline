import { defineConfig } from 'vitest/config';

// `npm run e2e`: the Console in a real browser (Chrome through playwright-core), against a real,
// running, packaged Engine (E2E_ENGINE_URL). Not part of `npm test`: it needs a browser and an
// Engine with its PostgreSQL. See the Console section of the README.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['e2e/**/*.e2e.ts'],
    testTimeout: 60_000,
    hookTimeout: 120_000,
    fileParallelism: false,
  },
});

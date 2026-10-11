import { defineConfig } from 'vitest/config';

// `npm run e2e`: the Console in a real browser (Chrome through playwright-core), against a real,
// running, packaged Engine (E2E_ENGINE_URL). Not part of `npm test`: it needs a browser and an
// Engine with its PostgreSQL. See the Console section of the README. e2e/oidc.e2e.ts is left out: it
// needs a Keycloak and an Engine with login on, and has its own command (`npm run e2e:oidc`,
// vitest.e2e-oidc.config.ts).
export default defineConfig({
  test: {
    environment: 'node',
    include: ['e2e/**/*.e2e.ts'],
    exclude: ['e2e/oidc.e2e.ts'],
    testTimeout: 60_000,
    hookTimeout: 120_000,
    fileParallelism: false,
  },
});

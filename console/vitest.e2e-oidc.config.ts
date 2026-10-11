import { defineConfig } from 'vitest/config';

// `npm run e2e:oidc`: the sign-in with a provider in a real browser, against a real, running,
// packaged Engine with login on (E2E_OIDC_ENGINE_URL) and a real Keycloak (E2E_OIDC_PROVIDER_URL).
// It is the one script `npm run e2e` leaves out (vitest.e2e.config.ts), so the day to day run needs
// neither; without what it needs this command fails, it never skips. See e2e/README.md.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['e2e/oidc.e2e.ts'],
    testTimeout: 60_000,
    hookTimeout: 120_000,
    fileParallelism: false,
  },
});

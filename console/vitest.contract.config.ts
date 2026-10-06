import { defineConfig } from 'vitest/config';

// `npm run test:contract`: the contract tests against a real, running Engine (RUNLINE_ENGINE_URL).
// Runs of the sample pipelines take seconds, so a test gets a generous time.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['contract/real-engine.contract.test.ts'],
    testTimeout: 60_000,
  },
});

import { defineConfig } from 'vitest/config';

// `npm run check:api-doc` (RUNLINE_API_DOC = path of 08-api.md): the translations against the API's
// document.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['checks/api-translations.check.ts'],
  },
});

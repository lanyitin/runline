import { checkCatalogs } from '../src/i18n/catalog-check.ts';
import type { Catalogs } from '../src/i18n/translator.ts';

/**
 * A Vite plugin: the build fails, listing every problem, when the language files disagree (ADR-015:
 * a missing key must not wait for the runtime to be found).
 */
export function failBuildOnCatalogProblems(catalogs: Catalogs) {
  return {
    name: 'runline-check-catalogs',
    buildStart() {
      const problems = checkCatalogs(catalogs);
      if (problems.length > 0) {
        throw new Error(
          `The language files of the Console (src/i18n/locales) disagree:\n  ${problems.join('\n  ')}`,
        );
      }
    },
  };
}

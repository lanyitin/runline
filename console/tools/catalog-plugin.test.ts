import { describe, expect, test } from 'vitest';
import { catalogs } from '../src/i18n/catalogs';
import { failBuildOnCatalogProblems } from './catalog-plugin';

const start = (plugin: ReturnType<typeof failBuildOnCatalogProblems>) =>
  (plugin.buildStart as () => void).call({});

describe('failBuildOnCatalogProblems', () => {
  test('lets the build go on when the catalogs agree', () => {
    expect(() => start(failBuildOnCatalogProblems(catalogs))).not.toThrow();
  });

  test('fails the build and names every missing key', () => {
    const broken = { ...catalogs, 'zh-TW': { ...catalogs['zh-TW'] } };
    delete (broken['zh-TW'] as Record<string, string>)['nav.runs'];
    delete (broken['zh-TW'] as Record<string, string>)['nav.upload'];

    const attempt = () => start(failBuildOnCatalogProblems(broken));

    expect(attempt).toThrow(/nav\.runs/);
    expect(attempt).toThrow(/nav\.upload/);
    expect(attempt).toThrow(/zh-TW/);
  });
});

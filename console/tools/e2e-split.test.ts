// @vitest-environment node
import { readdirSync } from 'node:fs';
import { matchesGlob } from 'node:path';
import { describe, expect, test } from 'vitest';
import defaultConfig from '../vitest.e2e.config';
import oidcConfig from '../vitest.e2e-oidc.config';

// The browser tests that need a Keycloak and an Engine with login on (e2e/oidc.e2e.ts) are run by
// their own command (`npm run e2e:oidc`); `npm run e2e` must not touch them, so that the day to day
// run needs neither (WI-1208, ADR-031). This is about which files each command selects, not about
// whether the environment is there: neither command skips anything by looking at it.

const scripts = readdirSync('e2e')
  .filter((name) => name.endsWith('.e2e.ts'))
  .map((name) => `e2e/${name}`);

const selected = (config: { test?: { include?: string[]; exclude?: string[] } }) =>
  scripts.filter(
    (file) =>
      (config.test?.include ?? []).some((glob) => matchesGlob(file, glob)) &&
      !(config.test?.exclude ?? []).some((glob) => matchesGlob(file, glob)),
  );

describe('the two commands of the browser tests', () => {
  test('npm run e2e selects every script but the sign-in with a provider', () => {
    expect(scripts).toContain('e2e/oidc.e2e.ts');
    expect(selected(defaultConfig).sort()).toEqual(
      scripts.filter((file) => file !== 'e2e/oidc.e2e.ts').sort(),
    );
  });

  test('npm run e2e:oidc selects only the sign-in with a provider', () => {
    expect(selected(oidcConfig)).toEqual(['e2e/oidc.e2e.ts']);
  });
});

import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import { findBoundaryViolations, listOwnSources } from './auth-boundary';

const rules = (source: string, file = 'src/pages/Runs.svelte') =>
  findBoundaryViolations(source, file).map((v) => v.rule);

describe('what only the authentication boundary may do', () => {
  test.each([
    ['reads the storage of the tab', "const t = sessionStorage.getItem('x');", 'sessionStorage'],
    ['talks on a channel between tabs', "new BroadcastChannel('x')", 'BroadcastChannel'],
    [
      'sets the Authorization header',
      "headers: { Authorization: 'x' }",
      'the Authorization header',
    ],
    ['speaks of a Bearer token', 'const scheme = `Bearer ${x}`;', 'a Bearer token'],
    ['calls the network on its own', "await fetch('/api/v1/runs')", 'fetch'],
    ['calls the network with a request object', 'const r = await window.fetch(url)', 'fetch'],
    ['opens a WebSocket', "new WebSocket('ws://x')", 'WebSocket'],
    ['opens an EventSource', "new EventSource('/x')", 'EventSource'],
    ['uses XMLHttpRequest', 'new XMLHttpRequest()', 'XMLHttpRequest'],
  ])('a screen that %s is a violation', (_what, source, rule) => {
    expect(rules(source)).toEqual([rule]);
  });

  test.each([
    "import { createTokenMethod } from '../auth/token-method.ts';",
    "import TokenSignIn from '../auth/TokenSignIn.svelte';",
    "import { createCredentialStore } from '../auth/credential-store';",
    "import { createTabSync } from '../auth/tab-sync';",
    "import { createSession } from '../auth/session.svelte';",
  ])('a screen that imports code of the boundary (%s) is a violation', (source) => {
    expect(rules(source)).toEqual(['an import of the authentication boundary']);
  });

  test('a screen may name the types of the boundary', () => {
    expect(
      rules(
        "import type { Session } from '../auth/session.svelte';\nimport type { SignInProps } from '../auth/method';",
      ),
    ).toEqual([]);
  });

  test('the violation says where it is', () => {
    expect(findBoundaryViolations("ok();\nnew BroadcastChannel('x')", 'src/a.ts')).toEqual([
      { file: 'src/a.ts', line: 2, rule: 'BroadcastChannel' },
    ]);
  });

  test('the boundary itself, and the composition root, are free to', () => {
    const source =
      "sessionStorage; new BroadcastChannel('x'); fetch('/y'); import { createSession } from './auth/session.svelte';";
    expect(rules(source, 'src/auth/session.svelte.ts')).toEqual([]);
    expect(rules(source, 'src/bootstrap.ts')).toEqual([]);
    expect(rules(source, 'src/main.ts')).toEqual([]);
  });

  test('the public info of the Engine, which needs no credential, may fetch', () => {
    expect(rules("fetch('/api/v1/info')", 'src/engine/info.ts')).toEqual([]);
    expect(rules("fetch('/api/v1/info')", 'src/engine/other.ts')).toEqual(['fetch']);
  });

  test('a method that is only a word in a comment is no violation of fetch', () => {
    expect(rules('// we do not fetch here\nconst prefetch = 1;')).toEqual([]);
  });
});

describe('the sources of the Console', () => {
  test('are found, and the boundary is among them', () => {
    const sources = listOwnSources('src');
    expect(sources).toContain('src/auth/session.svelte.ts');
    expect(sources).toContain('src/App.svelte');
    expect(sources.some((file) => /\.test\.ts$/.test(file))).toBe(false);
  });

  test('keep every credential, storage, channel and request behind the boundary', () => {
    const found = listOwnSources('src').flatMap((file) =>
      findBoundaryViolations(readFileSync(file, 'utf8'), file),
    );
    expect(found).toEqual([]);
  });
});

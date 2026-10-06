// The static rule behind the authentication boundary (ADR-017, WI-34): the credential of a session,
// where it is kept, how it is shared between the tabs and how a request gets it, are src/auth/'s
// business and nobody else's. A screen, a route or a data access that reads the tab's storage,
// opens the channel between tabs, names the Authorization header or a Bearer token, or sends a
// request on its own, would have to be rewritten the day the way to sign in changes; so the tests
// fail on it.

import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

export interface BoundaryViolation {
  file: string;
  line: number;
  rule: string;
}

const RULES: ReadonlyArray<{ rule: string; pattern: RegExp }> = [
  { rule: 'sessionStorage', pattern: /\bsessionStorage\b/ },
  { rule: 'BroadcastChannel', pattern: /\bBroadcastChannel\b/ },
  { rule: 'the Authorization header', pattern: /\bAuthorization\b/ },
  { rule: 'a Bearer token', pattern: /\bBearer\b/ },
  { rule: 'fetch', pattern: /(?<![.\w])fetch\s*\(|\b(?:window|globalThis|self)\s*\.\s*fetch\s*\(/ },
  { rule: 'WebSocket', pattern: /\bWebSocket\b/ },
  { rule: 'EventSource', pattern: /\bEventSource\b/ },
  { rule: 'XMLHttpRequest', pattern: /\bXMLHttpRequest\b/ },
  {
    rule: 'an import of the authentication boundary',
    pattern: /^\s*import\s+(?!type\b)[^;]*from\s+['"][^'"]*\/auth\//,
  },
];

/** Where the rules do not apply: the boundary, and what puts the Console together from its parts. */
const FREE = [/^src\/auth\//, /^src\/bootstrap\.ts$/, /^src\/main\.ts$/];

/**
 * The one call outside the boundary: the public `GET /api/v1/info`, which needs no credential and
 * is read before anyone has signed in.
 */
const PUBLIC_CALLS = new Set(['src/engine/info.ts']);

/** Every place in [source] that does what only the boundary may do. */
export function findBoundaryViolations(source: string, file: string): BoundaryViolation[] {
  const path = file.replaceAll('\\', '/');
  if (FREE.some((free) => free.test(path))) return [];
  const found: BoundaryViolation[] = [];
  source.split('\n').forEach((text, index) => {
    const code = text.replace(/\/\/.*$/, '');
    for (const { rule, pattern } of RULES) {
      if (rule === 'fetch' && PUBLIC_CALLS.has(path)) continue;
      if (pattern.test(rule === 'an import of the authentication boundary' ? text : code)) {
        found.push({ file, line: index + 1, rule });
      }
    }
  });
  return found;
}

/** The sources of the Console under [root] (tests excluded), as paths with `/`. */
export function listOwnSources(root: string): string[] {
  const files: string[] = [];
  const walk = (directory: string) => {
    for (const name of readdirSync(directory)) {
      const path = join(directory, name);
      if (statSync(path).isDirectory()) walk(path);
      else if (/\.(svelte|ts|js)$/.test(name) && !/\.test\.ts$/.test(name)) {
        files.push(path.replaceAll('\\', '/'));
      }
    }
  };
  walk(root);
  return files.sort();
}

// What GET /api/v1/system promises (08-api.md, ADR-016), as tests that run on any server that
// claims to be the Engine: the Fake of test-support (always, in `npm test`) and a real packaged
// Engine (`npm run test:contract`). The callers are the tokens the server was started with.

import { describe, expect, test } from 'vitest';

export interface ContractCaller {
  name: string;
  role: 'developer' | 'admin';
  token: string;
}

/** `name:role:token,...` as the Engine's API_TOKENS has it. */
export function parseCallers(text: string): ContractCaller[] {
  return text.split(',').map((entry) => {
    const [name, role, token] = entry.split(':');
    if (!name || (role !== 'developer' && role !== 'admin') || !token) {
      throw new Error('a caller must be name:role:token with the role developer or admin');
    }
    return { name, role, token };
  });
}

export function describeSystemContract(
  name: string,
  baseUrl: () => string,
  callers: () => ContractCaller[],
) {
  const get = (token?: string) =>
    fetch(`${baseUrl()}/api/v1/system`, {
      headers: token === undefined ? {} : { Authorization: `Bearer ${token}` },
    });
  const caller = (role: ContractCaller['role']) => callers().find((c) => c.role === role)!;

  describe(`GET /api/v1/system of ${name}`, () => {
    test('answers 401 to a request without a token', async () => {
      expect((await get()).status).toBe(401);
    });

    test('answers 401 to a token it does not know, and says Bearer', async () => {
      const response = await get('not-a-token-of-this-engine');
      expect(response.status).toBe(401);
      expect(response.headers.get('www-authenticate')).toMatch(/^Bearer/i);
    });

    test.each(['developer', 'admin'] as const)(
      'answers a %s token with who it is and the Engine details, not cached',
      async (role) => {
        const who = caller(role);
        const response = await get(who.token);
        expect(response.status).toBe(200);
        expect(response.headers.get('content-type')).toMatch(/^application\/json/);
        expect(response.headers.get('cache-control')).toContain('no-store');

        const body = await response.json();
        expect(body.caller).toEqual({ name: who.name, role: who.role });
        expect(typeof body.version).toBe('string');
        expect(body.commitHash).toMatch(/^([0-9a-f]{40}|unknown)$/);
        expect(typeof body.dirty).toBe('boolean');
        expect(Number.isNaN(Date.parse(body.buildTime))).toBe(false);
        expect(typeof body.jdk).toBe('string');
        expect(Number.isNaN(Date.parse(body.startedAt))).toBe(false);
        expect(Number.isInteger(body.uptimeSeconds)).toBe(true);
        expect(typeof body.allowListVersion).toBe('string');
      },
    );

    test('does not hold the token it was given', async () => {
      const who = caller('developer');
      expect(await (await get(who.token)).text()).not.toContain(who.token);
    });
  });
}

// What the Console relies on of the sign-in endpoints (08-api.md, "登入（OIDC）", oidc-login/05-ipc.md),
// as tests that run on any server that claims to be the Engine: the Fake of test-support (always,
// in `npm test`) and a real Engine with a real OpenID Connect provider (e2e/oidc.e2e.ts, which signs
// in with a real browser and hands the cookie over). A Fake that fails here does not stand for the
// Engine.

import { describe, expect, test } from 'vitest';

/** The question which ways of signing in there are, which every Engine answers. */
export function describeAuthMethodsContract(
  name: string,
  options: { baseUrl: () => string; oidcEnabled: boolean },
) {
  const { baseUrl, oidcEnabled } = options;
  describe(`GET /api/v1/auth/methods of ${name}`, () => {
    test('answers 200 JSON without a credential, not cached, and says whether OIDC is on', async () => {
      const response = await fetch(`${baseUrl()}/api/v1/auth/methods`);
      expect(response.status).toBe(200);
      expect(response.headers.get('content-type')).toMatch(/^application\/json/);
      expect(response.headers.get('cache-control')).toContain('no-store');
      const body = await response.json();
      expect(body.oidc.enabled).toBe(oidcEnabled);
    });

    if (!oidcEnabled) {
      test.each([
        ['GET', '/api/v1/auth/oidc/login'],
        ['GET', '/api/v1/auth/session'],
        ['POST', '/api/v1/auth/logout'],
      ])('%s %s is a 404 when OIDC is off', async (method, path) => {
        const response = await fetch(`${baseUrl()}${path}`, { method, redirect: 'manual' });
        expect(response.status).toBe(404);
      });
    }
  });
}

export interface SignedInSession {
  /** The value of the cookie `runline_session` that the sign-in set. */
  cookie: string;
  name: string;
  role: 'developer' | 'admin';
}

/** The session endpoints of an Engine that has OIDC on; [signIn] makes a person with a session. */
export function describeOidcSessionContract(
  name: string,
  options: {
    baseUrl: () => string;
    /** The address people use for the Engine: what `Origin` is when the Console asks. */
    origin: () => string;
    signIn: () => Promise<SignedInSession>;
  },
) {
  const { baseUrl, origin, signIn } = options;
  const url = (path: string) => `${baseUrl()}${path}`;
  const withCookie = (cookie: string, headers: Record<string, string> = {}) => ({
    ...headers,
    Cookie: `runline_session=${cookie}`,
  });

  describe(`the sign-in endpoints of ${name}`, () => {
    test('the login start sends the browser on to the provider (302), not cached', async () => {
      const response = await fetch(url('/api/v1/auth/oidc/login?return=/runs'), {
        redirect: 'manual',
      });
      expect(response.status).toBe(302);
      expect(response.headers.get('location')).toMatch(/^https?:\/\//);
      expect(response.headers.get('cache-control')).toContain('no-store');
    });

    test('a session query without a cookie, or with one that is not known, is a 401', async () => {
      expect((await fetch(url('/api/v1/auth/session'))).status).toBe(401);
      const unknown = await fetch(url('/api/v1/auth/session'), {
        headers: withCookie('not-a-session-of-this-engine'),
      });
      expect(unknown.status).toBe(401);
      expect((await unknown.json()).error).toBe('unauthorized');
    });

    test('a session query says who it is, with the CSRF token of the session, not cached', async () => {
      const who = await signIn();
      const response = await fetch(url('/api/v1/auth/session'), { headers: withCookie(who.cookie) });
      expect(response.status).toBe(200);
      expect(response.headers.get('cache-control')).toContain('no-store');
      const body = await response.json();
      expect(body.name).toBe(who.name);
      expect(body.role).toBe(who.role);
      expect(typeof body.displayName).toBe('string');
      expect(typeof body.csrfToken).toBe('string');
      expect(body.csrfToken).not.toBe('');
      expect(body.csrfToken).not.toBe(who.cookie);
    });

    test('the API takes the cookie for a credential: the same name and role', async () => {
      const who = await signIn();
      const response = await fetch(url('/api/v1/system'), { headers: withCookie(who.cookie) });
      expect(response.status).toBe(200);
      expect((await response.json()).caller).toEqual({
        name: who.name,
        role: who.role,
        source: 'oidc',
      });
    });

    test('a logout needs the Origin of the site, and the CSRF token of the session', async () => {
      const who = await signIn();
      const token = (
        await (await fetch(url('/api/v1/auth/session'), { headers: withCookie(who.cookie) })).json()
      ).csrfToken as string;

      const noOrigin = await fetch(url('/api/v1/auth/logout'), {
        method: 'POST',
        headers: withCookie(who.cookie, { 'X-CSRF-Token': token }),
      });
      expect(noOrigin.status).toBe(403);
      expect((await noOrigin.json()).error).toBe('origin_invalid');

      const noToken = await fetch(url('/api/v1/auth/logout'), {
        method: 'POST',
        headers: withCookie(who.cookie, { Origin: origin() }),
      });
      expect(noToken.status).toBe(403);
      expect((await noToken.json()).error).toBe('csrf_invalid');

      const stillIn = await fetch(url('/api/v1/auth/session'), { headers: withCookie(who.cookie) });
      expect(stillIn.status).toBe(200);
    });

    test('a logout ends the session and clears the cookie; the session is a 401 from then on', async () => {
      const who = await signIn();
      const token = (
        await (await fetch(url('/api/v1/auth/session'), { headers: withCookie(who.cookie) })).json()
      ).csrfToken as string;
      const response = await fetch(url('/api/v1/auth/logout'), {
        method: 'POST',
        headers: withCookie(who.cookie, { Origin: origin(), 'X-CSRF-Token': token }),
      });
      expect(response.status).toBe(204);
      expect(response.headers.get('set-cookie')).toMatch(/runline_session=;?.*Max-Age=0/i);
      const after = await fetch(url('/api/v1/auth/session'), { headers: withCookie(who.cookie) });
      expect(after.status).toBe(401);
    });

    test('a logout with no session is a 204 as well', async () => {
      const response = await fetch(url('/api/v1/auth/logout'), {
        method: 'POST',
        headers: { Origin: origin() },
      });
      expect(response.status).toBe(204);
    });

    test('a change of the API by the session needs the same: 403 without the token', async () => {
      const who = await signIn();
      const response = await fetch(url('/api/v1/runs'), {
        method: 'POST',
        headers: withCookie(who.cookie, { Origin: origin(), 'Content-Type': 'application/json' }),
        body: '{}',
      });
      expect(response.status).toBe(403);
      expect((await response.json()).error).toBe('csrf_invalid');
    });
  });
}

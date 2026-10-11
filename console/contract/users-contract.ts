// What the Console relies on of the user administration (08-api.md, "使用者與工作階段管理（管理員）",
// oidc-login/05-ipc.md), as tests that run on any server that claims to be the Engine and has login
// with a provider on: the Fake of test-support (always, in `npm test`) and a real Engine with a real
// provider (e2e/oidc.e2e.ts, which signs people in with a real browser). A Fake that fails here does
// not stand for the Engine. Every person is made new for a test, so the tests can run again.

import { describe, expect, test } from 'vitest';
import type { SignedInSession } from './auth-contract';
import type { ContractCaller } from './system-contract';

export interface UsersContractSetup {
  baseUrl: () => string;
  /** The address people use for the Engine: what `Origin` is when the Console asks. */
  origin: () => string;
  /** The tokens the Engine was started with (an admin and a developer at least). */
  callers: () => ContractCaller[];
  /** A person who signs in with the provider and gets the role; they have one valid session. */
  signIn: (role: 'developer' | 'admin') => Promise<SignedInSession>;
}

const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;

export function describeUsersContract(name: string, setup: UsersContractSetup) {
  const url = (path: string) => `${setup.baseUrl()}${path}`;
  const root = () => setup.callers().find((c) => c.role === 'admin')!;
  const developerToken = () => setup.callers().find((c) => c.role === 'developer')!;
  const asRoot = (headers: Record<string, string> = {}) => ({
    ...headers,
    Authorization: `Bearer ${root().token}`,
  });
  const ask = async (method: string, path: string, headers: Record<string, string> = asRoot()) => {
    const response = await fetch(url(path), { method, headers });
    const text = await response.text();
    return { status: response.status, headers: response.headers, body: text === '' ? null : JSON.parse(text) };
  };
  const q = encodeURIComponent;
  const sessionStatus = async (who: SignedInSession) =>
    (await fetch(url('/api/v1/auth/session'), { headers: { Cookie: `runline_session=${who.cookie}` } })).status;

  describe(`the user administration of ${name}`, () => {
    test('a request without a credential is a 401, a developer is a 403 whoever the name is', async () => {
      expect((await ask('GET', '/api/v1/users', {})).status).toBe(401);
      const developer = { Authorization: `Bearer ${developerToken().token}` };
      const person = await setup.signIn('developer');
      for (const path of ['/api/v1/users', `/api/v1/users/${q(person.name)}`, '/api/v1/users/nobody-at-all']) {
        const answer = await ask('GET', path, developer);
        expect(answer.status).toBe(403);
        expect(answer.body.error).toBe('forbidden');
      }
      expect((await ask('POST', `/api/v1/users/${q(person.name)}/disable`, developer)).status).toBe(403);
    });

    test('lists who signed in, by name, with the fields of the user object', async () => {
      const person = await setup.signIn('developer');
      const answer = await ask('GET', '/api/v1/users');
      expect(answer.status).toBe(200);
      const names = answer.body.users.map((u: { name: string }) => u.name);
      expect(names).toContain(person.name);
      expect(names).toEqual([...names].sort());
      const user = answer.body.users.find((u: { name: string }) => u.name === person.name);
      expect(user).toMatchObject({ name: person.name, disabled: false, statusChangedBy: null, statusChangedAt: null });
      expect(typeof user.displayName).toBe('string');
      expect(user.createdAt).toMatch(ISO);
      expect(user.lastLoginAt).toMatch(ISO);
      expect(user.sessions).toBeGreaterThanOrEqual(1);
      expect(JSON.stringify(answer.body)).not.toContain(person.cookie);
    });

    test('an unknown name is a 404 user_not_found, in every call that names one', async () => {
      for (const [method, path] of [
        ['GET', '/api/v1/users/nobody-at-all'],
        ['POST', '/api/v1/users/nobody-at-all/disable'],
        ['POST', '/api/v1/users/nobody-at-all/enable'],
        ['GET', '/api/v1/users/nobody-at-all/sessions'],
        ['DELETE', '/api/v1/users/nobody-at-all/sessions'],
      ]) {
        const answer = await ask(method, path);
        expect([method, path, answer.status, answer.body.error]).toEqual([method, path, 404, 'user_not_found']);
      }
    });

    test('the name of an API token is a 409 not_an_oidc_user', async () => {
      const answer = await ask('POST', `/api/v1/users/${q(developerToken().name)}/disable`);
      expect(answer.status).toBe(409);
      expect(answer.body.error).toBe('not_an_oidc_user');
    });

    test('disabling ends every session of the person, says how many, and records who did it', async () => {
      const person = await setup.signIn('developer');
      expect(await sessionStatus(person)).toBe(200);
      const answer = await ask('POST', `/api/v1/users/${q(person.name)}/disable`);
      expect(answer.status).toBe(200);
      expect(answer.body).toMatchObject({
        name: person.name,
        disabled: true,
        statusChangedBy: root().name,
        sessions: 0,
      });
      expect(answer.body.sessionsEnded).toBeGreaterThanOrEqual(1);
      expect(answer.body.statusChangedAt).toMatch(ISO);
      expect(await sessionStatus(person)).toBe(401);
      const read = await ask('GET', `/api/v1/users/${q(person.name)}`);
      expect(read.body.disabled).toBe(true);
    });

    test('enabling lifts it, and does not bring the sessions back', async () => {
      const person = await setup.signIn('developer');
      await ask('POST', `/api/v1/users/${q(person.name)}/disable`);
      const answer = await ask('POST', `/api/v1/users/${q(person.name)}/enable`);
      expect(answer.status).toBe(200);
      expect(answer.body).toMatchObject({ name: person.name, disabled: false, sessions: 0 });
      expect(await sessionStatus(person)).toBe(401);
    });

    test('lists the sessions by a handle that is not the session, and ends one of them', async () => {
      const person = await setup.signIn('developer');
      const listed = await ask('GET', `/api/v1/users/${q(person.name)}/sessions`);
      expect(listed.status).toBe(200);
      expect(listed.body.sessions).toHaveLength(1);
      const [session] = listed.body.sessions;
      expect(session.createdAt).toMatch(ISO);
      expect(session.lastUsedAt).toMatch(ISO);
      expect(JSON.stringify(listed.body)).not.toContain(person.cookie);
      expect(session.id).not.toBe(person.cookie);

      const ended = await ask('DELETE', `/api/v1/users/${q(person.name)}/sessions/${q(session.id)}`);
      expect(ended.status).toBe(204);
      expect(await sessionStatus(person)).toBe(401);
      const again = await ask('DELETE', `/api/v1/users/${q(person.name)}/sessions/${q(session.id)}`);
      expect([again.status, again.body.error]).toEqual([404, 'session_not_found']);
      expect((await ask('GET', `/api/v1/users/${q(person.name)}/sessions`)).body.sessions).toEqual([]);
    });

    test('ends all the sessions at once and says how many; the person is not disabled by it', async () => {
      const person = await setup.signIn('developer');
      const answer = await ask('DELETE', `/api/v1/users/${q(person.name)}/sessions`);
      expect(answer.status).toBe(200);
      expect(answer.body.ended).toBeGreaterThanOrEqual(1);
      expect(await sessionStatus(person)).toBe(401);
      expect((await ask('GET', `/api/v1/users/${q(person.name)}`)).body.disabled).toBe(false);
    });

    test('an admin by session passes the check of the Origin and the CSRF token to change something', async () => {
      const admin = await setup.signIn('admin');
      const person = await setup.signIn('developer');
      const cookie = { Cookie: `runline_session=${admin.cookie}` };
      const token = (await (await fetch(url('/api/v1/auth/session'), { headers: cookie })).json()).csrfToken as string;

      expect((await ask('GET', '/api/v1/users', cookie)).status).toBe(200);
      const path = `/api/v1/users/${q(person.name)}/disable`;
      const noToken = await ask('POST', path, { ...cookie, Origin: setup.origin() });
      expect([noToken.status, noToken.body.error]).toEqual([403, 'csrf_invalid']);
      const noOrigin = await ask('POST', path, { ...cookie, 'X-CSRF-Token': token });
      expect([noOrigin.status, noOrigin.body.error]).toEqual([403, 'origin_invalid']);
      expect(await sessionStatus(person)).toBe(200);

      const done = await ask('POST', path, { ...cookie, Origin: setup.origin(), 'X-CSRF-Token': token });
      expect(done.status).toBe(200);
      expect(await sessionStatus(person)).toBe(401);
    });
  });
}

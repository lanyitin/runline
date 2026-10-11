import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ApiFailure } from './failure';

// The user administration (08-api.md, "使用者與工作階段管理（管理員）") as the Console reads and
// sends it, held against the Fake Engine (which the contract tests hold to the real one).

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const ada = { name: 'ada', role: 'developer' as const };
const start = async (identity: { name: string; role: 'admin' | 'developer' } = root) => {
  app = await createTestApp({ identity, oidc: {} });
  return app.context.api;
};

describe('the users', () => {
  test('are read with their names, display names, state, last sign-in and number of sessions', async () => {
    const api = await start();
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });
    app.engine.oidc!.signIn({ name: 'dana', role: 'admin' });

    const users = await api.users();

    expect(users.map((u) => u.name)).toEqual(['dana', 'eli']);
    expect(users[1]).toMatchObject({
      name: 'eli',
      displayName: 'First eli',
      disabled: false,
      statusChangedBy: null,
      statusChangedAt: null,
      sessions: 2,
    });
    expect(users[1].lastLoginAt).toMatch(/^\d{4}-\d\d-\d\dT/);
  });

  test('are refused to a developer: a 403 that is an ApiFailure with the code of the Engine', async () => {
    const api = await start(ada);

    const failure = await api.users().catch((e) => e);

    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure).toMatchObject({ status: 403, body: { error: 'forbidden' } });
  });

  test('are disabled and enabled by name, a name with a dot or a slash going into the address as one piece', async () => {
    const api = await start();
    app.engine.oidc!.signIn({ name: 'a.b/c@x', role: 'developer' });

    const disabled = await api.disableUser('a.b/c@x');
    expect(disabled).toMatchObject({ name: 'a.b/c@x', disabled: true, statusChangedBy: 'root', sessionsEnded: 1, sessions: 0 });

    const enabled = await api.enableUser('a.b/c@x');
    expect(enabled).toMatchObject({ disabled: false, sessionsEnded: null });
  });

  test('say 404 user_not_found for a name that is none and 409 not_an_oidc_user for a token', async () => {
    const api = await start();

    expect(await api.disableUser('nobody').catch((e) => e)).toMatchObject({ status: 404, body: { error: 'user_not_found' } });
    expect(await api.disableUser('root').catch((e) => e)).toMatchObject({ status: 409, body: { error: 'not_an_oidc_user' } });
  });
});

describe('the sessions of a user', () => {
  test('are listed by handle with when they began and were last used, and ended one by one', async () => {
    const api = await start();
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });

    const sessions = await api.userSessions('eli');
    expect(sessions).toHaveLength(2);
    expect(sessions[0]).toEqual({ id: expect.any(String), createdAt: expect.any(String), lastUsedAt: expect.any(String) });

    await api.endUserSession('eli', sessions[0].id);
    expect((await api.userSessions('eli')).map((s) => s.id)).toEqual([sessions[1].id]);
    expect(await api.endUserSession('eli', sessions[0].id).catch((e) => e)).toMatchObject({
      status: 404,
      body: { error: 'session_not_found' },
    });
  });

  test('are all ended at once, and how many ended is told', async () => {
    const api = await start();
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });

    expect(await api.endUserSessions('eli')).toBe(2);
    expect(await api.userSessions('eli')).toEqual([]);
  });
});

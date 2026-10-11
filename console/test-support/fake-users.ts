// The user administration of the Fake Engine (08-api.md, "使用者與工作階段管理（管理員）"): the
// people who signed in with the provider and their sessions, for admins. The people and the
// sessions are the Fake provider login's (fake-oidc.ts); this is the API on top of them. The
// contract tests (contract/users-contract.ts) hold it to the Engine.

import { answer, failure, type ApiAnswer, type FakeRoute } from './fake-api';
import type { FakeOidc, FakeUser } from './fake-oidc';

const userObject = (user: FakeUser, oidc: FakeOidc, ended?: number) => ({
  ...user,
  sessions: oidc.sessionsOf(user.name).length,
  ...(ended === undefined ? {} : { sessionsEnded: ended }),
});

const notFound = () => failure(404, 'user_not_found', 'No such user.');

export class FakeUsers {
  /** [tokenNames]: the names of the API tokens, which are not people who signed in. */
  constructor(
    private readonly oidc: FakeOidc,
    private readonly tokenNames: () => string[],
  ) {}

  private change(name: string, disabled: boolean, by: string): ApiAnswer {
    const user = this.oidc.user(name);
    if (!user) {
      return this.tokenNames().includes(name)
        ? failure(409, 'not_an_oidc_user', 'This name belongs to an API token.')
        : notFound();
    }
    const ended = this.oidc.setDisabled(name, disabled, by);
    return answer(200, userObject(user, this.oidc, disabled ? ended : undefined));
  }

  /** Looks the name up for the calls that need a person; a token name is no one either. */
  private known(name: string): FakeUser | undefined {
    return this.oidc.user(name);
  }

  readonly routes: FakeRoute[] = [
    {
      method: 'GET',
      pattern: /^\/api\/v1\/users$/,
      admin: true,
      handle: () => answer(200, { users: this.oidc.users().map((u) => userObject(u, this.oidc)) }),
    },
    {
      method: 'GET',
      pattern: /^\/api\/v1\/users\/([^/]+)$/,
      admin: true,
      handle: (_, m) => {
        const user = this.known(decodeURIComponent(m[1]));
        return user ? answer(200, userObject(user, this.oidc)) : notFound();
      },
    },
    {
      method: 'POST',
      pattern: /^\/api\/v1\/users\/([^/]+)\/disable$/,
      admin: true,
      handle: (request, m) => this.change(decodeURIComponent(m[1]), true, request.caller.name),
    },
    {
      method: 'POST',
      pattern: /^\/api\/v1\/users\/([^/]+)\/enable$/,
      admin: true,
      handle: (request, m) => this.change(decodeURIComponent(m[1]), false, request.caller.name),
    },
    {
      method: 'GET',
      pattern: /^\/api\/v1\/users\/([^/]+)\/sessions$/,
      admin: true,
      handle: (_, m) => {
        const name = decodeURIComponent(m[1]);
        return this.known(name) ? answer(200, { sessions: this.oidc.sessionsOf(name) }) : notFound();
      },
    },
    {
      method: 'DELETE',
      pattern: /^\/api\/v1\/users\/([^/]+)\/sessions$/,
      admin: true,
      handle: (_, m) => {
        const name = decodeURIComponent(m[1]);
        return this.known(name) ? answer(200, { ended: this.oidc.endSessions(name) }) : notFound();
      },
    },
    {
      method: 'DELETE',
      pattern: /^\/api\/v1\/users\/([^/]+)\/sessions\/([^/]+)$/,
      admin: true,
      handle: (_, m) => {
        const name = decodeURIComponent(m[1]);
        if (!this.known(name)) return notFound();
        return this.oidc.endSessions(name, decodeURIComponent(m[2])) > 0
          ? answer(204)
          : failure(404, 'session_not_found', 'No such session.');
      },
    },
  ];

  /** Whether the path is one of this API (for the 401 an unauthenticated request gets). */
  handles(method: string, path: string): boolean {
    return this.routes.some((r) => r.method === method && r.pattern.test(path));
  }

  handle(method: string, path: string, request: Parameters<FakeRoute['handle']>[0]): ApiAnswer | null {
    for (const route of this.routes) {
      const found = route.method === method ? path.match(route.pattern) : null;
      if (!found) continue;
      if (route.admin && request.caller.role !== 'admin') {
        return failure(403, 'forbidden', 'This needs the role ADMIN.');
      }
      return route.handle(request, found);
    }
    return null;
  }
}

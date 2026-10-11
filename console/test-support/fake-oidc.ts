// The part of the Engine that lets a person sign in with an OpenID Connect provider (08-api.md,
// "登入（OIDC）"), as far as the Console meets it: the question which ways of signing in there are,
// the start of a login, the session query, the logout, and the session cookie as a credential of
// the API. What happens between the start and the cookie (the provider, the code, the callback) is
// not here: a test makes the person a session with `signIn`, which stands for a login that went
// well. Its behaviour is held to the Engine's by contract/auth-contract.ts.

import { randomBytes, timingSafeEqual } from 'node:crypto';

export interface FakeOidcOptions {
  /** Said by the question which ways of signing in there are, as the name of the button. */
  label?: string;
}

export interface FakePerson {
  name: string;
  role: 'developer' | 'admin';
}

export const SESSION_COOKIE = 'runline_session';

interface FakeSession extends FakePerson {
  csrfToken: string;
  /** The handle of the session for the administration: not the cookie. */
  ref: string;
  createdAt: string;
  lastUsedAt: string;
}

/** A person who signed in at least once, as the user administration knows them. */
export interface FakeUser {
  name: string;
  displayName: string;
  disabled: boolean;
  statusChangedBy: string | null;
  statusChangedAt: string | null;
  createdAt: string;
  lastLoginAt: string;
}

const now = () => new Date().toISOString().replace(/\.\d+Z$/, 'Z');

export interface OidcAnswer {
  status: number;
  json?: unknown;
  headers?: Record<string, string>;
}

const token = () => randomBytes(24).toString('base64url');
const same = (a: string, b: string) =>
  a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));

export class FakeOidc {
  private readonly sessions = new Map<string, FakeSession>();
  private readonly known = new Map<string, FakeUser>();
  /** The sign-ins that were started: the `return` each asked for, in order. */
  readonly started: string[] = [];

  constructor(
    private readonly origin: () => string,
    private readonly options: FakeOidcOptions = {},
  ) {}

  /** A login that went well: the value of the cookie the callback would have set. */
  signIn(person: FakePerson): string {
    const id = token();
    const at = now();
    this.sessions.set(id, { ...person, csrfToken: token(), ref: token(), createdAt: at, lastUsedAt: at });
    const user = this.known.get(person.name);
    if (user) user.lastLoginAt = at;
    else {
      this.known.set(person.name, {
        name: person.name,
        displayName: `First ${person.name}`,
        disabled: false,
        statusChangedBy: null,
        statusChangedAt: null,
        createdAt: at,
        lastLoginAt: at,
      });
    }
    return id;
  }

  /** The people who signed in, by name. */
  users(): FakeUser[] {
    return [...this.known.values()].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  }

  user(name: string): FakeUser | undefined {
    return this.known.get(name);
  }

  /** The valid sessions of a person, as the administration lists them. */
  sessionsOf(name: string): Array<{ id: string; createdAt: string; lastUsedAt: string }> {
    return [...this.sessions.values()]
      .filter((s) => s.name === name)
      .map((s) => ({ id: s.ref, createdAt: s.createdAt, lastUsedAt: s.lastUsedAt }));
  }

  /** Ends the sessions of a person, or only the one with the handle [ref]; how many ended. */
  endSessions(name: string, ref?: string): number {
    let ended = 0;
    for (const [id, session] of this.sessions) {
      if (session.name === name && (ref === undefined || session.ref === ref)) {
        this.sessions.delete(id);
        ended += 1;
      }
    }
    return ended;
  }

  /** Disables or enables a person; disabling ends their sessions (how many is the answer). */
  setDisabled(name: string, disabled: boolean, by: string): number {
    const user = this.known.get(name)!;
    user.disabled = disabled;
    user.statusChangedBy = by;
    user.statusChangedAt = now();
    return disabled ? this.endSessions(name) : 0;
  }

  /** The provider took the person's access away: the session is gone. */
  end(id: string): void {
    this.sessions.delete(id);
  }

  has(id: string): boolean {
    return this.sessions.has(id);
  }

  methods(): OidcAnswer {
    const oidc = { enabled: true, ...(this.options.label ? { displayName: this.options.label } : {}) };
    return { status: 200, json: { oidc }, headers: { 'Cache-Control': 'no-store' } };
  }

  /** Who a cookie belongs to; null for one that is not known. */
  find(cookieHeader: string | undefined): (FakePerson & { id: string }) | null {
    const id = cookieHeader
      ?.split(';')
      .map((part) => part.trim())
      .find((part) => part.startsWith(`${SESSION_COOKIE}=`))
      ?.slice(SESSION_COOKIE.length + 1);
    const session = id === undefined ? undefined : this.sessions.get(id);
    if (session) session.lastUsedAt = now();
    return id === undefined || session === undefined
      ? null
      : { id, name: session.name, role: session.role };
  }

  /**
   * The check that a change by a session must pass: the Origin of the site and the CSRF token of
   * the session. The code of what is wrong, or null.
   */
  refusal(
    method: string,
    cookieHeader: string | undefined,
    origin: string | undefined,
    presented: string | undefined,
  ): 'origin_invalid' | 'csrf_invalid' | null {
    if (['GET', 'HEAD', 'OPTIONS'].includes(method)) return null;
    const who = this.find(cookieHeader);
    if (!who) return null;
    if (origin !== this.origin()) return 'origin_invalid';
    return presented !== undefined && same(presented, this.sessions.get(who.id)!.csrfToken)
      ? null
      : 'csrf_invalid';
  }

  /** The endpoints under /api/v1/auth/ other than the question of the methods. */
  handle(
    method: string,
    path: string,
    query: URLSearchParams,
    cookieHeader: string | undefined,
    origin: string | undefined,
    csrf: string | undefined,
  ): OidcAnswer | null {
    const noStore = { 'Cache-Control': 'no-store' };
    if (method === 'GET' && path === '/api/v1/auth/oidc/login') {
      this.started.push(query.get('return') ?? '/');
      return {
        status: 302,
        headers: { ...noStore, Location: 'https://provider.invalid/authorize?state=fake' },
      };
    }
    if (method === 'GET' && path === '/api/v1/auth/session') {
      const who = this.find(cookieHeader);
      if (!who) {
        return {
          status: 401,
          json: { error: 'unauthorized', message: 'Not signed in.' },
          headers: noStore,
        };
      }
      const session = this.sessions.get(who.id)!;
      return {
        status: 200,
        json: {
          name: session.name,
          role: session.role,
          displayName: this.known.get(session.name)!.displayName,
          csrfToken: session.csrfToken,
        },
        headers: noStore,
      };
    }
    if (method === 'POST' && path === '/api/v1/auth/logout') {
      const refused = this.refusal(method, cookieHeader, origin, csrf);
      if (refused) return { status: 403, json: { error: refused, message: 'Refused.' }, headers: noStore };
      const who = this.find(cookieHeader);
      if (who) this.sessions.delete(who.id);
      return {
        status: 204,
        headers: {
          ...noStore,
          'Set-Cookie': `${SESSION_COOKIE}=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax`,
        },
      };
    }
    return null;
  }
}

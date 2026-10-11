// Signing in with an OpenID Connect provider (ADR-029), as far as the Console knows it: the Engine
// does the whole login (the provider, the code, the tokens) and keeps the session; the Console
// sends the browser to the Engine's login start and later asks the Engine who the session is. These
// are the pure parts of that: reading the Engine's answers, and the address of the login start.

import type { Identity, Role } from '../app/identity.svelte.ts';

export const METHODS_PATH = '/api/v1/auth/methods';
export const SESSION_PATH = '/api/v1/auth/session';
export const LOGOUT_PATH = '/api/v1/auth/logout';
const LOGIN_PATH = '/api/v1/auth/oidc/login';

/** What the Engine says of the way to sign in with a provider. */
export interface OidcMethod {
  /** The name of the button, if the Engine gives one; the Console has its own words otherwise. */
  label: string | null;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

/** The provider's way to sign in if `GET /api/v1/auth/methods` says it is on; null if it is off. */
export function parseOidcMethod(body: unknown): OidcMethod | null {
  if (!isRecord(body) || !isRecord(body.oidc) || body.oidc.enabled !== true) return null;
  const { displayName } = body.oidc;
  return { label: typeof displayName === 'string' && displayName.trim() !== '' ? displayName : null };
}

export interface OidcSession {
  identity: Identity;
  /** The token that a change by this session must carry in `X-CSRF-Token`. */
  csrfToken: string;
}

/** The session `GET /api/v1/auth/session` describes; null for an answer that is not one. */
export function parseOidcSession(body: unknown): OidcSession | null {
  if (!isRecord(body)) return null;
  const { name, role, csrfToken } = body;
  if (typeof name !== 'string' || name === '' || typeof csrfToken !== 'string' || csrfToken === '') {
    return null;
  }
  if (role !== 'developer' && role !== 'admin') return null;
  return { identity: { name, role: role as Role }, csrfToken };
}

const MAX_TARGET_LENGTH = 2048;

/**
 * [target] if it is a path of this site that the Engine will take for a way back (a single `/`
 * first, no `//`, no backslash, no space or control character, not too long); the front page if
 * not. The Engine decides again; this is so that the Console never asks for what it knows is ignored.
 */
export function returnTarget(target: string): string {
  const ok =
    target.startsWith('/') &&
    !target.startsWith('//') &&
    target.length <= MAX_TARGET_LENGTH &&
    ![...target].some((c) => c <= ' ' || c === '\\' || c === '\u007f');
  return ok ? target : '/';
}

/** The address of the Engine's login start, which sends the browser on to the provider. */
export function loginUrl(baseUrl: string, target: string): string {
  return `${baseUrl}${LOGIN_PATH}?return=${encodeURIComponent(returnTarget(target))}`;
}

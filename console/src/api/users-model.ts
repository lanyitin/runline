// What the API of the user administration answers (08-api.md, "使用者與工作階段管理（管理員）"): the
// people who signed in with the provider, and their sessions by handle. Read here and nowhere else,
// as model.ts does for the rest. The names and display names come from the provider: untrusted text.

import { bad, bool, list, num, obj, str, strOrNull } from './parse.ts';

export interface User {
  name: string;
  displayName: string;
  disabled: boolean;
  /** Who disabled or enabled the person last; null if nobody did. */
  statusChangedBy: string | null;
  statusChangedAt: string | null;
  createdAt: string;
  lastLoginAt: string;
  /** The sessions that are valid now. */
  sessions: number;
  /** Only in the answer to disabling: the sessions that went with it. */
  sessionsEnded: number | null;
}

/** A session by its handle: the handle is no credential and cannot sign in. */
export interface UserSession {
  id: string;
  createdAt: string;
  lastUsedAt: string;
}

export function parseUser(value: unknown): User {
  const o = obj(value, 'a user');
  return {
    name: str(o.name, 'user.name'),
    displayName: str(o.displayName, 'user.displayName'),
    disabled: bool(o.disabled, 'user.disabled'),
    statusChangedBy: strOrNull(o.statusChangedBy, 'user.statusChangedBy'),
    statusChangedAt: strOrNull(o.statusChangedAt, 'user.statusChangedAt'),
    createdAt: str(o.createdAt, 'user.createdAt'),
    lastLoginAt: str(o.lastLoginAt, 'user.lastLoginAt'),
    sessions: num(o.sessions, 'user.sessions'),
    sessionsEnded: o.sessionsEnded === undefined || o.sessionsEnded === null ? null : num(o.sessionsEnded, 'user.sessionsEnded'),
  };
}

export function parseUsers(value: unknown): User[] {
  const o = obj(value, 'the users');
  if (!Array.isArray(o.users)) throw bad('users');
  return list(o.users, 'users', parseUser);
}

export function parseUserSessions(value: unknown): UserSession[] {
  const o = obj(value, 'the sessions of a user');
  if (!Array.isArray(o.sessions)) throw bad('sessions');
  return list(o.sessions, 'sessions', (item) => {
    const s = obj(item, 'a session');
    return {
      id: str(s.id, 'session.id'),
      createdAt: str(s.createdAt, 'session.createdAt'),
      lastUsedAt: str(s.lastUsedAt, 'session.lastUsedAt'),
    };
  });
}

/** How many sessions `DELETE .../sessions` ended. */
export function parseEnded(value: unknown): number {
  return num(obj(value, 'the answer to ending sessions').ended, 'ended');
}

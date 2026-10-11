// The calls of the user administration (08-api.md, "使用者與工作階段管理（管理員）"), for admins:
// the people who signed in with the provider, disabling and enabling them, and their sessions. They
// go through the session as every call does (a change by a session carries its CSRF token there). A
// name goes into the address as one piece: a name from the provider may have a dot or an `@`.

import { jsonCall, succeed, type Transport } from './call.ts';
import {
  parseEnded,
  parseUser,
  parseUsers,
  parseUserSessions,
  type User,
  type UserSession,
} from './users-model.ts';

const q = encodeURIComponent;

export function createUsersApi(transport: Transport) {
  const json = jsonCall(transport);
  const userPath = (name: string) => `/api/v1/users/${q(name)}`;

  return {
    async users(): Promise<User[]> {
      return parseUsers(await succeed(await json('/api/v1/users')));
    },
    /** Ends every session of the person and refuses their sign-in until enabled. */
    async disableUser(name: string): Promise<User> {
      return parseUser(await succeed(await json(`${userPath(name)}/disable`, { method: 'POST' })));
    },
    /** Lets the person sign in again; the sessions that ended do not come back. */
    async enableUser(name: string): Promise<User> {
      return parseUser(await succeed(await json(`${userPath(name)}/enable`, { method: 'POST' })));
    },
    async userSessions(name: string): Promise<UserSession[]> {
      return parseUserSessions(await succeed(await json(`${userPath(name)}/sessions`)));
    },
    async endUserSession(name: string, id: string): Promise<void> {
      await succeed(await json(`${userPath(name)}/sessions/${q(id)}`, { method: 'DELETE' }));
    },
    /** How many sessions were ended. */
    async endUserSessions(name: string): Promise<number> {
      return parseEnded(await succeed(await json(`${userPath(name)}/sessions`, { method: 'DELETE' })));
    },
  };
}

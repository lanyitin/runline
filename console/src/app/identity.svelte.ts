// Who is using the Console, as the screens and the routes need to know it: a name and a role, or
// nobody yet. Where that comes from is not their business (ADR-017: the authentication boundary,
// src/auth/); the screens only read `state`, never a credential.

export type Role = 'developer' | 'admin';

export interface Identity {
  name: string;
  role: Role;
}

/** Why nobody is signed in, when it is worth telling the person at the sign-in screen. */
export type AnonymousReason =
  /** The person signed out here or in another tab. */
  | 'signed-out'
  /** The Engine refused the session's credential (401): it is no longer valid. */
  | 'expired'
  /** The Engine could not be asked whether the session still holds: nothing was thrown away. */
  | 'unavailable';

export type IdentityState =
  /** The tab is finding out whether there is a session (its own, or another tab's). */
  | { status: 'restoring' }
  | { status: 'anonymous'; reason?: AnonymousReason }
  | { status: 'authenticated'; identity: Identity };

/** What the shell reads. `state` is reactive: when it changes, what depends on it is redrawn. */
export interface IdentitySource {
  readonly state: IdentityState;
}

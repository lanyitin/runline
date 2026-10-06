// The authentication boundary's one extension point (ADR-017): the way the Console signs in. The
// rest of the Console does not know which method is in use, nor what a credential is: it is an
// opaque string that the session keeps, shares between the tabs and hands to `authorize`. Today
// there is one method, the Bearer token (token-method.ts); OIDC or user name and password would be
// another, behind the same interface.

import type { Component } from 'svelte';
import type { Identity } from '../app/identity.svelte.ts';
import type { ApiFailure } from '../api/failure.ts';

/** Sends a request to the Engine's origin as it is: no credential is added. */
export type Transport = (path: string, init?: RequestInit) => Promise<Response>;

export type SignInOutcome = { ok: true } | { ok: false; failure: ApiFailure };

/** What the sign-in screen of a method is given: the way to hand a credential over. */
export interface SignInProps {
  signIn(credential: string): Promise<SignInOutcome>;
}

export interface AuthMethod {
  /** Puts what a request needs to be authenticated by [credential] into [headers]. */
  authorize(credential: string, headers: Headers): void;
  /** Who [credential] is. Throws [ApiFailure]: status 401 when it is not valid. */
  identify(credential: string, transport: Transport): Promise<Identity>;
  /** The screen that asks for the credential. */
  SignIn: Component<SignInProps>;
}

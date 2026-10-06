// Who is using the Console, as the screens and the routes need to know it: a name and a role, or
// nobody yet. Where that comes from is not their business (ADR-017: the authentication boundary).
// WI-34 provides the source that signs in with a token; the screens only read `state`, never a
// credential.

export type Role = 'developer' | 'admin';

export interface Identity {
  name: string;
  role: Role;
}

export type IdentityState =
  | { status: 'anonymous' }
  | { status: 'authenticated'; identity: Identity };

/** What the shell reads. `state` is reactive: when it changes, what depends on it is redrawn. */
export interface IdentitySource {
  readonly state: IdentityState;
}

/** An identity source that is set from outside: nobody until [signIn], and back with [signOut]. */
export class SettableIdentity implements IdentitySource {
  state = $state<IdentityState>({ status: 'anonymous' });

  constructor(identity?: Identity) {
    if (identity) this.signIn(identity);
  }

  signIn(identity: Identity) {
    this.state = { status: 'authenticated', identity };
  }

  signOut() {
    this.state = { status: 'anonymous' };
  }
}

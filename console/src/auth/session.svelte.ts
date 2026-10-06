// The session of the Console: the authentication boundary (ADR-017). Everything about being signed
// in is here: whether the tab is, and as whom; the credential, which is kept in the tab's storage
// and shared with the other tabs; the single way a request leaves the Console with a credential; and
// what happens when the Engine refuses it. The credential is an opaque string that only the
// AuthMethod understands. Nothing outside src/auth/ reads it (tools/auth-boundary.ts, checked by
// the tests, sees to that).

import type { Component } from 'svelte';
import { ApiFailure } from '../api/failure.ts';
import type { IdentitySource, IdentityState } from '../app/identity.svelte.ts';
import { createCredentialStore, type CredentialStorage } from './credential-store.ts';
import type { AuthMethod, SignInOutcome, SignInProps } from './method.ts';
import type { TabSync } from './tab-sync.ts';

export interface SessionOptions {
  method: AuthMethod;
  /** The storage of this tab (sessionStorage). */
  storage: CredentialStorage;
  /** The channel to the other tabs; the session closes it when it is disposed. */
  sync: TabSync;
  /** The origin of the Engine; empty means the origin that served the Console (production). */
  baseUrl?: string;
  /** How long a tab without a credential waits for another tab to give it one. */
  askTimeoutMs?: number;
  fetch?: typeof fetch;
  /** Told after every request: whether the Engine answered (a status below 500) or not. */
  onOutcome?: (ok: boolean) => void;
}

/** What the screens may do with the session: who is in, sign out, and ask the Engine something. */
export interface Session extends IdentitySource {
  /** The screen that asks for the credential, of the method in use. */
  readonly SignIn: Component<SignInProps>;
  /** Finds out whether there is a session: the tab's own, or one of another tab. */
  start(): Promise<void>;
  signIn(credential: string): Promise<SignInOutcome>;
  signOut(): void;
  /**
   * The only way out to the Engine's API: [path] on the Engine's origin, with the credential of the
   * session. A 401 ends the session, here and in the other tabs, and is still given to the caller.
   * Without a session nothing is sent and the call fails as a 401.
   */
  request(path: string, init?: RequestInit): Promise<Response>;
  dispose(): void;
}

const failureOf = (error: unknown): ApiFailure =>
  error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

export function createSession(options: SessionOptions): Session {
  const { method, sync, baseUrl = '', askTimeoutMs = 500, onOutcome } = options;
  const rawFetch = options.fetch ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const store = createCredentialStore(options.storage);

  let state = $state<IdentityState>({ status: 'restoring' });
  // Moves on whenever the session changes under the feet of something that is still being checked
  // (a sign-out, a refusal, a session of another tab): what was started before must not end it.
  let epoch = 0;
  let asking: ((credential: string | null) => void) | null = null;

  const send = async (path: string, init?: RequestInit): Promise<Response> => {
    try {
      const response = await rawFetch(`${baseUrl}${path}`, init);
      onOutcome?.(response.status < 500);
      return response;
    } catch (error) {
      onOutcome?.(false);
      throw error;
    }
  };

  const wasSignedIn = () => state.status === 'authenticated';

  /** The credential is no longer valid: forget it here and say so to the other tabs. */
  function expire() {
    epoch += 1;
    store.clear();
    state = { status: 'anonymous', reason: 'expired' };
    sync.post({ type: 'expired' });
  }

  /** Takes [credential] as the session of this tab, if the Engine says who it is. */
  async function adopt(credential: string) {
    const mine = ++epoch;
    if (state.status !== 'authenticated') state = { status: 'restoring' };
    let identity;
    try {
      identity = await method.identify(credential, send);
    } catch (error) {
      if (mine !== epoch) return;
      if (failureOf(error).status === 401) expire();
      else state = { status: 'anonymous', reason: 'unavailable' };
      return;
    }
    if (mine !== epoch) return;
    store.write(credential);
    state = { status: 'authenticated', identity };
  }

  function ask(): Promise<string | null> {
    return new Promise((resolve) => {
      const timer = setTimeout(() => finish(null), askTimeoutMs);
      const finish = (credential: string | null) => {
        clearTimeout(timer);
        asking = null;
        resolve(credential);
      };
      asking = finish;
      sync.post({ type: 'ask' });
    });
  }

  const forget = (reason: 'signed-out' | 'expired') => {
    const was = wasSignedIn();
    epoch += 1;
    asking?.(null);
    store.clear();
    state = was ? { status: 'anonymous', reason } : { status: 'anonymous' };
  };

  const unsubscribe = sync.subscribe((message) => {
    switch (message.type) {
      case 'ask': {
        const credential = store.read();
        if (credential !== null && state.status === 'authenticated') {
          sync.post({ type: 'answer', credential });
        }
        return;
      }
      case 'answer':
        asking?.(message.credential);
        return;
      case 'signed-out':
        return forget('signed-out');
      case 'expired':
        return forget('expired');
      case 'signed-in':
        if (state.status === 'authenticated' && store.read() === message.credential) return;
        asking?.(null);
        void adopt(message.credential);
        return;
    }
  });

  return {
    SignIn: method.SignIn,

    get state() {
      return state;
    },

    async start() {
      state = { status: 'restoring' };
      const own = store.read();
      if (own !== null) return adopt(own);
      const before = epoch;
      const given = await ask();
      if (given !== null) return adopt(given);
      if (epoch === before && state.status === 'restoring') state = { status: 'anonymous' };
    },

    async signIn(credential) {
      let identity;
      try {
        identity = await method.identify(credential, send);
      } catch (error) {
        return { ok: false, failure: failureOf(error) };
      }
      epoch += 1;
      store.write(credential);
      state = { status: 'authenticated', identity };
      sync.post({ type: 'signed-in', credential });
      return { ok: true };
    },

    signOut() {
      forget('signed-out');
      sync.post({ type: 'signed-out' });
    },

    async request(path, init) {
      const credential = store.read();
      if (credential === null) throw new ApiFailure(401, null, 'nobody is signed in');
      const headers = new Headers(init?.headers);
      method.authorize(credential, headers);
      let response: Response;
      try {
        response = await send(path, { ...init, headers });
      } catch (error) {
        throw failureOf(error);
      }
      // Only the credential this request carried may end the session: a 401 for one that has been
      // replaced since says nothing about the new one.
      if (response.status === 401 && store.read() === credential) expire();
      return response;
    },

    dispose() {
      unsubscribe();
      asking?.(null);
      sync.close();
    },
  };
}

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
import OidcSignIn from './OidcSignIn.svelte';
import {
  loginUrl,
  LOGOUT_PATH,
  METHODS_PATH,
  parseOidcMethod,
  parseOidcSession,
  SESSION_PATH,
} from './oidc.ts';
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
  /**
   * How long the two questions of the provider's sign-in (which ways there are, and whether the
   * browser has a session) wait for the Engine. An Engine that does not answer is as one without
   * provider: the sign-in page is shown.
   */
  oidcTimeoutMs?: number;
  fetch?: typeof fetch;
  /** Told after every request: whether the Engine answered (a status below 500) or not. */
  onOutcome?: (ok: boolean) => void;
}

export interface UploadOptions {
  contentType?: string;
  signal?: AbortSignal;
  /** Called while the file goes, with the bytes sent and the bytes in all. */
  onProgress?: (sent: number, total: number) => void;
}

/** The way to sign in with a provider (ADR-029), when the Engine has it on. */
export interface OidcLogin {
  /** The name of the button as the Engine gives it; null when the Console should use its own words. */
  readonly label: string | null;
  /**
   * Where a link goes to sign in: the Engine's login start, which sends the browser on to the
   * provider and, when the login is done, back to [target] (a path of this site). The browser
   * moves to it as a page, so the Console never sees the code or a token.
   */
  loginUrl(target: string): string;
}

/** What the screens may do with the session: who is in, sign out, and ask the Engine something. */
export interface Session extends IdentitySource {
  /** The screen that asks for the credential, of the method in use. */
  readonly SignIn: Component<SignInProps>;
  /** The button that signs in with a provider, for the page to put where it asks for the credential. */
  readonly OidcSignIn: Component<{ target: string }>;
  /** The way to sign in with a provider, if the Engine has it on; null if not (or not known). */
  readonly oidc: OidcLogin | null;
  /** Finds out whether there is a session: the tab's own, or one of another tab. */
  start(): Promise<void>;
  signIn(credential: string): Promise<SignInOutcome>;
  /**
   * Ends the session. With a token that is done at once, here and in the other tabs. With a
   * provider's session the Engine is asked to end it first; if it cannot, the person stays signed in.
   */
  signOut(): Promise<void>;
  /**
   * The only way out to the Engine's API: [path] on the Engine's origin, with the credential of the
   * session. A 401 ends the session, here and in the other tabs, and is still given to the caller.
   * Without a session nothing is sent and the call fails as a 401.
   */
  request(path: string, init?: RequestInit): Promise<Response>;
  /**
   * A POST of a file to [path] with the credential of the session, which tells how much has gone and
   * can be cancelled (`fetch` can do neither for a file). The answer, the 401 and the connection are
   * as for `request`. A cancelled upload fails with an `AbortError`, as `fetch` does.
   */
  upload(path: string, body: Blob, options?: UploadOptions): Promise<Response>;
  dispose(): void;
}

/** Sends [body] with an XMLHttpRequest, the only way a browser says how much of it has gone. */
function post(
  url: string,
  body: Blob,
  headers: Headers,
  options: UploadOptions,
  withCookies: boolean,
): Promise<Response> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url);
    xhr.withCredentials = withCookies;
    headers.forEach((value, name) => xhr.setRequestHeader(name, value));
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) options.onProgress?.(event.loaded, event.total);
    };
    xhr.upload.onload = () => options.onProgress?.(body.size, body.size);
    xhr.onload = () => {
      const answer = new Headers();
      for (const line of xhr.getAllResponseHeaders().split('\r\n')) {
        const colon = line.indexOf(':');
        if (colon > 0) answer.append(line.slice(0, colon).trim(), line.slice(colon + 1).trim());
      }
      resolve(new Response(xhr.responseText, { status: xhr.status, headers: answer }));
    };
    xhr.onerror = () => reject(new TypeError('the Engine did not answer'));
    xhr.onabort = () => reject(new DOMException('The upload was cancelled', 'AbortError'));
    options.signal?.addEventListener('abort', () => xhr.abort(), { once: true });
    xhr.send(body);
  });
}

const failureOf = (error: unknown): ApiFailure =>
  error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

export function createSession(options: SessionOptions): Session {
  const { method, sync, baseUrl = '', askTimeoutMs = 500, oidcTimeoutMs = 3_000, onOutcome } = options;
  const rawFetch = options.fetch ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const store = createCredentialStore(options.storage);

  let state = $state<IdentityState>({ status: 'restoring' });
  // Moves on whenever the session changes under the feet of something that is still being checked
  // (a sign-out, a refusal, a session of another tab): what was started before must not end it.
  let epoch = 0;
  let asking: ((credential: string | null) => void) | null = null;
  // The provider's way to sign in, when the Engine has it on.
  let oidcLogin = $state<OidcLogin | null>(null);
  // Set while the person is signed in with the provider's session: the CSRF token of it, which is
  // all the Console holds (the session itself is a cookie that scripts cannot read). Null with a token.
  let csrfToken: string | null = null;

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
    // The cookie of a provider's session is the browser's, shared by every tab, which find out by
    // their own next request; their tokens are not touched.
    const withToken = csrfToken === null;
    csrfToken = null;
    store.clear();
    state = { status: 'anonymous', reason: 'expired' };
    if (withToken) sync.post({ type: 'expired' });
  }

  /** Asks the Engine for the ways to sign in with a provider; a failed question is "none". */
  async function learnOidc(): Promise<void> {
    try {
      const response = await send(METHODS_PATH, {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        signal: AbortSignal.timeout(oidcTimeoutMs),
      });
      const method = response.ok ? parseOidcMethod(await response.json().catch(() => null)) : null;
      oidcLogin = method
        ? { label: method.label, loginUrl: (target) => loginUrl(baseUrl, target) }
        : null;
    } catch {
      oidcLogin = null;
    }
  }

  /** The session the browser holds with the Engine (the cookie), if there is one. */
  async function findOidcSession() {
    try {
      const response = await send(SESSION_PATH, {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'include',
        signal: AbortSignal.timeout(oidcTimeoutMs),
      });
      return response.ok ? parseOidcSession(await response.json().catch(() => null)) : null;
    } catch {
      return null;
    }
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
    csrfToken = null;
    asking?.(null);
    store.clear();
    state = was ? { status: 'anonymous', reason } : { status: 'anonymous' };
  };

  const unsubscribe = sync.subscribe((message) => {
    // The session of a provider is not the tabs' to share: what happens to it reaches a tab by the
    // Engine's answers, and a tab that has a token must not take it from them.
    if (csrfToken !== null && state.status === 'authenticated') return;
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

  /** Whether the session that a request was made with is the one the tab still has. */
  const stillHolds = (credential: string | null) =>
    credential === null ? csrfToken !== null : store.read() === credential;

  /** A call as the person signed in with the provider: the cookie, and the CSRF token for a change. */
  async function requestAsOidc(path: string, init?: RequestInit): Promise<Response> {
    const token = csrfToken;
    const headers = new Headers(init?.headers);
    const read = ['GET', 'HEAD', 'OPTIONS'].includes((init?.method ?? 'GET').toUpperCase());
    if (!read && token !== null) headers.set('X-CSRF-Token', token);
    let response: Response;
    try {
      response = await send(path, { ...init, headers, credentials: 'include' });
    } catch (error) {
      throw failureOf(error);
    }
    // Only a refusal of the session this request was made with may end it (not of one that came since).
    if (response.status === 401 && csrfToken === token) expire();
    return response;
  }

  return {
    SignIn: method.SignIn,
    OidcSignIn,

    get state() {
      return state;
    },

    get oidc() {
      return oidcLogin;
    },

    async start() {
      state = { status: 'restoring' };
      const own = store.read();
      if (own !== null) {
        // The pages that exist only with a provider need to know it is there, token or not.
        await learnOidc();
        return adopt(own);
      }
      const before = epoch;
      await learnOidc();
      if (oidcLogin !== null) {
        const found = await findOidcSession();
        if (epoch !== before) return;
        if (found !== null) {
          csrfToken = found.csrfToken;
          state = { status: 'authenticated', identity: found.identity };
          return;
        }
      }
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

    async signOut() {
      if (csrfToken === null) {
        forget('signed-out');
        sync.post({ type: 'signed-out' });
        return;
      }
      let response: Response;
      try {
        response = await send(LOGOUT_PATH, {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-CSRF-Token': csrfToken },
        });
      } catch {
        return;
      }
      // 401 says there is no session any more, which is what a sign-out is for.
      if (response.ok || response.status === 401) forget('signed-out');
    },

    async request(path, init) {
      const credential = store.read();
      if (credential === null && csrfToken !== null) return requestAsOidc(path, init);
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
      if (response.status === 401 && stillHolds(credential)) expire();
      return response;
    },

    async upload(path, body, uploadOptions = {}) {
      const credential = store.read();
      if (credential === null && csrfToken === null) {
        throw new ApiFailure(401, null, 'nobody is signed in');
      }
      uploadOptions.signal?.throwIfAborted();
      const headers = new Headers();
      if (credential !== null) method.authorize(credential, headers);
      else headers.set('X-CSRF-Token', csrfToken!);
      if (uploadOptions.contentType) headers.set('Content-Type', uploadOptions.contentType);
      let response: Response;
      try {
        response = await post(`${baseUrl}${path}`, body, headers, uploadOptions, credential === null);
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') throw error;
        onOutcome?.(false);
        throw failureOf(error);
      }
      onOutcome?.(response.status < 500);
      if (response.status === 401 && stillHolds(credential)) expire();
      return response;
    },

    dispose() {
      unsubscribe();
      asking?.(null);
      sync.close();
    },
  };
}

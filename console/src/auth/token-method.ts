// The Bearer token as the way to sign in (ADR-012, ADR-017): the credential is the token, sent as
// `Authorization: Bearer`; who it is, is read from `GET /api/v1/system`, which answers 401 for a
// token it does not know. This is a transitional method: the files of it are this one and
// TokenSignIn.svelte, and nothing else of the Console knows that a token exists.

import { ApiFailure } from '../api/failure.ts';
import type { Identity } from '../app/identity.svelte.ts';
import { parseSystem } from '../engine/system.ts';
import type { AuthMethod, Transport } from './method.ts';
import TokenSignIn from './TokenSignIn.svelte';

export function createTokenMethod(options: { timeoutMs?: number } = {}): AuthMethod {
  const { timeoutMs = 10_000 } = options;

  const method: AuthMethod = {
    authorize(credential, headers) {
      headers.set('Authorization', `Bearer ${credential}`);
    },

    async identify(credential: string, transport: Transport): Promise<Identity> {
      const headers = new Headers({ Accept: 'application/json' });
      method.authorize(credential, headers);
      let response: Response;
      try {
        response = await transport('/api/v1/system', {
          headers,
          cache: 'no-store',
          signal: AbortSignal.timeout(timeoutMs),
        });
      } catch (error) {
        throw new ApiFailure(0, null, `the Engine did not answer: ${String(error)}`);
      }
      const body: unknown = await response.json().catch(() => null);
      if (!response.ok) throw new ApiFailure(response.status, body);
      return parseSystem(body).caller;
    },

    SignIn: TokenSignIn,
  };
  return method;
}

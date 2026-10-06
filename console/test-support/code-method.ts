// A second way to sign in, for the tests only: an access code that is sent in the header
// `X-Session-Code`, with nothing of a Bearer token about it. It proves that the Console's screens,
// routes and requests do not depend on the kind of credential (WI-34): they run, unchanged, on this
// method as on the token method. It is not part of what the build packs into the Engine.

import { ApiFailure } from '../src/api/failure';
import type { AuthMethod } from '../src/auth/method';
import { parseSystem } from '../src/engine/system';
import CodeSignIn from './CodeSignIn.svelte';

export function createCodeMethod(): AuthMethod {
  const method: AuthMethod = {
    authorize(credential, headers) {
      headers.set('X-Session-Code', credential);
    },
    async identify(credential, transport) {
      const headers = new Headers({ Accept: 'application/json' });
      method.authorize(credential, headers);
      let response: Response;
      try {
        response = await transport('/api/v1/system', { headers, cache: 'no-store' });
      } catch (error) {
        throw new ApiFailure(0, null, String(error));
      }
      const body: unknown = await response.json().catch(() => null);
      if (!response.ok) throw new ApiFailure(response.status, body);
      return parseSystem(body).caller;
    },
    SignIn: CodeSignIn,
  };
  return method;
}

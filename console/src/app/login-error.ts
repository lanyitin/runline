// A login that failed comes back to the Console as `/?login_error=<code>` (08-api.md, the callback of
// the login): the Engine sends the browser, so the code is in the address. It is read once, shown
// in words of the Console's own, and taken out of the address, so that it is not carried on (to the
// page signing in again returns to) nor bookmarked. The value is never shown: only the codes the
// Console knows have words, and any other value is "signing in failed".

const PARAMETER = 'login_error';

export const LOGIN_ERRORS = [
  'access_denied',
  'invalid_state',
  'invalid_token',
  'refresh_unavailable',
  'not_authorized',
  'name_conflict',
  'provider_unavailable',
  'user_disabled',
  'too_many_logins',
] as const;

export type LoginError = (typeof LOGIN_ERRORS)[number] | 'unknown';

/** The code in [search] (`?a=b`), as the Console knows it, and [search] without it. */
export function takeLoginError(search: string): { error: LoginError | null; rest: string } {
  const params = new URLSearchParams(search);
  const value = params.get(PARAMETER);
  if (value === null) return { error: null, rest: search };
  params.delete(PARAMETER);
  const rest = params.toString();
  const error = (LOGIN_ERRORS as readonly string[]).includes(value) ? (value as LoginError) : 'unknown';
  return { error, rest: rest === '' ? '' : `?${rest}` };
}

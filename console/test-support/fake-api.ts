// What the parts of the Fake Engine's API (fake-backend.ts and the parts it is made of) have in
// common: the request as it is given to them, the answer as they give it back, and the routes.

export interface FakeCallerRef {
  name: string;
  role: 'developer' | 'admin';
}

export interface ApiRequest {
  method: string;
  /** The path without the query. */
  path: string;
  query: URLSearchParams;
  caller: FakeCallerRef;
  body: Buffer;
}

export interface ApiAnswer {
  status: number;
  headers?: Record<string, string>;
  /** Sent as JSON; none for an answer without a body. */
  json?: unknown;
}

export const answer = (
  status: number,
  json?: unknown,
  headers?: Record<string, string>,
): ApiAnswer => ({ status, json, headers });

export const failure = (status: number, error: string, message: string, extra: object = {}) =>
  answer(status, { error, message, ...extra });

export const forbidden = () => failure(403, 'forbidden', 'This needs the role ADMIN.');

/** A route: [handle] gets the request and what the groups of the path matched. */
export interface FakeRoute {
  method: string;
  pattern: RegExp;
  /** Only an admin may call it: a developer is answered with 403 `forbidden`. */
  admin: boolean;
  handle(request: ApiRequest, match: RegExpMatchArray): ApiAnswer;
}

/** The JSON object of the body, or null when the body is not one. */
export function jsonObject(body: Buffer): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(body.toString('utf8'));
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

export const isStringMap = (value: unknown): value is Record<string, string> =>
  typeof value === 'object' &&
  value !== null &&
  !Array.isArray(value) &&
  Object.values(value).every((v) => typeof v === 'string');

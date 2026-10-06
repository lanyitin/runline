// The way to the Engine for every call of the API (08-api.md): what a call asks, and what an answer
// is. An answer that is not a success is an `ApiFailure` with the status and the body of the Engine,
// which `describeApiError` turns into words; an answer that is no answer is an `ApiFailure` with
// status 0.

import { ApiFailure } from './failure.ts';

/** The parts of the session the API needs. */
export interface Transport {
  request(path: string, init?: RequestInit): Promise<Response>;
  upload(
    path: string,
    body: Blob,
    options?: {
      contentType?: string;
      signal?: AbortSignal;
      onProgress?: (sent: number, total: number) => void;
    },
  ): Promise<Response>;
}

async function read(response: Response): Promise<unknown> {
  let text: string;
  try {
    text = await response.text();
  } catch (error) {
    throw new ApiFailure(0, null, `the answer of the Engine could not be read: ${String(error)}`);
  }
  if (text === '') return null;
  try {
    return JSON.parse(text);
  } catch {
    return response.ok ? undefined : null;
  }
}

/** The body of a success, or the failure the Engine answered. */
export async function succeed(response: Response): Promise<unknown> {
  const body = await read(response);
  if (!response.ok) throw new ApiFailure(response.status, body);
  if (body === undefined) throw new ApiFailure(0, null, 'an answer of the Engine is not JSON');
  return body;
}

export async function send(transport: Transport, path: string, init?: RequestInit): Promise<Response> {
  try {
    return await transport.request(path, init);
  } catch (error) {
    throw error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
  }
}

/** A call that asks for JSON, never from the cache, through the session. */
export function jsonCall(transport: Transport) {
  return (path: string, init: RequestInit = {}) =>
    send(transport, path, {
      ...init,
      headers: { Accept: 'application/json', ...init.headers },
      cache: 'no-store',
    });
}

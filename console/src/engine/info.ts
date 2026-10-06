// The Engine's identity: version and commit hash, from `GET /api/v1/info` (ADR-016, 08-api.md).
// The endpoint needs no token, so the Console can show it before anyone has signed in. It is the
// only call of the Console that is made without the authentication boundary (WI-34).

export interface EngineInfo {
  version: string;
  /** The full 40 character hash, or `unknown`. */
  commitHash: string;
  /** Whether the working tree had uncommitted changes when the Engine was built. */
  dirty: boolean;
}

/** The Engine did not give its info: not reachable, not in time, an error status, or nonsense. */
export class EngineInfoError extends Error {
  constructor(
    message: string,
    readonly status: number | null = null,
    /** The JSON body of an error answer, if it had one. */
    readonly body: unknown = null,
  ) {
    super(message);
    this.name = 'EngineInfoError';
  }
}

const SHORT_HASH_LENGTH = 7;

export const shortHash = (commitHash: string): string =>
  commitHash === 'unknown' ? commitHash : commitHash.slice(0, SHORT_HASH_LENGTH);

function parse(json: unknown): EngineInfo {
  const record = typeof json === 'object' && json !== null ? (json as Record<string, unknown>) : {};
  const { version, commitHash, dirty } = record;
  if (typeof version !== 'string' || typeof commitHash !== 'string' || typeof dirty !== 'boolean') {
    throw new EngineInfoError('the answer of /api/v1/info does not have version, commitHash, dirty');
  }
  return { version, commitHash, dirty };
}

export interface FetchEngineInfoOptions {
  /** The origin of the Engine; empty means the origin that served the Console (the only use in production). */
  baseUrl?: string;
  timeoutMs?: number;
}

export async function fetchEngineInfo(options: FetchEngineInfoOptions = {}): Promise<EngineInfo> {
  const { baseUrl = '', timeoutMs = 5_000 } = options;
  let response: Response;
  try {
    response = await fetch(`${baseUrl}/api/v1/info`, {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (error) {
    throw new EngineInfoError(`the Engine did not answer: ${String(error)}`);
  }

  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    throw new EngineInfoError(`the Engine answered ${response.status}`, response.status, body);
  }
  return parse(body);
}

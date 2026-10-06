// A small, real implementation of the part of the Engine's API that the Console reads:
// `GET /api/v1/info` (no token) and `GET /api/v1/system` (a Bearer token; 08-api.md). It listens on
// a port and speaks HTTP, so what is tested against it is the Console's real request and real
// response handling. It is not the Engine: its behaviour is held to the Engine's by the contract
// tests (contract/), which run against this and, with `npm run test:contract`, against a real
// packaged Engine.
//
// It can also fail like the Engine does: a 503 while shutting down, a 500, an answer that is not
// what the contract says, an answer that does not come.

import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

export interface FakeEngineInfo {
  version: string;
  commitHash: string;
  dirty: boolean;
}

export interface FakeCaller {
  name: string;
  role: 'developer' | 'admin';
  token: string;
}

export interface FakeSystemDetails {
  buildTime: string;
  jdk: string;
  startedAt: string;
  uptimeSeconds: number;
  allowListVersion: string;
}

export interface FakeEngineOptions {
  /** Who may call `GET /api/v1/system`, by token. */
  callers?: FakeCaller[];
  system?: Partial<FakeSystemDetails>;
}

const DEFAULT_SYSTEM: FakeSystemDetails = {
  buildTime: '2026-10-05T08:30:00Z',
  jdk: '25.0.4+1-LTS',
  startedAt: '2026-10-04T04:00:00Z',
  uptimeSeconds: 100_000,
  allowListVersion: '3',
};

export type FakeEngineMode =
  | 'normal'
  | 'shutting-down'
  | 'internal-error'
  | 'malformed-json'
  | 'wrong-shape'
  | 'hang';

export class FakeEngine {
  mode: FakeEngineMode = 'normal';
  requests = 0;
  /** The paths asked, in order, with the Authorization and X-Session-Code header each had (or null). */
  readonly log: Array<{ path: string; authorization: string | null; sessionCode: string | null }> = [];
  /** Who is known by token; a token removed from here is refused from then on (an expired session). */
  callers: FakeCaller[] = [];

  private constructor(
    private readonly server: Server,
    readonly url: string,
  ) {}

  static async start(info: FakeEngineInfo, options: FakeEngineOptions = {}): Promise<FakeEngine> {
    let engine!: FakeEngine;
    const system = { ...DEFAULT_SYSTEM, ...options.system };
    const server = createServer((request, response) => {
      engine.requests += 1;
      const path = request.url ?? '';
      const sessionCode = request.headers['x-session-code'];
      engine.log.push({
        path,
        authorization: request.headers.authorization ?? null,
        sessionCode: typeof sessionCode === 'string' ? sessionCode : null,
      });
      const isInfo = path === '/api/v1/info';
      const isSystem = path === '/api/v1/system';
      if (request.method !== 'GET' || !(isInfo || isSystem)) {
        // As the Engine answers an unknown path under /api: 404, no body.
        response.writeHead(404, { 'Content-Length': 0 });
        response.end();
        return;
      }
      const json = (status: number, body: string) => {
        response.writeHead(status, {
          'Content-Type': 'application/json; charset=UTF-8',
          'Cache-Control': 'no-store',
        });
        response.end(body);
      };
      switch (engine.mode) {
        case 'normal': {
          if (isInfo) return json(200, JSON.stringify(info));
          // As the Engine answers a missing or unknown token: 401, Bearer, no body. Beside the
          // Bearer token the Fake knows the header `X-Session-Code`, with the same callers: the
          // second way to sign in of the tests that show the Console does not depend on the kind of
          // credential (test-support/code-method.ts). The Engine has no such header.
          const token =
            /^Bearer (.+)$/.exec(request.headers.authorization ?? '')?.[1] ??
            (typeof sessionCode === 'string' ? sessionCode : undefined);
          const caller = engine.callers.find((c) => c.token === token);
          if (!caller) {
            response.writeHead(401, { 'WWW-Authenticate': 'Bearer realm=runline', 'Content-Length': 0 });
            response.end();
            return;
          }
          return json(
            200,
            JSON.stringify({
              ...info,
              ...system,
              caller: { name: caller.name, role: caller.role },
            }),
          );
        }
        case 'shutting-down':
          return json(503, JSON.stringify({ error: 'shutting_down', message: 'Shutting down' }));
        case 'internal-error':
          return json(
            500,
            JSON.stringify({ error: 'internal_error', message: 'boom', errorId: 'e-1' }),
          );
        case 'malformed-json':
          return json(200, '{"version": ');
        case 'wrong-shape':
          return json(200, JSON.stringify({ version: 7, commitHash: null }));
        case 'hang':
          return; // never answers
      }
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const { port } = server.address() as AddressInfo;
    engine = new FakeEngine(server, `http://127.0.0.1:${port}`);
    engine.callers = [...(options.callers ?? [])];
    return engine;
  }

  async stop(): Promise<void> {
    this.server.closeAllConnections();
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
  }
}

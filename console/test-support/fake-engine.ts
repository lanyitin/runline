// A small, real implementation of the part of the Engine's API that the Console reads without a
// token: `GET /api/v1/info` (08-api.md). It listens on a port and speaks HTTP, so what is tested
// against it is the Console's real request and real response handling. It is not the Engine: its
// behaviour is held to the Engine's by the contract test (src/engine/info.contract.test.ts), which
// runs against this and, with `npm run test:contract`, against a real packaged Engine.
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

  private constructor(
    private readonly server: Server,
    readonly url: string,
  ) {}

  static async start(info: FakeEngineInfo): Promise<FakeEngine> {
    let engine!: FakeEngine;
    const server = createServer((request, response) => {
      engine.requests += 1;
      if (request.method !== 'GET' || request.url !== '/api/v1/info') {
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
        case 'normal':
          return json(200, JSON.stringify(info));
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
    return engine;
  }

  async stop(): Promise<void> {
    this.server.closeAllConnections();
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
  }
}

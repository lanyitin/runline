// What the browser tests of the signed-in shell run against: the Console built for production (the
// same Vite config, so the same plugins and the same rules), with the preview entry (preview/) as
// its entry, served the way the Engine serves the Console and with the Engine's own security
// headers, which are read from the real Engine; `/api` is passed on to that Engine. Nothing of the
// Console is replaced: only the identity is chosen by the address, in the preview entry.

import { mkdtempSync, readFileSync, statSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { extname, join, normalize, sep } from 'node:path';
import { build } from 'vite';

const TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.woff2': 'font/woff2',
  '.svg': 'image/svg+xml',
};
const SECURITY_HEADERS = [
  'content-security-policy',
  'x-content-type-options',
  'x-frame-options',
  'referrer-policy',
];

export interface PreviewServer {
  url: string;
  stop(): Promise<void>;
}

export async function startPreviewServer(engineUrl: string): Promise<PreviewServer> {
  const out = mkdtempSync(join(tmpdir(), 'console-preview-'));
  await build({
    logLevel: 'warn',
    build: { outDir: out, emptyOutDir: true, rollupOptions: { input: 'preview/index.html' } },
  });

  const headers: Record<string, string> = {};
  const entry = await fetch(`${engineUrl}/`);
  for (const name of SECURITY_HEADERS) {
    const value = entry.headers.get(name);
    if (!value) throw new Error(`the Engine's Console answer has no ${name} header`);
    headers[name] = value;
  }

  const server: Server = createServer(async (request, response) => {
    const url = new URL(request.url ?? '/', 'http://preview');
    if (url.pathname.startsWith('/api/')) {
      const answer = await fetch(`${engineUrl}${url.pathname}${url.search}`, {
        method: request.method,
      });
      response.writeHead(answer.status, {
        'content-type': answer.headers.get('content-type') ?? 'application/octet-stream',
        'cache-control': 'no-store',
      });
      response.end(Buffer.from(await answer.arrayBuffer()));
      return;
    }
    const file = normalize(join(out, url.pathname));
    const isFile = file.startsWith(out + sep) && statSync(file, { throwIfNoEntry: false })?.isFile();
    const path = isFile ? file : join(out, 'preview', 'index.html');
    response.writeHead(200, { ...headers, 'content-type': TYPES[extname(path)] ?? 'text/plain' });
    response.end(readFileSync(path));
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${port}`,
    stop: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}

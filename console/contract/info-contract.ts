// What GET /api/v1/info promises (08-api.md, ADR-016), as tests that run on any server that claims
// to be the Engine: the Fake of test-support (always, in `npm test`) and a real packaged Engine
// (`npm run test:contract` with RUNLINE_ENGINE_URL). A Fake that fails here does not stand for
// the Engine.

import { describe, expect, test } from 'vitest';
import { fetchEngineInfo } from '../src/engine/info';

export function describeInfoContract(name: string, baseUrl: () => string) {
  describe(`GET /api/v1/info of ${name}`, () => {
    test('answers 200 JSON without any credential', async () => {
      const response = await fetch(`${baseUrl()}/api/v1/info`);
      expect(response.status).toBe(200);
      expect(response.headers.get('content-type')).toMatch(/^application\/json/);
    });

    test('holds a version, a commit hash of 40 hex characters or unknown, and a dirty flag', async () => {
      const body = await (await fetch(`${baseUrl()}/api/v1/info`)).json();
      expect(typeof body.version).toBe('string');
      expect(body.version).not.toBe('');
      expect(body.commitHash).toMatch(/^([0-9a-f]{40}|unknown)$/);
      expect(typeof body.dirty).toBe('boolean');
    });

    test('is not cached', async () => {
      const response = await fetch(`${baseUrl()}/api/v1/info`);
      expect(response.headers.get('cache-control')).toContain('no-store');
    });

    test('is read by the Console as the same three values', async () => {
      const raw = await (await fetch(`${baseUrl()}/api/v1/info`)).json();
      const info = await fetchEngineInfo({ baseUrl: baseUrl() });
      expect(info).toEqual({
        version: raw.version,
        commitHash: raw.commitHash,
        dirty: raw.dirty,
      });
    });

    test('an unknown path under /api is a 404, never the entry page of the Console', async () => {
      const response = await fetch(`${baseUrl()}/api/v1/nothing-here`);
      expect(response.status).toBe(404);
      expect(response.headers.get('content-type') ?? '').not.toMatch(/html/);
      expect(await response.text()).not.toMatch(/<!doctype html/i);
    });
  });
}

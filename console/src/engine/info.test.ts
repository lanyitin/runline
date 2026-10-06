import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { FakeEngine } from '../../test-support/fake-engine';
import { EngineInfoError, fetchEngineInfo, shortHash } from './info';

const FULL = 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70';
let engine: FakeEngine;

beforeEach(async () => {
  engine = await FakeEngine.start({ version: '0.4.2', commitHash: FULL, dirty: false });
});
afterEach(() => engine.stop());

describe('fetchEngineInfo', () => {
  test('reads version, commit hash and dirty flag of GET /api/v1/info', async () => {
    expect(await fetchEngineInfo({ baseUrl: engine.url })).toEqual({
      version: '0.4.2',
      commitHash: FULL,
      dirty: false,
    });
  });

  test('asks the origin it is given once', async () => {
    await fetchEngineInfo({ baseUrl: engine.url });
    expect(engine.requests).toBe(1);
  });

  test.each(['shutting-down', 'internal-error'] as const)(
    'an answer of status %s is a failure that keeps the status and the body',
    async (mode) => {
      engine.mode = mode;
      const failure = await fetchEngineInfo({ baseUrl: engine.url }).catch((e) => e);
      expect(failure).toBeInstanceOf(EngineInfoError);
      expect(failure.status).toBe(mode === 'shutting-down' ? 503 : 500);
      expect(failure.body).toMatchObject({ error: expect.any(String) });
    },
  );

  test.each(['malformed-json', 'wrong-shape'] as const)(
    'an answer that is %s is a failure',
    async (mode) => {
      engine.mode = mode;
      await expect(fetchEngineInfo({ baseUrl: engine.url })).rejects.toBeInstanceOf(EngineInfoError);
    },
  );

  test('an Engine that does not answer in time is a failure, not a wait without end', async () => {
    engine.mode = 'hang';
    await expect(
      fetchEngineInfo({ baseUrl: engine.url, timeoutMs: 100 }),
    ).rejects.toBeInstanceOf(EngineInfoError);
  });

  test('an Engine that cannot be reached is a failure', async () => {
    await engine.stop();
    await expect(fetchEngineInfo({ baseUrl: engine.url })).rejects.toBeInstanceOf(EngineInfoError);
  });
});

describe('shortHash', () => {
  test('is the first 7 characters of the commit', () => {
    expect(shortHash(FULL)).toBe('a3f9c1e');
  });
  test('leaves unknown as it is', () => {
    expect(shortHash('unknown')).toBe('unknown');
  });
});

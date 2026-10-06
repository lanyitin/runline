import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { ApiFailure } from '../api/failure';
import { FakeEngine } from '../../test-support/fake-engine';
import { createSession, type Session } from '../auth/session.svelte';
import { createTabSync } from '../auth/tab-sync';
import { createTokenMethod } from '../auth/token-method';
import { MemoryStorage } from '../../test-support/memory-storage';
import { fetchSystem, parseSystem, toEngineInfo } from './system';

const FULL = 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70';
const body = {
  version: '0.4.2',
  commitHash: FULL,
  dirty: true,
  buildTime: '2026-10-05T08:30:00Z',
  jdk: '25.0.4+1-LTS',
  startedAt: '2026-10-04T04:00:00Z',
  uptimeSeconds: 100_000,
  allowListVersion: '3',
  caller: { name: 'ada', role: 'developer' },
};

describe('parseSystem', () => {
  test('reads the answer of GET /api/v1/system', () => {
    expect(parseSystem(body)).toEqual(body);
  });

  test('drops what 08-api.md does not say', () => {
    expect(parseSystem({ ...body, token: 'secret', host: 'h' })).toEqual(body);
  });

  test.each([
    'version',
    'commitHash',
    'dirty',
    'buildTime',
    'jdk',
    'startedAt',
    'uptimeSeconds',
    'allowListVersion',
    'caller',
  ])('an answer without %s is no answer', (field) => {
    const { [field]: _left, ...rest } = body as Record<string, unknown>;
    expect(() => parseSystem(rest)).toThrow(ApiFailure);
  });

  test.each([null, 'text', 7, []])('%j is no answer', (value) => {
    expect(() => parseSystem(value)).toThrow(ApiFailure);
  });

  test('a caller whose role is neither developer nor admin is no answer', () => {
    expect(() => parseSystem({ ...body, caller: { name: 'x', role: 'guest' } })).toThrow(
      ApiFailure,
    );
  });
});

describe('toEngineInfo', () => {
  test('is the identity of the Engine, with the details of the system and when they were read', () => {
    expect(toEngineInfo(parseSystem(body), 1_000)).toEqual({
      version: '0.4.2',
      commitHash: FULL,
      dirty: true,
      system: {
        buildTime: '2026-10-05T08:30:00Z',
        jdk: '25.0.4+1-LTS',
        startedAt: '2026-10-04T04:00:00Z',
        uptimeSeconds: 100_000,
        allowListVersion: '3',
        receivedAt: 1_000,
      },
    });
  });
});

describe('fetchSystem', () => {
  let engine: FakeEngine;
  let session: Session;
  beforeEach(async () => {
    engine = await FakeEngine.start(
      { version: '0.4.2', commitHash: FULL, dirty: false },
      { callers: [{ name: 'ada', role: 'developer', token: 'tok-ada' }] },
    );
    session = createSession({
      method: createTokenMethod(),
      storage: new MemoryStorage(),
      sync: createTabSync(),
      baseUrl: engine.url,
      askTimeoutMs: 20,
    });
    await session.start();
    await session.signIn('tok-ada');
  });
  afterEach(async () => {
    session.dispose();
    await engine.stop();
  });

  test('reads it through the session, which gives the credential', async () => {
    const system = await fetchSystem(session.request);
    expect(system.caller).toEqual({ name: 'ada', role: 'developer' });
    expect(system.jdk).toBe('25.0.4+1-LTS');
    expect(engine.log.at(-1)).toEqual({
      path: '/api/v1/system',
      authorization: 'Bearer tok-ada',
      sessionCode: null,
    });
  });

  test('a refusal is a failure with its status, and ends the session', async () => {
    engine.callers = [];
    const failure = await fetchSystem(session.request).catch((e) => e);
    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure.status).toBe(401);
    expect(session.state).toMatchObject({ status: 'anonymous', reason: 'expired' });
  });

  test('an Engine that does not answer in time is a failure, not a wait without end', async () => {
    engine.mode = 'hang';
    await expect(fetchSystem(session.request, { timeoutMs: 100 })).rejects.toMatchObject({
      status: 0,
    });
  });
});

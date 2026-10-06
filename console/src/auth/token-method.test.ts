import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { FakeEngine } from '../../test-support/fake-engine';
import { ApiFailure } from '../api/failure';
import type { Transport } from './method';
import { createTokenMethod } from './token-method';

const developer = { name: 'ada', role: 'developer' as const, token: 'tok-ada-0123456789' };
let engine: FakeEngine;
let transport: Transport;

beforeEach(async () => {
  engine = await FakeEngine.start(
    { version: '0.4.2', commitHash: 'a'.repeat(40), dirty: false },
    { callers: [developer, { name: 'root', role: 'admin', token: 'tok-root-0123456789' }] },
  );
  transport = (path, init) => fetch(`${engine.url}${path}`, init);
});
afterEach(() => engine.stop());

describe('the token method', () => {
  test('sends the credential as a Bearer token', () => {
    const headers = new Headers();
    createTokenMethod().authorize('tok-abc', headers);
    expect(headers.get('Authorization')).toBe('Bearer tok-abc');
  });

  test.each([
    [developer.token, { name: 'ada', role: 'developer' }],
    ['tok-root-0123456789', { name: 'root', role: 'admin' }],
  ])('knows who %s is from GET /api/v1/system', async (token, expected) => {
    expect(await createTokenMethod().identify(token, transport)).toEqual(expected);
    expect(engine.log).toEqual([
      { path: '/api/v1/system', authorization: `Bearer ${token}`, sessionCode: null },
    ]);
  });

  test('refuses a token the Engine does not know: 401', async () => {
    const failure = await createTokenMethod()
      .identify('tok-wrong', transport)
      .catch((e) => e);
    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure.status).toBe(401);
  });

  test('keeps the status and the body of any other refusal', async () => {
    engine.mode = 'shutting-down';
    const failure = await createTokenMethod()
      .identify(developer.token, transport)
      .catch((e) => e);
    expect(failure).toMatchObject({ status: 503, body: { error: 'shutting_down' } });
  });

  test.each(['malformed-json', 'wrong-shape'] as const)(
    'an answer that is %s is no answer (status 0)',
    async (mode) => {
      engine.mode = mode;
      const failure = await createTokenMethod()
        .identify(developer.token, transport)
        .catch((e) => e);
      expect(failure).toBeInstanceOf(ApiFailure);
      expect(failure.status).toBe(0);
    },
  );

  test('an answer without a role the Console knows is no answer', async () => {
    engine.callers = [{ name: 'eve', role: 'auditor' as never, token: 'tok-eve' }];
    const failure = await createTokenMethod()
      .identify('tok-eve', transport)
      .catch((e) => e);
    expect(failure).toMatchObject({ status: 0 });
  });

  test('an Engine that does not answer in time is no answer, not a wait without end', async () => {
    engine.mode = 'hang';
    const failure = await createTokenMethod({ timeoutMs: 100 })
      .identify(developer.token, transport)
      .catch((e) => e);
    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure.status).toBe(0);
  });

  test('an Engine that cannot be reached is no answer', async () => {
    await engine.stop();
    const failure = await createTokenMethod()
      .identify(developer.token, transport)
      .catch((e) => e);
    expect(failure).toMatchObject({ status: 0 });
  });
});

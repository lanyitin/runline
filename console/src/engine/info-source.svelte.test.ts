import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { createConnectionMonitor } from '../api/connection.svelte';
import { createSession, type Session } from '../auth/session.svelte';
import { createTabSync } from '../auth/tab-sync';
import { createTokenMethod } from '../auth/token-method';
import { FakeEngine } from '../../test-support/fake-engine';
import { MemoryStorage } from '../../test-support/memory-storage';
import { createEngineInfo } from './info-source.svelte';

const FULL = 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70';
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
});
afterEach(async () => {
  session.dispose();
  await engine.stop();
});

test('before anyone has signed in it is the public info only: version, hash, dirty', async () => {
  const connection = createConnectionMonitor();
  const info = createEngineInfo({ session, connection, baseUrl: engine.url });

  await vi.waitFor(() => expect(info.state.status).toBe('ready'));

  expect(info.state).toMatchObject({ info: { version: '0.4.2', commitHash: FULL } });
  expect((info.state as { info: { system?: unknown } }).info.system).toBeUndefined();
  expect(engine.log.every((call) => call.authorization === null)).toBe(true);
  info.dispose();
});

test('once signed in it is the system info, with the details, read with the credential', async () => {
  const connection = createConnectionMonitor();
  const info = createEngineInfo({ session, connection, baseUrl: engine.url });
  await vi.waitFor(() => expect(info.state.status).toBe('ready'));

  await session.signIn('tok-ada');

  await vi.waitFor(() =>
    expect(info.state).toMatchObject({
      status: 'ready',
      info: { system: { jdk: '25.0.4+1-LTS', allowListVersion: '3', uptimeSeconds: 100_000 } },
    }),
  );
  info.dispose();
});

test('after signing out it is the public info again, without the details', async () => {
  const connection = createConnectionMonitor();
  const info = createEngineInfo({ session, connection, baseUrl: engine.url });
  await session.signIn('tok-ada');
  await vi.waitFor(() => expect(info.state).toMatchObject({ info: { system: expect.anything() } }));

  session.signOut();

  await vi.waitFor(() =>
    expect((info.state as { info: { system?: unknown } }).info.system).toBeUndefined(),
  );
  info.dispose();
});

test('the public read tells the connection how it went', async () => {
  const connection = createConnectionMonitor();
  const info = createEngineInfo({ session, connection, baseUrl: engine.url });
  await vi.waitFor(() => expect(connection.state).toBe('up'));

  engine.mode = 'internal-error';
  info.reload();
  await vi.waitFor(() => expect(connection.state).toBe('down'));
  info.dispose();
});

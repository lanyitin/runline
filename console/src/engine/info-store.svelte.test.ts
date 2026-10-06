import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { Signal } from '../../test-support/signal.svelte';
import { FakeEngine } from '../../test-support/fake-engine';
import { fetchEngineInfo, type EngineInfo } from './info';
import { createEngineInfoStore } from './info-store.svelte';

const FULL = 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70';
let engine: FakeEngine;

beforeEach(async () => {
  engine = await FakeEngine.start({ version: '0.4.2', commitHash: FULL, dirty: false });
});
afterEach(() => engine.stop());

const storeOf = () => createEngineInfoStore(() => fetchEngineInfo({ baseUrl: engine.url }));

test('is loading at first, then ready with the info', async () => {
  const store = storeOf();
  expect(store.state.status).toBe('loading');

  await vi.waitFor(() => expect(store.state.status).toBe('ready'));
  expect(store.state).toMatchObject({ info: { version: '0.4.2', commitHash: FULL } });
});

test('is failed when the Engine does not give it, and a reload can recover', async () => {
  engine.mode = 'internal-error';
  const store = storeOf();
  await vi.waitFor(() => expect(store.state.status).toBe('failed'));

  engine.mode = 'normal';
  store.reload();
  expect(store.state.status).toBe('loading');
  await vi.waitFor(() => expect(store.state.status).toBe('ready'));
});

test('an answer that comes late for an earlier try does not overwrite a newer one', async () => {
  // The store's own logic, with no I/O in it: two tries whose answers come in the wrong order.
  const first = Promise.withResolvers<EngineInfo>();
  const second = Promise.withResolvers<EngineInfo>();
  const answers = [first, second];
  const store = createEngineInfoStore(() => answers.shift()!.promise);
  store.reload();

  second.resolve({ version: 'new', commitHash: FULL, dirty: false });
  await vi.waitFor(() => expect(store.state.status).toBe('ready'));
  first.resolve({ version: 'old', commitHash: FULL, dirty: false });
  await Promise.resolve();
  await Promise.resolve();

  expect(store.state).toMatchObject({ status: 'ready', info: { version: 'new' } });
});

test('a reload keeps showing what it had until the new answer is there: no flash of loading', async () => {
  const second = Promise.withResolvers<EngineInfo>();
  const answers = [Promise.resolve({ version: 'one', commitHash: FULL, dirty: false }), second.promise];
  const store = createEngineInfoStore(() => answers.shift()!);
  await vi.waitFor(() => expect(store.state.status).toBe('ready'));

  store.reload();

  expect(store.state).toMatchObject({ status: 'ready', info: { version: 'one' } });
  second.resolve({ version: 'two', commitHash: FULL, dirty: false });
  await vi.waitFor(() => expect(store.state).toMatchObject({ info: { version: 'two' } }));
});

describe('a store that reloads when what it depends on changes', () => {
  test('reads again, with what changed, and not for a change that is no change', async () => {
    const who = new Signal<string | null>(null);
    const seen: Array<string | null> = [];
    const store = createEngineInfoStore(
      async () => {
        seen.push(who.value);
        return { version: who.value ?? 'public', commitHash: FULL, dirty: false };
      },
      { reloadWhen: () => who.value },
    );
    await vi.waitFor(() => expect(store.state).toMatchObject({ info: { version: 'public' } }));

    who.value = 'ada';
    await vi.waitFor(() => expect(store.state).toMatchObject({ info: { version: 'ada' } }));
    who.value = 'ada';
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(seen).toEqual([null, 'ada']);
    store.dispose();
  });

  test('stops watching when disposed', async () => {
    const who = new Signal<string | null>(null);
    let reads = 0;
    const store = createEngineInfoStore(
      async () => {
        reads += 1;
        return { version: 'v', commitHash: FULL, dirty: false };
      },
      { reloadWhen: () => who.value },
    );
    await vi.waitFor(() => expect(store.state.status).toBe('ready'));

    store.dispose();
    who.value = 'ada';
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(reads).toBe(1);
  });
});

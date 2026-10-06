import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { ApiFailure } from '../api/failure';
import { FakeEngine, type FakeCaller } from '../../test-support/fake-engine';
import { MemoryStorage } from '../../test-support/memory-storage';
import { createCredentialStore, SESSION_STORAGE_KEY } from './credential-store';
import { createSession, type Session, type SessionOptions } from './session.svelte';
import { CHANNEL_NAME, createTabSync, type TabMessage } from './tab-sync';
import { createTokenMethod } from './token-method';

const ada: FakeCaller = { name: 'ada', role: 'developer', token: 'tok-ada-0123456789' };
const root: FakeCaller = { name: 'root', role: 'admin', token: 'tok-root-0123456789' };
const ASK_TIMEOUT = 150;

let engine: FakeEngine;
const cleanup: Array<() => void> = [];

beforeEach(async () => {
  engine = await FakeEngine.start(
    { version: '0.4.2', commitHash: 'a'.repeat(40), dirty: false },
    { callers: [ada, root] },
  );
});
afterEach(async () => {
  cleanup.splice(0).forEach((close) => close());
  await engine.stop();
});

/** One tab of the Console: its own storage, its own channel object, the same origin and Engine. */
function tab(options: Partial<SessionOptions> & { storage?: MemoryStorage } = {}) {
  const storage = options.storage ?? new MemoryStorage();
  const session = createSession({
    method: createTokenMethod({ timeoutMs: 2_000 }),
    storage,
    sync: createTabSync(),
    baseUrl: engine.url,
    askTimeoutMs: ASK_TIMEOUT,
    ...options,
  });
  cleanup.push(() => session.dispose());
  return { session, storage, stored: () => createCredentialStore(storage).read() };
}

/** A listener on the channel that records what the tabs say to each other. */
function overhear() {
  const channel = new BroadcastChannel(CHANNEL_NAME);
  const heard: TabMessage[] = [];
  channel.onmessage = (event) => heard.push(event.data as TabMessage);
  cleanup.push(() => channel.close());
  return heard;
}

/** A tab that is scripted by the test: it says what a tab could say, in the order it chooses. */
function peer() {
  const channel = new BroadcastChannel(CHANNEL_NAME);
  const heard: TabMessage[] = [];
  channel.onmessage = (event) => heard.push(event.data as TabMessage);
  cleanup.push(() => channel.close());
  return { say: (message: TabMessage) => channel.postMessage(message), heard };
}

const authenticatedAs = (session: Session, name: string, role: string) =>
  expect(session.state).toEqual({ status: 'authenticated', identity: { name, role } });
const settle = (ms = ASK_TIMEOUT * 3) => new Promise((resolve) => setTimeout(resolve, ms));

describe('signing in', () => {
  test('with a token the Engine knows: signed in as who the Engine says, and the credential kept in the tab', async () => {
    const heard = overhear();
    const { session, stored } = tab();
    await session.start();

    expect(await session.signIn(ada.token)).toEqual({ ok: true });

    authenticatedAs(session, 'ada', 'developer');
    expect(stored()).toBe(ada.token);
    await vi.waitFor(() =>
      expect(heard).toContainEqual({ type: 'signed-in', credential: ada.token }),
    );
  });

  test('with a token the Engine does not know: refused with the 401, nothing kept, nothing said', async () => {
    const heard = overhear();
    const { session, stored } = tab();
    await session.start();

    const outcome = await session.signIn('tok-wrong');

    expect(outcome.ok).toBe(false);
    expect(!outcome.ok && outcome.failure.status).toBe(401);
    expect(session.state).toMatchObject({ status: 'anonymous' });
    expect(stored()).toBeNull();
    await settle(50);
    expect(heard.filter((m) => m.type === 'signed-in')).toEqual([]);
  });

  test('when the Engine cannot be reached: refused as no answer, nothing kept', async () => {
    const { session, stored } = tab();
    await session.start();
    await engine.stop();

    const outcome = await session.signIn(ada.token);

    expect(!outcome.ok && outcome.failure).toBeInstanceOf(ApiFailure);
    expect(!outcome.ok && outcome.failure.status).toBe(0);
    expect(stored()).toBeNull();
  });
});

describe('signing out', () => {
  test('forgets the credential, tells the other tabs, and the Console is anonymous', async () => {
    const heard = overhear();
    const { session, stored } = tab();
    await session.start();
    await session.signIn(ada.token);

    session.signOut();

    expect(session.state).toEqual({ status: 'anonymous', reason: 'signed-out' });
    expect(stored()).toBeNull();
    await vi.waitFor(() => expect(heard).toContainEqual({ type: 'signed-out' }));
  });
});

describe('a tab that starts', () => {
  test('and has a credential of its own (the page was reloaded) is back in the session without asking anyone', async () => {
    const storage = new MemoryStorage();
    createCredentialStore(storage).write(root.token);
    const heard = overhear();
    const { session } = tab({ storage });

    const starting = session.start();
    expect(session.state).toEqual({ status: 'restoring' });
    await starting;

    authenticatedAs(session, 'root', 'admin');
    await settle(50);
    expect(heard).toEqual([]);
  });

  test('and has a credential the Engine no longer accepts clears it, and says the session is no longer valid', async () => {
    const storage = new MemoryStorage();
    createCredentialStore(storage).write('tok-revoked');
    const heard = overhear();
    const { session, stored } = tab({ storage });

    await session.start();

    expect(session.state).toEqual({ status: 'anonymous', reason: 'expired' });
    expect(stored()).toBeNull();
    await vi.waitFor(() => expect(heard).toContainEqual({ type: 'expired' }));
  });

  test('and cannot ask the Engine about its credential does not throw the credential away', async () => {
    const storage = new MemoryStorage();
    createCredentialStore(storage).write(ada.token);
    await engine.stop();
    const { session, stored } = tab({ storage });

    await session.start();

    expect(session.state).toEqual({ status: 'anonymous', reason: 'unavailable' });
    expect(stored()).toBe(ada.token);
  });

  test('without a credential asks the other tabs, and gets the session of one that is signed in', async () => {
    const first = tab();
    await first.session.start();
    await first.session.signIn(root.token);

    const second = tab();
    await second.session.start();

    authenticatedAs(second.session, 'root', 'admin');
    expect(second.stored()).toBe(root.token);
  });

  test('without a credential, when no tab answers, shows the sign-in once the time is up', async () => {
    const { session, stored } = tab();
    const started = Date.now();

    const starting = session.start();
    expect(session.state).toEqual({ status: 'restoring' });
    await starting;

    expect(session.state).toEqual({ status: 'anonymous' });
    expect(Date.now() - started).toBeGreaterThanOrEqual(ASK_TIMEOUT - 20);
    expect(stored()).toBeNull();
  });

  test('is not answered by a tab that is not signed in', async () => {
    const idle = tab();
    await idle.session.start();

    const second = tab();
    await second.session.start();

    expect(second.session.state).toEqual({ status: 'anonymous' });
  });

  test('takes the credential of the first answer only; later answers change nothing', async () => {
    const scripted = peer();
    const { session, stored } = tab({ askTimeoutMs: 1_000 });
    const starting = session.start();
    await vi.waitFor(() => expect(scripted.heard).toContainEqual({ type: 'ask' }));

    scripted.say({ type: 'answer', credential: ada.token });
    scripted.say({ type: 'answer', credential: root.token });
    await starting;
    await settle(100);

    authenticatedAs(session, 'ada', 'developer');
    expect(stored()).toBe(ada.token);
  });

  test('gets a credential that the Engine refuses: signed out, the credential cleared, the others told', async () => {
    const scripted = peer();
    const { session, stored } = tab({ askTimeoutMs: 1_000 });
    const starting = session.start();
    await vi.waitFor(() => expect(scripted.heard).toContainEqual({ type: 'ask' }));

    scripted.say({ type: 'answer', credential: 'tok-stale' });
    await starting;

    expect(session.state).toEqual({ status: 'anonymous', reason: 'expired' });
    expect(stored()).toBeNull();
    await vi.waitFor(() => expect(scripted.heard).toContainEqual({ type: 'expired' }));
  });
});

describe('two tabs of one session', () => {
  const twoSignedIn = async () => {
    const a = tab();
    await a.session.start();
    const b = tab();
    await b.session.start();
    await a.session.signIn(ada.token);
    await vi.waitFor(() => expect(b.session.state.status).toBe('authenticated'));
    return { a, b };
  };

  test('a sign-in in one tab signs the other in, and its own storage holds the credential', async () => {
    const { a, b } = await twoSignedIn();
    authenticatedAs(b.session, 'ada', 'developer');
    expect(b.stored()).toBe(a.stored());
  });

  test('does not repeat what it was told: a tab that took the session over says nothing', async () => {
    const a = tab();
    await a.session.start();
    const b = tab();
    await b.session.start();
    const heard = overhear();

    await a.session.signIn(ada.token);
    await vi.waitFor(() => expect(b.session.state.status).toBe('authenticated'));
    await settle(100);

    expect(heard).toEqual([{ type: 'signed-in', credential: ada.token }]);
  });

  test('a sign-out in one tab signs the other out, and clears its storage', async () => {
    const { a, b } = await twoSignedIn();

    a.session.signOut();

    await vi.waitFor(() =>
      expect(b.session.state).toEqual({ status: 'anonymous', reason: 'signed-out' }),
    );
    expect(b.stored()).toBeNull();
  });

  test('a 401 in one tab signs the other out too, with the reason', async () => {
    const { a, b } = await twoSignedIn();
    engine.callers = [root];

    const response = await a.session.request('/api/v1/system');

    expect(response.status).toBe(401);
    expect(a.session.state).toEqual({ status: 'anonymous', reason: 'expired' });
    expect(a.stored()).toBeNull();
    await vi.waitFor(() =>
      expect(b.session.state).toEqual({ status: 'anonymous', reason: 'expired' }),
    );
    expect(b.stored()).toBeNull();
  });

  test('another sign-in in another tab replaces the session of this one', async () => {
    const { a, b } = await twoSignedIn();

    await b.session.signIn(root.token);

    await vi.waitFor(() => authenticatedAs(a.session, 'root', 'admin'));
    expect(a.stored()).toBe(root.token);
  });
});

describe('what can go wrong between the tabs', () => {
  test('two tabs that start at the same moment, with nobody signed in, both show the sign-in, and neither waits for the other', async () => {
    const a = tab();
    const b = tab();

    await Promise.all([a.session.start(), b.session.start()]);

    expect(a.session.state).toEqual({ status: 'anonymous' });
    expect(b.session.state).toEqual({ status: 'anonymous' });

    await a.session.signIn(ada.token);
    await vi.waitFor(() => authenticatedAs(b.session, 'ada', 'developer'));
  });

  test('two tabs that start at the same moment, with a third signed in, both get the session', async () => {
    const holder = tab();
    await holder.session.start();
    await holder.session.signIn(ada.token);

    const a = tab();
    const b = tab();
    await Promise.all([a.session.start(), b.session.start()]);

    authenticatedAs(a.session, 'ada', 'developer');
    authenticatedAs(b.session, 'ada', 'developer');
  });

  test('a sign-out that arrives while a tab asks ends in signed out, and the credential is not kept', async () => {
    const scripted = peer();
    const { session, stored } = tab({ askTimeoutMs: 1_000 });
    const starting = session.start();
    await vi.waitFor(() => expect(scripted.heard).toContainEqual({ type: 'ask' }));

    scripted.say({ type: 'signed-out' });
    await starting;
    scripted.say({ type: 'answer', credential: ada.token }); // from a tab that had not heard yet
    await settle(100);

    expect(session.state).toMatchObject({ status: 'anonymous' });
    expect(stored()).toBeNull();
  });

  test('a sign-out that follows the answer while the credential is being checked is not undone by the check', async () => {
    const scripted = peer();
    const { session, stored } = tab({ askTimeoutMs: 1_000 });
    const starting = session.start();
    await vi.waitFor(() => expect(scripted.heard).toContainEqual({ type: 'ask' }));

    scripted.say({ type: 'answer', credential: ada.token });
    scripted.say({ type: 'signed-out' });
    await starting;
    await settle(100);

    expect(session.state).toMatchObject({ status: 'anonymous' });
    expect(stored()).toBeNull();
  });

  test('a tab that signs out while another asks: the asking tab ends signed out, whoever was first', async () => {
    const holder = tab();
    await holder.session.start();
    await holder.session.signIn(ada.token);

    const asking = tab({ askTimeoutMs: 1_000 });
    const starting = asking.session.start();
    holder.session.signOut();
    await starting;
    await settle(100);

    expect(asking.session.state).toMatchObject({ status: 'anonymous' });
    expect(asking.stored()).toBeNull();
    expect(holder.stored()).toBeNull();
  });

  test('every tab closed and the browser opened again: nothing remains, and the sign-in is shown', async () => {
    const first = tab();
    await first.session.start();
    await first.session.signIn(ada.token);
    first.session.dispose();

    const reopened = tab({ storage: new MemoryStorage() });
    await reopened.session.start();

    expect(reopened.session.state).toEqual({ status: 'anonymous' });
  });

  test('a tab that is told to sign in but finds the credential refused ends signed out, not half in', async () => {
    const { session, stored } = tab();
    await session.start();
    const scripted = peer();

    scripted.say({ type: 'signed-in', credential: 'tok-stale' });

    await vi.waitFor(() =>
      expect(session.state).toEqual({ status: 'anonymous', reason: 'expired' }),
    );
    expect(stored()).toBeNull();
  });
});

describe('requests to the Engine', () => {
  test('carry the credential the method adds, to the origin of the Engine', async () => {
    const { session } = tab();
    await session.start();
    await session.signIn(ada.token);
    engine.log.length = 0;

    const response = await session.request('/api/v1/system');

    expect(response.status).toBe(200);
    expect(engine.log).toEqual([
      { path: '/api/v1/system', authorization: `Bearer ${ada.token}`, sessionCode: null },
    ]);
  });

  test('are not sent at all when nobody is signed in: they fail as a 401', async () => {
    const { session } = tab();
    await session.start();
    engine.log.length = 0;

    await expect(session.request('/api/v1/system')).rejects.toMatchObject({ status: 401 });
    expect(engine.log).toEqual([]);
  });

  test('a 401 signs out, clears the credential and tells the other tabs; the answer still reaches the caller', async () => {
    const heard = overhear();
    const { session, stored } = tab();
    await session.start();
    await session.signIn(ada.token);
    engine.callers = [root];

    const response = await session.request('/api/v1/system');

    expect(response.status).toBe(401);
    expect(session.state).toEqual({ status: 'anonymous', reason: 'expired' });
    expect(stored()).toBeNull();
    await vi.waitFor(() => expect(heard).toContainEqual({ type: 'expired' }));
  });

  test('several that fail at once with 401 clear and tell once', async () => {
    const heard = overhear();
    const { session } = tab();
    await session.start();
    await session.signIn(ada.token);
    engine.callers = [root];

    await Promise.all([1, 2, 3].map(() => session.request('/api/v1/system')));
    await settle(100);

    expect(heard.filter((m) => m.type === 'expired')).toHaveLength(1);
  });

  test('a 401 for a credential that has been replaced since does not end the new session', async () => {
    const gate = Promise.withResolvers<void>();
    let hold = false;
    const heldFetch: typeof fetch = async (input, init) => {
      const credential = new Headers(init?.headers).get('Authorization');
      if (hold && credential === `Bearer ${ada.token}`) await gate.promise;
      return fetch(input, init);
    };
    const { session, stored } = tab({ fetch: heldFetch });
    await session.start();
    await session.signIn(ada.token);

    hold = true;
    const late = session.request('/api/v1/system'); // sent with ada's token, and held on the way
    await session.signIn(root.token);
    engine.callers = [root]; // ada's token is no longer valid
    gate.resolve();

    expect((await late).status).toBe(401);
    authenticatedAs(session, 'root', 'admin');
    expect(stored()).toBe(root.token);
  });

  test('tell the connection how they went: any answer is a connection, a failure to get one is not', async () => {
    const outcomes: boolean[] = [];
    const { session } = tab({ onOutcome: (ok) => outcomes.push(ok) });
    await session.start();
    await session.signIn(ada.token);
    outcomes.length = 0;

    await session.request('/api/v1/system');
    engine.mode = 'internal-error';
    await session.request('/api/v1/system');
    await engine.stop();
    await expect(session.request('/api/v1/system')).rejects.toBeInstanceOf(ApiFailure);

    expect(outcomes).toEqual([true, false, false]);
  });
});

describe('uploads to the Engine', () => {
  const jar = new Uint8Array(300_000).fill(7);

  test('carry the credential like any request, send the bytes as they are, and give the answer of the Engine', async () => {
    const { session } = tab();
    await session.start();
    await session.signIn(ada.token);
    engine.log.length = 0;

    const response = await session.upload('/api/v1/artifacts', new Blob([jar]), {
      contentType: 'application/octet-stream',
    });

    expect(response.status).toBe(422); // the Engine reads no jar in these bytes
    expect(await response.json()).toMatchObject({ error: 'not_a_jar' });
    expect(engine.log).toEqual([
      { path: '/api/v1/artifacts', authorization: `Bearer ${ada.token}`, sessionCode: null },
    ]);
  });

  test('tell how much has been sent, up to all of it', async () => {
    const { session } = tab();
    await session.start();
    await session.signIn(ada.token);
    const told: Array<[number, number]> = [];

    await session.upload('/api/v1/artifacts', new Blob([jar]), {
      onProgress: (sent, total) => told.push([sent, total]),
    });

    expect(told.length).toBeGreaterThan(0);
    expect(told.every(([, total]) => total === jar.length)).toBe(true);
    expect(told.at(-1)).toEqual([jar.length, jar.length]);
    expect(told.map(([sent]) => sent)).toEqual([...told.map(([sent]) => sent)].sort((a, b) => a - b));
  });

  test('can be cancelled: the call ends as aborted, and the session is as it was', async () => {
    const { session } = tab();
    await session.start();
    await session.signIn(ada.token);
    engine.mode = 'hang';
    const abort = new AbortController();

    const sending = session.upload('/api/v1/artifacts', new Blob([jar]), { signal: abort.signal });
    await vi.waitFor(() => expect(engine.requests).toBeGreaterThan(0));
    abort.abort();

    await expect(sending).rejects.toMatchObject({ name: 'AbortError' });
    authenticatedAs(session, 'ada', 'developer');
  });

  test('that were cancelled before they began send nothing', async () => {
    const { session } = tab();
    await session.start();
    await session.signIn(ada.token);
    engine.log.length = 0;
    const abort = new AbortController();
    abort.abort();

    await expect(
      session.upload('/api/v1/artifacts', new Blob([jar]), { signal: abort.signal }),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(engine.log).toEqual([]);
  });

  test('are not sent at all when nobody is signed in: they fail as a 401', async () => {
    const { session } = tab();
    await session.start();
    engine.log.length = 0;

    await expect(session.upload('/api/v1/artifacts', new Blob([jar]))).rejects.toMatchObject({
      status: 401,
    });
    expect(engine.log).toEqual([]);
  });

  test('a 401 signs out like any other request, and the answer still reaches the caller', async () => {
    const { session, stored } = tab();
    await session.start();
    await session.signIn(ada.token);
    engine.callers = [root];

    const response = await session.upload('/api/v1/artifacts', new Blob([jar]));

    expect(response.status).toBe(401);
    expect(session.state).toEqual({ status: 'anonymous', reason: 'expired' });
    expect(stored()).toBeNull();
  });

  test('when the Engine cannot be reached fail as no answer, and tell the connection', async () => {
    const outcomes: boolean[] = [];
    const { session } = tab({ onOutcome: (ok) => outcomes.push(ok) });
    await session.start();
    await session.signIn(ada.token);
    outcomes.length = 0;
    await engine.stop();

    await expect(session.upload('/api/v1/artifacts', new Blob([jar]))).rejects.toMatchObject({
      status: 0,
    });
    expect(outcomes).toEqual([false]);
  });
});

test('a session that is disposed leaves the channel: it no longer answers or follows', async () => {
  const holder = tab();
  await holder.session.start();
  await holder.session.signIn(ada.token);
  holder.session.dispose();

  const asking = tab();
  await asking.session.start();

  expect(asking.session.state).toEqual({ status: 'anonymous' });
  expect(SESSION_STORAGE_KEY).toBe('runline.session');
});

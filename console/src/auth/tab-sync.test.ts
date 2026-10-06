import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTabSync, type TabMessage, type TabSync } from './tab-sync';

// Two tabs of one origin, each with its own channel object on the same name: the browser's real
// BroadcastChannel (Node's, which follows the same specification) carries the messages.
const tabs: TabSync[] = [];
const open = (name?: string) => {
  const tab = createTabSync(name);
  tabs.push(tab);
  return tab;
};
afterEach(() => {
  tabs.splice(0).forEach((tab) => tab.close());
});

const collect = (tab: TabSync) => {
  const received: TabMessage[] = [];
  tab.subscribe((message) => received.push(message));
  return received;
};

describe('the channel between the tabs', () => {
  test.each<TabMessage>([
    { type: 'signed-in', credential: 'c-1' },
    { type: 'signed-out' },
    { type: 'expired' },
    { type: 'ask' },
    { type: 'answer', credential: 'c-2' },
  ])('carries %j to the other tab', async (message) => {
    const sender = open();
    const receiver = open();
    const received = collect(receiver);

    sender.post(message);

    await vi.waitFor(() => expect(received).toEqual([message]));
  });

  test('does not give a tab its own messages', async () => {
    const sender = open();
    const other = open();
    const own = collect(sender);
    const heard = collect(other);

    sender.post({ type: 'signed-out' });
    await vi.waitFor(() => expect(heard.length).toBe(1));

    expect(own).toEqual([]);
  });

  test('drops what is not one of its messages, and a message with more than it needs is cut down', async () => {
    const receiver = open();
    const received = collect(receiver);
    const raw = new BroadcastChannel('runline.session');
    raw.postMessage('a string');
    raw.postMessage({ type: 'something-else' });
    raw.postMessage({ type: 'signed-in' }); // no credential
    raw.postMessage({ type: 'signed-in', credential: 42 });
    raw.postMessage({ type: 'signed-in', credential: 'c-1', name: 'Ada', role: 'admin' });
    await vi.waitFor(() => expect(received.length).toBe(1));
    raw.close();

    expect(received).toEqual([{ type: 'signed-in', credential: 'c-1' }]);
  });

  test('keeps to its own channel name', async () => {
    const receiver = open();
    const received = collect(receiver);
    const stranger = open('another.channel');

    stranger.post({ type: 'signed-out' });
    const sentinel = open();
    sentinel.post({ type: 'expired' });

    await vi.waitFor(() => expect(received).toEqual([{ type: 'expired' }]));
  });

  test('stops giving messages to a listener that unsubscribed', async () => {
    const sender = open();
    const receiver = open();
    const received: TabMessage[] = [];
    const unsubscribe = receiver.subscribe((m) => received.push(m));
    const sentinel = collect(receiver);

    unsubscribe();
    sender.post({ type: 'signed-out' });
    await vi.waitFor(() => expect(sentinel.length).toBe(1));

    expect(received).toEqual([]);
  });
});

// The channel between the tabs of the Console (ADR-017). A BroadcastChannel reaches the tabs of
// the same origin only. It carries the events of the session and the opaque credential, nothing
// else: a message that is not one of these is dropped, and a message is cut down to its own fields.

export type TabMessage =
  /** The user signed in here; the others take the session over. */
  | { type: 'signed-in'; credential: string }
  /** The user signed out here; the others sign out too. */
  | { type: 'signed-out' }
  /** The Engine refused the credential (401) and it was cleared here; the others clear it too. */
  | { type: 'expired' }
  /** A tab that has no credential asks whether another has one. */
  | { type: 'ask' }
  /** A tab that has a credential gives it to the one that asked. */
  | { type: 'answer'; credential: string };

export const CHANNEL_NAME = 'runline.session';

function parse(data: unknown): TabMessage | null {
  if (typeof data !== 'object' || data === null) return null;
  const { type, credential } = data as Record<string, unknown>;
  switch (type) {
    case 'signed-out':
    case 'expired':
    case 'ask':
      return { type };
    case 'signed-in':
    case 'answer':
      return typeof credential === 'string' ? { type, credential } : null;
    default:
      return null;
  }
}

export function createTabSync(name: string = CHANNEL_NAME) {
  const channel = new BroadcastChannel(name);
  return {
    post: (message: TabMessage) => channel.postMessage(message),
    /** Calls [listener] for every message of the other tabs; returns the way to stop. */
    subscribe(listener: (message: TabMessage) => void): () => void {
      const receive = (event: MessageEvent) => {
        const message = parse(event.data);
        if (message) listener(message);
      };
      channel.addEventListener('message', receive);
      return () => channel.removeEventListener('message', receive);
    },
    close: () => channel.close(),
  };
}

export type TabSync = ReturnType<typeof createTabSync>;

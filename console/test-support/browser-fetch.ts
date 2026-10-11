// The browser around `fetch` in the tests of the Console. Node's `fetch` has no cookies and sends no
// `Origin`; a browser has a jar of cookies that goes along when a call asks for credentials, takes
// the cookies an answer sets, and says where a change comes from. The Console's calls to an Engine
// with login depend on all three, so the tests give the session this `fetch`. It stands for the
// browser, not for the Engine: the Engine is the real thing or the Fake Engine, over HTTP.

export interface TestBrowser {
  fetch: typeof fetch;
  setCookie(name: string, value: string): void;
  cookie(name: string): string | undefined;
}

/** [pageOrigin] is where the Console was served from: the `Origin` of its changes. */
export function createBrowser(pageOrigin: string): TestBrowser {
  const jar = new Map<string, string>();

  const take = (setCookie: string) => {
    const [pair, ...attributes] = setCookie.split(';').map((part) => part.trim());
    const equals = pair.indexOf('=');
    if (equals < 0) return;
    const name = pair.slice(0, equals);
    const value = pair.slice(equals + 1);
    const ended = attributes.some((a) => /^max-age=0$/i.test(a));
    if (ended || value === '') jar.delete(name);
    else jar.set(name, value);
  };

  return {
    async fetch(input, init) {
      const headers = new Headers(init?.headers);
      const method = (init?.method ?? 'GET').toUpperCase();
      if (init?.credentials === 'include' && jar.size > 0) {
        headers.set('Cookie', [...jar].map(([name, value]) => `${name}=${value}`).join('; '));
      }
      if (method !== 'GET' && method !== 'HEAD') headers.set('Origin', pageOrigin);
      const response = await fetch(input, { ...init, headers });
      if (init?.credentials === 'include') {
        for (const line of response.headers.getSetCookie()) take(line);
      }
      return response;
    },
    setCookie: (name, value) => void jar.set(name, value),
    cookie: (name) => jar.get(name),
  };
}

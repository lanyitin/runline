import { createRawSnippet, flushSync } from 'svelte';
import { afterEach, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import Link from './Link.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const label = createRawSnippet(() => ({ render: () => '<span>go</span>' }));

const mountLink = async (href: string) => {
  app = await createTestApp({ path: '/' });
  return app.mount(Link, { href, children: label }).querySelector('a')!;
};

const click = (a: HTMLAnchorElement, init: MouseEventInit = {}) => {
  const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ...init });
  a.dispatchEvent(event);
  flushSync();
  return event;
};

test('is a real link to the path, for opening in a new tab and for assistive technology', async () => {
  const a = await mountLink('/runs');
  expect(a.getAttribute('href')).toBe('/runs');
});

test('a plain click goes there through the router, without loading a page', async () => {
  const a = await mountLink('/runs');
  const event = click(a);
  expect(event.defaultPrevented).toBe(true);
  expect(app.context.router.path).toBe('/runs');
  expect(location.pathname).toBe('/runs');
});

test.each([{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { button: 1 }])(
  'a click with %o is left to the browser',
  async (init) => {
    const a = await mountLink('/runs');
    const event = click(a, init);
    expect(event.defaultPrevented).toBe(false);
    expect(app.context.router.path).toBe('/');
  },
);

test('marks the link of the current page for assistive technology', async () => {
  app = await createTestApp({ path: '/runs' });
  const view = app.mount(Link, { href: '/runs', children: label });
  expect(view.querySelector('a')!.getAttribute('aria-current')).toBe('page');
});

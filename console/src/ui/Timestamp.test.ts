import { flushSync } from 'svelte';
import { afterEach, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import Timestamp from './Timestamp.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ISO = '2026-10-05T14:30:00Z';

test('shows the time locally, in the language, with the UTC value of the API as the tooltip', async () => {
  app = await createTestApp({ languages: ['en'] });
  const time = app.mount(Timestamp, { iso: ISO }).querySelector('time')!;

  expect(time.textContent).toMatch(/Oct [56], 2026/);
  expect(time.title).toBe(ISO);
  expect(time.getAttribute('datetime')).toBe(ISO);
});

test('follows a change of language at once', async () => {
  app = await createTestApp({ languages: ['en'] });
  const time = app.mount(Timestamp, { iso: ISO }).querySelector('time')!;

  app.context.i18n.setLocale('zh-TW');
  flushSync();

  expect(time.textContent).toMatch(/2026年10月[56]日/);
});

test('can show how long ago it was, the exact time kept in the tooltip', async () => {
  app = await createTestApp({ languages: ['en'] });
  const now = new Date('2026-10-05T14:33:00Z');
  const time = app.mount(Timestamp, { iso: ISO, relativeTo: now }).querySelector('time')!;

  expect(time.textContent).toBe('3 minutes ago');
  expect(time.title).toContain(ISO);
});

import { flushSync } from 'svelte';
import { afterEach, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import LanguageSwitcher from './LanguageSwitcher.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const buttons = (view: HTMLElement) => [...view.querySelectorAll<HTMLButtonElement>('button')];

test('offers both languages by their own names, the current one pressed', async () => {
  app = await createTestApp({ languages: ['zh-TW'] });
  const view = app.mount(LanguageSwitcher);

  expect(buttons(view).map((b) => b.textContent?.trim())).toEqual(['繁體中文', 'English']);
  expect(buttons(view).map((b) => b.getAttribute('aria-pressed'))).toEqual(['true', 'false']);
  expect(buttons(view).map((b) => b.lang)).toEqual(['zh-TW', 'en']);
  expect(view.querySelector('[role="group"]')?.getAttribute('aria-label')).toBe('語言');
});

test('a click changes the language at once: the page, the lang of the document, the choice kept', async () => {
  app = await createTestApp({ languages: ['zh-TW'] });
  const view = app.mount(LanguageSwitcher);
  const marker = document.createElement('i');
  document.body.append(marker);

  buttons(view)[1].click();
  flushSync();

  expect(app.context.i18n.locale).toBe('en');
  expect(document.documentElement.lang).toBe('en');
  expect(localStorage.getItem('runline.locale')).toBe('en');
  expect(buttons(view).map((b) => b.getAttribute('aria-pressed'))).toEqual(['false', 'true']);
  expect(view.querySelector('[role="group"]')?.getAttribute('aria-label')).toBe('Language');
  expect(marker.isConnected).toBe(true); // nothing was reloaded or rebuilt around it
});

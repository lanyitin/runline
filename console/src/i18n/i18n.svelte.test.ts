import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { createI18n, LOCALE_STORAGE_KEY } from './i18n.svelte';

const catalogs = {
  en: { hello: 'Hello', only: 'Only English' },
  'zh-TW': { hello: '哈囉' },
};

let i18n: ReturnType<typeof createI18n>;

const create = (languages: string[]) =>
  (i18n = createI18n({ catalogs, languages, storage: localStorage, root: document.documentElement }));

beforeEach(() => {
  localStorage.clear();
  document.documentElement.lang = '';
});
afterEach(() => i18n?.dispose());

describe('the language at the start', () => {
  test('is the browser preference when nothing was chosen before', () => {
    create(['en-US']);
    expect(i18n.locale).toBe('en');
    expect(document.documentElement.lang).toBe('en');
  });

  test('is zh-TW when the browser prefers neither language', () => {
    create(['ja']);
    expect(i18n.locale).toBe('zh-TW');
    expect(document.documentElement.lang).toBe('zh-TW');
  });

  test('is the earlier choice, before the browser preference', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'zh-TW');
    create(['en-US']);
    expect(i18n.locale).toBe('zh-TW');
  });
});

describe('changing the language', () => {
  test('changes the texts at once, the lang of the page, and is remembered', () => {
    create(['en']);
    expect(i18n.translate('hello')).toBe('Hello');

    i18n.setLocale('zh-TW');

    expect(i18n.locale).toBe('zh-TW');
    expect(i18n.translate('hello')).toBe('哈囉');
    expect(document.documentElement.lang).toBe('zh-TW');
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-TW');
  });

  test('a text the language lacks is the English one', () => {
    create(['zh-TW']);
    expect(i18n.translate('only')).toBe('Only English');
  });

  test('a choice made in another tab is taken over, and not written again', () => {
    create(['zh-TW']);

    // What the browser does in this tab when another tab of the origin changed the storage.
    localStorage.setItem(LOCALE_STORAGE_KEY, 'en');
    window.dispatchEvent(new StorageEvent('storage', { key: LOCALE_STORAGE_KEY, newValue: 'en' }));

    expect(i18n.locale).toBe('en');
    expect(document.documentElement.lang).toBe('en');
  });

  test('a change of some other key of the storage is none of its business', () => {
    create(['zh-TW']);
    window.dispatchEvent(new StorageEvent('storage', { key: 'other', newValue: 'en' }));
    expect(i18n.locale).toBe('zh-TW');
  });

  test('a value in the storage that is not a language is ignored', () => {
    create(['zh-TW']);
    window.dispatchEvent(new StorageEvent('storage', { key: LOCALE_STORAGE_KEY, newValue: 'xx' }));
    expect(i18n.locale).toBe('zh-TW');
  });
});

test('after dispose a change of another tab is no longer heard', () => {
  create(['zh-TW']);
  i18n.dispose();
  window.dispatchEvent(new StorageEvent('storage', { key: LOCALE_STORAGE_KEY, newValue: 'en' }));
  expect(i18n.locale).toBe('zh-TW');
});

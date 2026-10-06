import { describe, expect, test } from 'vitest';
import { createTranslator } from './translator';

const catalogs = {
  en: {
    'greeting': 'Hello, {name}',
    'runs.count': '{count, plural, one {# run} other {# runs}}',
    'only.english': 'Only in English',
  },
  'zh-TW': {
    'greeting': '你好，{name}',
    'runs.count': '{count, plural, other {# 個 run}}',
  },
};

describe('createTranslator', () => {
  test('inserts parameters', () => {
    expect(createTranslator(catalogs, 'en')('greeting', { name: 'Ada' })).toBe('Hello, Ada');
    expect(createTranslator(catalogs, 'zh-TW')('greeting', { name: 'Ada' })).toBe('你好，Ada');
  });

  test('chooses the plural form of the language', () => {
    const en = createTranslator(catalogs, 'en');
    expect(en('runs.count', { count: 1 })).toBe('1 run');
    expect(en('runs.count', { count: 2 })).toBe('2 runs');
    expect(createTranslator(catalogs, 'zh-TW')('runs.count', { count: 1 })).toBe('1 個 run');
  });

  test('formats numbers in a message by the language', () => {
    const t = createTranslator({ en: { n: '{count, number}' }, 'zh-TW': {} }, 'en');
    expect(t('n', { count: 1234567 })).toBe('1,234,567');
  });

  test('a key the language has no text for falls back to English', () => {
    expect(createTranslator(catalogs, 'zh-TW')('only.english')).toBe('Only in English');
  });

  test('a key that no language has is shown as the key, not as a failure', () => {
    expect(createTranslator(catalogs, 'en')('no.such.key')).toBe('no.such.key');
  });

  test('a parameter a message needs but did not get does not throw', () => {
    expect(() => createTranslator(catalogs, 'en')('greeting')).not.toThrow();
  });

  test('says whether a key has a text in the language or in English', () => {
    const t = createTranslator(catalogs, 'zh-TW');
    expect(t.has('greeting')).toBe(true);
    expect(t.has('only.english')).toBe(true);
    expect(t.has('no.such.key')).toBe(false);
  });
});

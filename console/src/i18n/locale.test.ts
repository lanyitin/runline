import { describe, expect, test } from 'vitest';
import { DEFAULT_LOCALE, localeOfLanguageTag, resolveLocale } from './locale';

describe('localeOfLanguageTag', () => {
  test.each(['zh', 'zh-TW', 'zh-HK', 'zh-Hant', 'zh-CN', 'ZH-tw'])(
    'the zh family %s is zh-TW',
    (tag) => expect(localeOfLanguageTag(tag)).toBe('zh-TW'),
  );

  test.each(['en', 'en-US', 'en-GB', 'EN'])('the en family %s is en', (tag) =>
    expect(localeOfLanguageTag(tag)).toBe('en'),
  );

  test.each(['ja', 'fr-FR', '', 'zhx', 'english'])('%s is none of the two', (tag) =>
    expect(localeOfLanguageTag(tag)).toBeNull(),
  );
});

describe('resolveLocale', () => {
  test('a stored choice comes before the browser preference', () => {
    expect(resolveLocale({ stored: 'en', preferred: ['zh-TW'] })).toBe('en');
    expect(resolveLocale({ stored: 'zh-TW', preferred: ['en-US'] })).toBe('zh-TW');
  });

  test('without a stored choice the first browser language that is one of the two wins', () => {
    expect(resolveLocale({ stored: null, preferred: ['ja', 'en-GB', 'zh-TW'] })).toBe('en');
    expect(resolveLocale({ stored: null, preferred: ['fr', 'zh-HK'] })).toBe('zh-TW');
  });

  test('a stored value that is not a locale is ignored', () => {
    expect(resolveLocale({ stored: 'klingon', preferred: ['en'] })).toBe('en');
  });

  test('nothing usable gives the default, zh-TW', () => {
    expect(DEFAULT_LOCALE).toBe('zh-TW');
    expect(resolveLocale({ stored: null, preferred: [] })).toBe('zh-TW');
    expect(resolveLocale({ stored: null, preferred: ['ja', 'fr'] })).toBe('zh-TW');
  });
});

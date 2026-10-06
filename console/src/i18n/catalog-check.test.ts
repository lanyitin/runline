import { describe, expect, test } from 'vitest';
import { checkCatalogs } from './catalog-check';

const good = {
  en: { a: 'Hello {name}', b: '{n, plural, one {# run} other {# runs}}' },
  'zh-TW': { a: '你好 {name}', b: '{n, plural, other {# 個 run}}' },
};

describe('checkCatalogs', () => {
  test('catalogs with the same keys and parameters have no problems', () => {
    expect(checkCatalogs(good)).toEqual([]);
  });

  test('a key missing in a language is reported with the key and the language', () => {
    const problems = checkCatalogs({ ...good, 'zh-TW': { a: '你好 {name}' } });
    expect(problems).toHaveLength(1);
    expect(problems[0]).toContain('zh-TW');
    expect(problems[0]).toContain('"b"');
    expect(problems[0]).toMatch(/missing/i);
  });

  test('a key that only one language has is reported for the other, whichever it is', () => {
    const problems = checkCatalogs({ ...good, en: { ...good.en, c: 'Extra' } });
    expect(problems).toHaveLength(1);
    expect(problems[0]).toContain('zh-TW');
    expect(problems[0]).toContain('"c"');
  });

  test('a message that is not valid ICU is reported', () => {
    const problems = checkCatalogs({ ...good, en: { ...good.en, a: 'Hello {name' } });
    expect(problems.some((p) => p.includes('"a"') && p.includes('en'))).toBe(true);
  });

  test('a message whose parameters differ between languages is reported', () => {
    const problems = checkCatalogs({ ...good, 'zh-TW': { ...good['zh-TW'], a: '你好 {who}' } });
    expect(problems.some((p) => p.includes('"a"') && /parameter/i.test(p))).toBe(true);
  });

  test('parameters inside plural branches count', () => {
    const problems = checkCatalogs({
      en: { x: '{n, plural, one {{who} ran} other {{who} ran #}}' },
      'zh-TW': { x: '{n, plural, other {執行了 #}}' },
    });
    expect(problems.some((p) => p.includes('"x"') && /parameter/i.test(p))).toBe(true);
  });
});

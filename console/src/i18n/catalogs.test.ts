import { describe, expect, test } from 'vitest';
import { checkCatalogs } from './catalog-check';
import { catalogs } from './catalogs';

describe('the catalogs of the Console', () => {
  test('zh-TW and en have the same keys and the same parameters, in valid ICU', () => {
    expect(checkCatalogs(catalogs)).toEqual([]);
  });

  test('have no empty text', () => {
    for (const [locale, texts] of Object.entries(catalogs)) {
      for (const [key, text] of Object.entries(texts)) {
        expect(text.trim(), `${locale}: ${key}`).not.toBe('');
      }
    }
  });
});

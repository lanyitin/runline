import { IntlMessageFormat } from 'intl-messageformat';
import { FALLBACK_LOCALE, type Locale } from './locale';

/** The text of every message, per language, by its stable key. Messages are ICU MessageFormat. */
export type Catalogs = Record<Locale, Record<string, string>>;
export type Params = Record<string, string | number>;
export interface Translate {
  (key: string, params?: Params): string;
  /** Whether the language, or English, has a text for the key. */
  has(key: string): boolean;
}

/**
 * A translator for one language. A key that the language has no text for gets the English text
 * (ADR-015); a key that no language has is shown as the key itself: a missing translation is seen,
 * and does not break the screen.
 */
export function createTranslator(catalogs: Catalogs, locale: Locale): Translate {
  const formats = new Map<string, IntlMessageFormat>();

  const formatOf = (key: string): IntlMessageFormat | null => {
    const cached = formats.get(key);
    if (cached) return cached;
    const own = catalogs[locale][key];
    const [text, language] =
      own !== undefined ? [own, locale] : [catalogs[FALLBACK_LOCALE][key], FALLBACK_LOCALE];
    if (text === undefined) return null;
    const format = new IntlMessageFormat(text, language);
    formats.set(key, format);
    return format;
  };

  const translate = (key: string, params?: Params): string => {
    const format = formatOf(key);
    if (!format) return key;
    try {
      return String(format.format(params));
    } catch {
      // A parameter the message needs was not given: show the key, not a broken screen.
      return key;
    }
  };
  return Object.assign(translate, {
    has: (key: string) => key in catalogs[locale] || key in catalogs[FALLBACK_LOCALE],
  });
}

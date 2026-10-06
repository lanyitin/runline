// Which language the Console speaks (ADR-015): the earlier choice, then the browser's preference,
// then zh-TW. Pure functions; reading and writing the browser's storage is in locale-store.

export const LOCALES = ['zh-TW', 'en'] as const;
export type Locale = (typeof LOCALES)[number];

export const DEFAULT_LOCALE: Locale = 'zh-TW';
/** The language a message falls back to when the current one has no text for it. */
export const FALLBACK_LOCALE: Locale = 'en';

export function isLocale(value: string | null | undefined): value is Locale {
  return (LOCALES as readonly string[]).includes(value ?? '');
}

/** zh family (zh, zh-HK, zh-Hant, ...) is zh-TW, en family is en, anything else is neither. */
export function localeOfLanguageTag(tag: string): Locale | null {
  const language = tag.toLowerCase().split('-')[0];
  if (language === 'zh') return 'zh-TW';
  if (language === 'en') return 'en';
  return null;
}

export function resolveLocale(input: {
  stored: string | null;
  preferred: readonly string[];
}): Locale {
  if (isLocale(input.stored)) return input.stored;
  for (const tag of input.preferred) {
    const locale = localeOfLanguageTag(tag);
    if (locale) return locale;
  }
  return DEFAULT_LOCALE;
}

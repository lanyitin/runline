import { isLocale, resolveLocale, type Locale } from './locale';
import { createTranslator, type Catalogs, type Params, type Translate } from './translator';

/** Where the choice of language is kept: localStorage, so that every tab of the origin shares it. */
export const LOCALE_STORAGE_KEY = 'runline.locale';

export interface I18nOptions {
  catalogs: Catalogs;
  /** The browser's languages in order of preference (`navigator.languages`). */
  languages: readonly string[];
  /** The storage of the choice; the language is a preference, not a secret (ADR-015). */
  storage: Storage;
  /** The element whose `lang` follows the language (`<html>`). */
  root: HTMLElement;
}

/**
 * The language of the Console and the texts in it. Changing the language takes effect at once,
 * everywhere the texts are read (the state is reactive), with no reload; the choice is stored for
 * the next visit and for the other tabs, which follow it.
 */
export function createI18n<Key extends string = string>(options: I18nOptions) {
  const { catalogs, storage, root } = options;
  const translators = {
    'zh-TW': createTranslator(catalogs, 'zh-TW'),
    en: createTranslator(catalogs, 'en'),
  } satisfies Record<Locale, Translate>;

  const initial = resolveLocale({
    stored: storage.getItem(LOCALE_STORAGE_KEY),
    preferred: options.languages,
  });
  let locale = $state<Locale>(initial);
  root.lang = initial;

  const follow = (event: StorageEvent) => {
    if (event.key === LOCALE_STORAGE_KEY && isLocale(event.newValue)) {
      locale = event.newValue;
      root.lang = locale;
    }
  };
  window.addEventListener('storage', follow);

  return {
    get locale(): Locale {
      return locale;
    },
    /** Looks the key up in the current language, at the time of the call (so it is reactive). */
    translate: Object.assign(
      (key: string, params?: Params) => translators[locale](key, params),
      { has: (key: string) => translators[locale].has(key) },
    ) satisfies Translate,
    /** `translate` for the keys of the catalogs only: a key that is not there fails the type check. */
    t: (key: Key, params?: Params): string => translators[locale](key, params),
    setLocale(next: Locale) {
      locale = next;
      root.lang = next;
      storage.setItem(LOCALE_STORAGE_KEY, next);
    },
    dispose() {
      window.removeEventListener('storage', follow);
    },
  };
}

export type I18n<Key extends string = string> = ReturnType<typeof createI18n<Key>>;

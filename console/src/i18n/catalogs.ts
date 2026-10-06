import en from './locales/en.json' with { type: 'json' };
import zhTW from './locales/zh-TW.json' with { type: 'json' };
import type { Catalogs } from './translator.ts';

export const catalogs: Catalogs = { en, 'zh-TW': zhTW };

/** Every key the Console has a text for. A key that is not in en.json is a type error. */
export type MessageKey = keyof typeof en;

// Both directions must assign: a key in one file and not in the other fails the type check, so
// that a missing key is found without running anything (and checkCatalogs finds it in the build).
const _zhTWHasEveryKeyOfEn: Record<MessageKey, string> = zhTW;
const _enHasEveryKeyOfZhTW: Record<keyof typeof zhTW, string> = en;
void [_zhTWHasEveryKeyOfEn, _enHasEveryKeyOfZhTW];

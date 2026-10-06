import {
  isArgumentElement,
  isDateElement,
  isNumberElement,
  isPluralElement,
  isSelectElement,
  isTagElement,
  isTimeElement,
  parse,
  type MessageFormatElement,
} from '@formatjs/icu-messageformat-parser';
import { LOCALES } from './locale.ts';
import type { Catalogs } from './translator.ts';

/** The names of the parameters a message takes, branches of plural and select included. */
function parametersOf(elements: MessageFormatElement[], into = new Set<string>()): Set<string> {
  for (const element of elements) {
    if (
      isArgumentElement(element) ||
      isNumberElement(element) ||
      isDateElement(element) ||
      isTimeElement(element)
    ) {
      into.add(element.value);
    } else if (isPluralElement(element) || isSelectElement(element)) {
      into.add(element.value);
      for (const option of Object.values(element.options)) parametersOf(option.value, into);
    } else if (isTagElement(element)) {
      parametersOf(element.children, into);
    }
  }
  return into;
}

/**
 * What is wrong with the catalogs, as lines of text for a person: a key one language lacks, a
 * message that is not valid ICU, a message whose parameters differ between languages. An empty list
 * means the catalogs agree (ADR-015: a missing key fails the local build).
 */
export function checkCatalogs(catalogs: Catalogs): string[] {
  const problems: string[] = [];
  const keys = new Set(LOCALES.flatMap((locale) => Object.keys(catalogs[locale])));
  const parameters = new Map<string, Map<string, string>>();

  for (const locale of LOCALES) {
    for (const key of [...keys].sort()) {
      const text = catalogs[locale][key];
      if (text === undefined) {
        problems.push(`${locale}: missing key "${key}"`);
        continue;
      }
      try {
        const names = [...parametersOf(parse(text))].sort().join(', ');
        parameters.set(key, (parameters.get(key) ?? new Map()).set(locale, names));
      } catch (error) {
        problems.push(`${locale}: "${key}" is not valid ICU MessageFormat (${String(error)})`);
      }
    }
  }

  for (const [key, byLocale] of parameters) {
    if (new Set(byLocale.values()).size > 1) {
      const shown = [...byLocale].map(([locale, names]) => `${locale}: {${names}}`).join('; ');
      problems.push(`"${key}" has different parameters in the languages (${shown})`);
    }
  }
  return problems;
}

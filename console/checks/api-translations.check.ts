// `npm run check:api-doc`: every error code and enumeration value that 08-api.md documents has a
// text in both languages (ADR-015: the document is the single source of truth, in the spirit of
// ApiDocumentationTest). A gap fails with the list of what is missing. Gradle runs it as the task
// consoleApiDocCheck, with the document as an input, so that editing 08-api.md reruns it.

import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import {
  documentedErrorCodes,
  documentedReasonKinds,
  documentedRunStates,
} from '../src/i18n/api-doc';
import { catalogs } from '../src/i18n/catalogs';
import { LOCALES } from '../src/i18n/locale';

const path = process.env.RUNLINE_API_DOC;
if (!path) throw new Error('RUNLINE_API_DOC is not set: it is the path of 08-api.md');
const document = readFileSync(path, 'utf8');

/** The keys that have no text of their own in a language (English is not a stand-in here). */
const missingIn = (locale: (typeof LOCALES)[number], keys: string[]) =>
  keys.filter((key) => !(key in catalogs[locale]));

describe('08-api.md and the translations', () => {
  const codes = documentedErrorCodes(document);
  const states = documentedRunStates(document);
  const kinds = documentedReasonKinds(document);

  test('the document yields what the checks need, so that a gap cannot hide in a parse failure', () => {
    expect(codes.length).toBeGreaterThanOrEqual(30);
    expect(states).toContain('SUCCEEDED');
    expect(kinds).toContain('NOT_ALLOW_LISTED');
  });

  test.each(LOCALES)('every error code has a text in %s', (locale) => {
    expect(missingIn(locale, codes.map((code) => `error.${code}`))).toEqual([]);
  });

  test.each(LOCALES)('every run state has a text in %s', (locale) => {
    expect(missingIn(locale, states.map((state) => `runState.${state}`))).toEqual([]);
  });

  test.each(LOCALES)('every reasons[].kind has a text in %s', (locale) => {
    expect(missingIn(locale, kinds.map((kind) => `reasonKind.${kind}`))).toEqual([]);
  });

  test.each(LOCALES)('the verdicts, the sources of a run and the log streams have texts in %s', (locale) => {
    const keys = [
      ...['SAFE', 'UNSAFE'].map((v) => `verdict.${v}`),
      ...['MANUAL', 'TRIGGER'].map((v) => `runSource.${v}`),
      ...['STDOUT', 'STDERR'].map((v) => `logStream.${v}`),
    ];
    expect(missingIn(locale, keys)).toEqual([]);
  });
});

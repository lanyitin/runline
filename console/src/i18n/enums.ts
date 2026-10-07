import type { Translate } from './translator';

/** The enumerations of the API that the Console shows as words, by the group of their keys. */
export type EnumGroup =
  | 'runState'
  | 'verdict'
  | 'reasonKind'
  | 'runSource'
  | 'logStream'
  | 'triggerKind'
  | 'firingOutcome'
  | 'allowAction'
  | 'allowKind'
  | 'resourceType'
  | 'secretStatus'
  | 'checkFailure'
  | 'secretType'
  | 'keystoreStatus';

/**
 * The words for a value of an enumeration of the API (`state`, `verdict`, `reasons[].kind`, ...),
 * in the language of the screen. A value the Console does not know yet (a newer Engine) is shown
 * as it came, never as a key.
 */
export function enumLabel(t: Translate, group: EnumGroup, value: string): string {
  const key = `${group}.${value}`;
  return t.has(key) ? t(key) : value;
}

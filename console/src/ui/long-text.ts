/** A default longer than this does not read in a line of the form (about its width in monospace). */
export const LONG_TEXT_LENGTH = 80;

/**
 * Whether a default value is read and edited as a text of several lines rather than a line: it has a
 * line break, or is longer than a line of the form. Decided by the value itself; the Engine's
 * declaration of a parameter does not say.
 */
export function isLongText(value: string | null): boolean {
  return value !== null && (value.includes('\n') || value.length > LONG_TEXT_LENGTH);
}

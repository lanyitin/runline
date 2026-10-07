// What the Console knows of each type of shared resource (ADR-019: a closed set): which types it has
// a form for, which refer to a secret in the keystore, and which of the settings a card shows as
// its summary. A type the Console does not know (a newer Engine) has no summary and no form; its
// card still shows the rest. The forms and the use of the other types come with WI-50, as entries
// here and not as changes to the pages.

/** The types the Console can define, in the order it offers them. */
export const formTypes: string[] = ['counter'];

/** The types whose settings refer to a secret of the keystore by its alias. */
const SECRET_TYPES = ['jdbc-pool', 'openai-compatible'];

export const takesSecret = (type: string): boolean => SECRET_TYPES.includes(type);

/** The settings, not secret, that say what a resource of each type is: its address, path or account. */
const SUMMARY_FIELDS: Record<string, string[]> = {
  counter: [],
  file: ['path'],
  'openai-compatible': ['baseUrl', 'organization', 'project'],
  'jdbc-pool': ['kind', 'host', 'port', 'database', 'username'],
};

/** One line of a summary: the name of the setting, and its value as text. */
export interface SettingLine {
  field: string;
  value: string;
}

/**
 * The summary of [settings] for a resource of [type]: the fields that say what it is, in order,
 * each that is there and is a plain value. Everything here is something the admin wrote; the Engine
 * never puts a secret in the settings.
 */
export function settingsSummary(type: string, settings: Record<string, unknown>): SettingLine[] {
  return (SUMMARY_FIELDS[type] ?? []).flatMap((field) => {
    const value = settings[field];
    return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
      ? [{ field, value: String(value) }]
      : [];
  });
}

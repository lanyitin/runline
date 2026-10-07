// What the Console knows of each type of shared resource (ADR-019: a closed set): which types it has
// a form for (the fields are in resource-forms.ts), which refer to a secret in the keystore, which
// of the settings a card shows as its summary, and the use of its own a card shows. A type the
// Console does not know (a newer Engine) has no summary and no form; its card still shows the rest.

/** The types the Console can define, in the order it offers them. */
export const formTypes: string[] = ['counter', 'file', 'jdbc-pool', 'openai-compatible'];

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

/** One measure of the use of a resource: how much of it, out of the limit of its entity. */
export interface UsageLine {
  /** `activeConnections`, `inFlightRequests`, or what a newer Engine names. */
  measure: string;
  count: number;
  /** The capacity times what one holder may do at once; null when the Engine does not say. */
  limit: number | null;
}

/**
 * The use of the type's own (08-api `usage`): the connections that runs of a `jdbc-pool` hold, the
 * requests in flight to an `openai-compatible` service; nothing for the types that have none.
 */
export function usageLines(resource: {
  usage: Record<string, number> | null;
  concurrencyLimit: number | null;
}): UsageLine[] {
  return Object.entries(resource.usage ?? {}).map(([measure, count]) => ({
    measure,
    count,
    limit: resource.concurrencyLimit,
  }));
}

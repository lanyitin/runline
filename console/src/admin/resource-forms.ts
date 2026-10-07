// The fields of each type of shared resource in the Console's form (WI-50; 08-api.md: Shared
// resources): what a new one starts from, what an existing one's settings come to, the settings the
// fields make, and the field that a `problem` of `invalid_resource` is about. One form per type of
// the closed set (ADR-019), chosen by `formOf`; the dialog only asks it. The fields hold text as it
// was typed; only what the Engine cannot be asked (a field left empty, a number that is not one) is
// said here, the rest is the Engine's to refuse, and its `problem` is said at the field it is about.
// Settings the form has no field for are kept as they are when a resource is changed (`kept`).

import { ENDPOINTS, PARAMETERS, type RequestParameter } from './openai-catalog';

/**
 * What the fields make: the settings to send (none for a type without settings), or what is wrong
 * with fields, by the id of the field, as a word that the dialog translates (`required`, ...).
 */
export type FormResult = { settings: Record<string, unknown> | undefined } | { errors: Record<string, string> };

export interface TypeForm<F> {
  /** Whether the type names a secret of the keystore by its alias. */
  takesSecret: boolean;
  /** The fields of a new resource. */
  empty(): F;
  /** The fields of an existing resource, from its settings as the Engine wrote them. */
  fromSettings(settings: Record<string, unknown>): F;
  /**
   * The settings that [fields] make; of the [stored] settings of an existing resource, those the
   * form has no field for are kept as they are.
   */
  toSettings(fields: F, stored: Record<string, unknown>): FormResult;
  /** The id of the field that a `problem` of `invalid_resource` is about; undefined for none. */
  fieldOf(problem: string): string | undefined;
}

const counter: TypeForm<Record<string, never>> = {
  takesSecret: false,
  empty: () => ({}),
  fromSettings: () => ({}),
  toSettings: () => ({ settings: undefined }),
  fieldOf: () => undefined,
};

export interface FileFields {
  /** Relative to the resource root of the Engine. */
  path: string;
}

const file: TypeForm<FileFields> = {
  takesSecret: false,
  empty: () => ({ path: '' }),
  fromSettings: (settings) => ({ path: typeof settings.path === 'string' ? settings.path : '' }),
  toSettings: ({ path }) =>
    path.trim() === '' ? { errors: { 'resource-path': 'required' } } : { settings: { path } },
  fieldOf: (problem) =>
    ['path_outside_root', 'path_unusable', 'invalid_settings'].includes(problem) ? 'resource-path' : undefined,
};

/** A name and a value of a list of them (headers, connection properties), as typed. */
export interface Pair {
  name: string;
  value: string;
}

/** What the fields come to, field by field, and what is wrong with them so far. */
class Collected {
  readonly settings: Record<string, unknown>;
  readonly errors: Record<string, string> = {};

  /** [stored] without the settings the form has fields for: those are what the fields say. */
  constructor(stored: Record<string, unknown>, fielded: string[]) {
    this.settings = Object.fromEntries(Object.entries(stored).filter(([key]) => !fielded.includes(key)));
  }

  /** Text that must be there. */
  required(id: string, key: string, text: string) {
    if (text.trim() === '') this.errors[id] = 'required';
    else this.settings[key] = text.trim();
  }

  /** A whole number of 0 or more, or nothing (the Engine's default) when left empty. */
  wholeNumber(id: string, text: string): number | undefined {
    const trimmed = text.trim();
    if (trimmed === '') return undefined;
    if (!/^\d+$/.test(trimmed)) {
      this.errors[id] = 'wholeNumber';
      return undefined;
    }
    return Number(trimmed);
  }

  result(): FormResult {
    return Object.keys(this.errors).length > 0 ? { errors: this.errors } : { settings: this.settings };
  }
}

const text = (value: unknown): string =>
  typeof value === 'string' || typeof value === 'number' ? String(value) : '';
const objectOf = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
const pairsOf = (value: unknown): Pair[] =>
  Object.entries(objectOf(value)).map(([name, v]) => ({ name, value: text(v) }));
/** The pairs that have a name, as an object; undefined when none has. */
const objectOfPairs = (pairs: Pair[]): Record<string, string> | undefined => {
  const named = pairs.filter((pair) => pair.name.trim() !== '');
  return named.length === 0 ? undefined : Object.fromEntries(named.map((pair) => [pair.name.trim(), pair.value]));
};
/** [values] without the ones left empty; undefined when all were. */
const present = <T>(values: Record<string, T | undefined>): Record<string, T> | undefined => {
  const given = Object.entries(values).filter(([, value]) => value !== undefined) as Array<[string, T]>;
  return given.length === 0 ? undefined : Object.fromEntries(given);
};

export interface JdbcFields {
  /** The kind of database; the first version has PostgreSQL only. */
  kind: string;
  host: string;
  port: string;
  database: string;
  username: string;
  connectionsPerRun: string;
  connectMs: string;
  statementMs: string;
  quotaWaitMs: string;
  properties: Pair[];
}

/** The kinds of database the Engine has a profile for (08-api.md: `jdbc-pool`), in order. */
export const DATABASE_KINDS = ['postgresql'];

const JDBC_TIMEOUTS: Array<[keyof JdbcFields, string, string]> = [
  ['connectMs', 'connectMs', 'jdbc-connect-ms'],
  ['statementMs', 'statementMs', 'jdbc-statement-ms'],
  ['quotaWaitMs', 'quotaWaitMs', 'jdbc-quota-wait-ms'],
];

const jdbcPool: TypeForm<JdbcFields> = {
  takesSecret: true,
  empty: () => ({
    kind: DATABASE_KINDS[0],
    host: '',
    port: '',
    database: '',
    username: '',
    connectionsPerRun: '',
    connectMs: '',
    statementMs: '',
    quotaWaitMs: '',
    properties: [],
  }),
  fromSettings: (settings) => {
    const timeouts = objectOf(settings.timeouts);
    return {
      kind: text(settings.kind),
      host: text(settings.host),
      port: text(settings.port),
      database: text(settings.database),
      username: text(settings.username),
      connectionsPerRun: text(settings.connectionsPerRun),
      connectMs: text(timeouts.connectMs),
      statementMs: text(timeouts.statementMs),
      quotaWaitMs: text(timeouts.quotaWaitMs),
      properties: pairsOf(settings.properties),
    };
  },
  toSettings: (fields, stored) => {
    const out = new Collected(stored, [
      'kind',
      'host',
      'port',
      'database',
      'username',
      'connectionsPerRun',
      'timeouts',
      'properties',
    ]);
    out.settings.kind = fields.kind;
    out.required('jdbc-host', 'host', fields.host);
    const port = out.wholeNumber('jdbc-port', fields.port);
    out.required('jdbc-database', 'database', fields.database);
    out.required('jdbc-username', 'username', fields.username);
    if (port !== undefined) out.settings.port = port;
    const perRun = out.wholeNumber('jdbc-per-run', fields.connectionsPerRun);
    if (perRun !== undefined) out.settings.connectionsPerRun = perRun;
    const timeouts = present(
      Object.fromEntries(JDBC_TIMEOUTS.map(([field, key, id]) => [key, out.wholeNumber(id, fields[field] as string)])),
    );
    if (timeouts !== undefined) out.settings.timeouts = timeouts;
    const properties = objectOfPairs(fields.properties);
    if (properties !== undefined) out.settings.properties = properties;
    return out.result();
  },
  fieldOf: (problem) =>
    ({
      unsupported_database: 'jdbc-kind',
      property_not_allowed: 'jdbc-properties',
      invalid_timeout: 'jdbc-timeouts',
      invalid_limit: 'jdbc-per-run',
      invalid_settings: 'jdbc-settings',
    })[problem],
};

/** A request parameter of an `openai-compatible` resource: its default, lock and ceiling, as typed. */
export interface ParameterFields {
  /** The default, which a pipeline's own value replaces; empty for none. */
  value: string;
  /** A pipeline that gives the parameter is refused. */
  locked: boolean;
  /** The highest value a pipeline may give (numbers only); empty for none. */
  max: string;
}

export interface OpenAiFields {
  baseUrl: string;
  organization: string;
  project: string;
  headers: Pair[];
  /** The ids of the enabled entries of the catalog. */
  endpoints: string[];
  connectMs: string;
  firstByteMs: string;
  idleMs: string;
  /** Empty: no limit on a call as a whole. */
  totalMs: string;
  quotaWaitMs: string;
  requestsPerRun: string;
  /** Separated by commas; empty for any model. */
  allowedModels: string;
  parameters: Record<string, ParameterFields>;
}

const OPENAI_TIMEOUTS: Array<[keyof OpenAiFields, string]> = [
  ['connectMs', 'openai-connect-ms'],
  ['firstByteMs', 'openai-first-byte-ms'],
  ['idleMs', 'openai-idle-ms'],
  ['totalMs', 'openai-total-ms'],
  ['quotaWaitMs', 'openai-quota-wait-ms'],
];
const NUMBER = /^-?\d+(\.\d+)?([eE][+-]?\d+)?$/;

/** The default of a parameter as the text of its field: JSON for what is not plain text or number. */
const parameterText = (value: unknown): string =>
  value === undefined ? '' : typeof value === 'string' || typeof value === 'number' ? String(value) : JSON.stringify(value);

/** The fields of the request parameters, every one of them, in order. */
const parameterFields = (settings: Record<string, unknown> = {}): Record<string, ParameterFields> => {
  const defaults = objectOf(settings.defaults);
  const maxValues = objectOf(settings.maxValues);
  const locked = Array.isArray(settings.lockedParameters) ? settings.lockedParameters : [];
  return Object.fromEntries(
    PARAMETERS.map(({ name }) => [
      name,
      { value: parameterText(defaults[name]), locked: locked.includes(name), max: text(maxValues[name]) },
    ]),
  );
};

/** Reads the text of a default as the kind of value of [parameter]; writes the error if it is not. */
function parameterValue(out: Collected, parameter: RequestParameter, typed: string): unknown {
  const id = `openai-parameter-${parameter.name}`;
  const trimmed = typed.trim();
  if (parameter.kind === 'number') {
    if (NUMBER.test(trimmed)) return Number(trimmed);
    out.errors[id] = 'number';
    return undefined;
  }
  // `stop` is a word, or a list of them in JSON; `response_format` is an object of JSON.
  if (parameter.kind === 'json' && (parameter.name !== 'stop' || trimmed.startsWith('['))) {
    try {
      return JSON.parse(trimmed);
    } catch {
      out.errors[id] = 'json';
      return undefined;
    }
  }
  return trimmed;
}

const openAiCompatible: TypeForm<OpenAiFields> = {
  takesSecret: true,
  empty: () => ({
    baseUrl: '',
    organization: '',
    project: '',
    headers: [],
    endpoints: ENDPOINTS.filter((entry) => entry.byDefault).map((entry) => entry.id),
    connectMs: '',
    firstByteMs: '',
    idleMs: '',
    totalMs: '',
    quotaWaitMs: '',
    requestsPerRun: '',
    allowedModels: '',
    parameters: parameterFields(),
  }),
  fromSettings: (settings) => {
    const timeouts = objectOf(settings.timeouts);
    return {
      baseUrl: text(settings.baseUrl),
      organization: text(settings.organization),
      project: text(settings.project),
      headers: pairsOf(settings.headers),
      endpoints: Array.isArray(settings.endpoints) ? settings.endpoints.map(String) : [],
      connectMs: text(timeouts.connectMs),
      firstByteMs: text(timeouts.firstByteMs),
      idleMs: text(timeouts.idleMs),
      totalMs: text(timeouts.totalMs),
      quotaWaitMs: text(timeouts.quotaWaitMs),
      requestsPerRun: text(settings.requestsPerRun),
      allowedModels: Array.isArray(settings.allowedModels) ? settings.allowedModels.join(', ') : '',
      parameters: parameterFields(settings),
    };
  },
  toSettings: (fields, stored) => {
    const out = new Collected(stored, [
      'baseUrl',
      'organization',
      'project',
      'headers',
      'endpoints',
      'timeouts',
      'requestsPerRun',
      'defaults',
      'allowedModels',
      'lockedParameters',
      'maxValues',
    ]);
    out.required('openai-base-url', 'baseUrl', fields.baseUrl);
    if (fields.organization.trim() !== '') out.settings.organization = fields.organization.trim();
    if (fields.project.trim() !== '') out.settings.project = fields.project.trim();
    const headers = objectOfPairs(fields.headers);
    if (headers !== undefined) out.settings.headers = headers;
    if (fields.endpoints.length === 0) out.errors['openai-endpoints'] = 'endpoints';
    // In the order of the catalog; one the catalog does not have stays, last, for the Engine to say.
    out.settings.endpoints = [
      ...ENDPOINTS.map((entry) => entry.id).filter((id) => fields.endpoints.includes(id)),
      ...fields.endpoints.filter((id) => !ENDPOINTS.some((entry) => entry.id === id)),
    ];
    const timeouts = present(
      Object.fromEntries(OPENAI_TIMEOUTS.map(([field, id]) => [field, out.wholeNumber(id, fields[field] as string)])),
    );
    if (timeouts !== undefined) out.settings.timeouts = timeouts;
    const perRun = out.wholeNumber('openai-per-run', fields.requestsPerRun);
    if (perRun !== undefined) out.settings.requestsPerRun = perRun;

    const defaults: Record<string, unknown> = {};
    const locked: string[] = [];
    const maxValues: Record<string, number> = {};
    for (const parameter of PARAMETERS) {
      const typed = fields.parameters[parameter.name] ?? { value: '', locked: false, max: '' };
      if (typed.value.trim() !== '') {
        const value = parameterValue(out, parameter, typed.value);
        if (value !== undefined) defaults[parameter.name] = value;
      }
      if (typed.locked) locked.push(parameter.name);
      if (typed.max.trim() !== '') {
        if (NUMBER.test(typed.max.trim())) maxValues[parameter.name] = Number(typed.max.trim());
        else out.errors[`openai-max-${parameter.name}`] = 'number';
      }
    }
    if (Object.keys(defaults).length > 0) out.settings.defaults = defaults;
    if (locked.length > 0) out.settings.lockedParameters = locked;
    if (Object.keys(maxValues).length > 0) out.settings.maxValues = maxValues;
    const models = fields.allowedModels
      .split(',')
      .map((model) => model.trim())
      .filter((model) => model !== '');
    if (models.length > 0) out.settings.allowedModels = models;
    return out.result();
  },
  fieldOf: (problem) =>
    ({
      invalid_base_url: 'openai-base-url',
      invalid_header: 'openai-headers',
      invalid_endpoint: 'openai-endpoints',
      invalid_request_defaults: 'openai-parameters',
      invalid_timeout: 'openai-timeouts',
      invalid_limit: 'openai-per-run',
      invalid_settings: 'openai-settings',
    })[problem],
};

const FORMS: Record<string, TypeForm<any>> = {
  counter,
  file,
  'jdbc-pool': jdbcPool,
  'openai-compatible': openAiCompatible,
};

/** The form of [type]; undefined for a type the Console has no form for. */
export const formOf = (type: string): TypeForm<any> | undefined => FORMS[type];

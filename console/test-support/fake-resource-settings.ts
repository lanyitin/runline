// The settings of each type of shared resource in the Fake Engine (08-api.md: Shared resources,
// ADR-019): what the Engine accepts for a type, the `problem` of what it refuses, and the settings
// as it writes them back (every effective value written out). One set of rules per type, as the
// Engine has one behavior per type; the contract tests (contract/admin-contract.ts) hold each to the
// Engine.

/** The settings as written, or the `problem` of `invalid_resource` that refuses them. */
export type SettingsAnswer = { settings: Record<string, unknown> } | { problem: string };

export interface TypeRules {
  /** Whether a resource of the type may name a secret of the keystore by its alias. */
  takesSecret: boolean;
  /** [given] (undefined when the request had none), checked and written out. */
  settings(given: unknown): SettingsAnswer;
}

const ALIAS = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;

/**
 * The certificate aliases of a type that has TLS (WI-52), written in lower case after the rest:
 * a list of at most 16 distinct aliases and one alias; a member of the wrong shape is
 * `invalid_settings` and an alias that is not written like one is `invalid_secret_alias`.
 */
function tlsOf(settings: Record<string, unknown>): Record<string, unknown> | { problem: string } {
  const written: Record<string, unknown> = {};
  const trust = settings.trustAliases;
  if (trust !== undefined) {
    if (!Array.isArray(trust) || trust.some((alias) => typeof alias !== 'string')) return { problem: 'invalid_settings' };
    if ((trust as string[]).some((alias) => !ALIAS.test(alias))) return { problem: 'invalid_secret_alias' };
    const lower = (trust as string[]).map((alias) => alias.toLowerCase());
    if (lower.length > 16 || new Set(lower).size !== lower.length) return { problem: 'invalid_settings' };
    if (lower.length > 0) written.trustAliases = lower;
  }
  const client = settings.clientCertAlias;
  if (client !== undefined) {
    if (typeof client !== 'string') return { problem: 'invalid_settings' };
    if (!ALIAS.test(client)) return { problem: 'invalid_secret_alias' };
    written.clientCertAlias = client.toLowerCase();
  }
  return written;
}

const objectOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;

const counter: TypeRules = {
  takesSecret: false,
  settings: (given) =>
    given === undefined || (objectOf(given) !== null && Object.keys(objectOf(given)!).length === 0)
      ? { settings: {} }
      : { problem: 'invalid_settings' },
};

/** One file under the resource root: `{"path": "<relative path>"}`, kept as it was given. */
const file: TypeRules = {
  takesSecret: false,
  settings(given) {
    const settings = objectOf(given);
    const path = settings?.path;
    if (settings === null || Object.keys(settings).join() !== 'path' || typeof path !== 'string' || path.trim() === '') {
      return { problem: 'invalid_settings' };
    }
    return insideRoot(path) ? { settings: { path } } : { problem: 'path_outside_root' };
  },
};

/**
 * Whether [path], read as it is written, is a file below the root and not the root itself. The
 * Fake has no file system: a path is inside when it is relative and `..` never climbs above it.
 */
function insideRoot(path: string): boolean {
  if (path.startsWith('/')) return false;
  let depth = 0;
  for (const part of path.split('/')) {
    if (part === '..') depth--;
    else if (part !== '' && part !== '.') depth++;
    if (depth < 0) return false;
  }
  return depth > 0;
}

/** A whole number in [min]..[max], [fallback] when absent; undefined when it is something else. */
const bounded = (value: unknown, min: number, max: number, fallback: number): number | undefined =>
  value === undefined ? fallback : Number.isInteger(value) && (value as number) >= min && (value as number) <= max ? (value as number) : undefined;

const DAY_MS = 24 * 3600 * 1000;

const JDBC_MEMBERS = ['kind', 'host', 'port', 'database', 'username', 'connectionsPerRun', 'timeouts', 'maxRows', 'maxResponseBytes', 'properties', 'trustAliases', 'clientCertAlias'];
const HOST = /^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\[[0-9A-Fa-f:.]{2,45}\])$/;
const DATABASE = /^[A-Za-z0-9_][A-Za-z0-9_.$-]{0,62}$/;
const IDENTIFIERS = /^[A-Za-z_][A-Za-z0-9_$]{0,62}(?:,[A-Za-z_][A-Za-z0-9_$]{0,62}){0,7}$/;
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;

/** The extra connection properties PostgreSQL's profile allows, with what each accepts. */
const POSTGRES_PROPERTIES: Record<string, (value: string) => boolean> = {
  ApplicationName: (value) => value.length <= 64 && !CONTROL.test(value),
  currentSchema: (value) => IDENTIFIERS.test(value),
  tcpKeepAlive: (value) => value === 'true' || value === 'false',
};

/**
 * Times in milliseconds, each a whole number from 1 to a day: [defaults] has every one there is,
 * in order; [optional] may also be null or left out. Undefined when one is wrong or unknown.
 */
function timeoutsOf(
  given: unknown,
  defaults: Record<string, number>,
  optional: string[] = [],
): Record<string, number> | undefined {
  const timeouts = given === undefined ? {} : objectOf(given);
  const known = [...Object.keys(defaults), ...optional];
  if (timeouts === null || Object.keys(timeouts).some((key) => !known.includes(key))) return undefined;
  const written: Record<string, number> = {};
  for (const key of known) {
    if (optional.includes(key) && (timeouts[key] === undefined || timeouts[key] === null)) continue;
    const value = bounded(timeouts[key], 1, DAY_MS, defaults[key]);
    if (value === undefined) return undefined;
    written[key] = value;
  }
  return written;
}

/** The connection pool of a PostgreSQL database, the only kind of the first version. */
const jdbcPool: TypeRules = {
  takesSecret: true,
  settings(given) {
    const settings = objectOf(given);
    if (settings === null || Object.keys(settings).some((key) => !JDBC_MEMBERS.includes(key))) {
      return { problem: 'invalid_settings' };
    }
    const text = (key: string) => (typeof settings[key] === 'string' && settings[key] !== '' ? (settings[key] as string) : undefined);
    const kind = text('kind');
    if (kind === undefined) return { problem: 'invalid_settings' };
    if (kind !== 'postgresql') return { problem: 'unsupported_database' };
    const host = text('host');
    const database = text('database');
    const username = text('username');
    if (host === undefined || database === undefined || username === undefined) return { problem: 'invalid_settings' };
    if (username.length > 128 || CONTROL.test(username) || !HOST.test(host) || !DATABASE.test(database)) {
      return { problem: 'invalid_settings' };
    }
    const port = bounded(settings.port, 1, 65535, 5432);
    if (port === undefined) return { problem: 'invalid_settings' };
    const timeouts = timeoutsOf(settings.timeouts, { connectMs: 10_000, statementMs: 300_000, quotaWaitMs: 60_000 });
    if (timeouts === undefined) return { problem: 'invalid_timeout' };
    const connectionsPerRun = bounded(settings.connectionsPerRun, 1, 64, 1);
    const maxRows = bounded(settings.maxRows, 1, 1_000_000, 10_000);
    const maxResponseBytes = bounded(settings.maxResponseBytes, 1, 256 * 1024 * 1024, 8 * 1024 * 1024);
    if (connectionsPerRun === undefined || maxRows === undefined || maxResponseBytes === undefined) {
      return { problem: 'invalid_limit' };
    }
    const properties = settings.properties === undefined ? {} : objectOf(settings.properties);
    if (properties === null || Object.keys(properties).length > 16) return { problem: 'invalid_settings' };
    // A name that is not allowed is the first thing said, whatever its value is.
    if (Object.keys(properties).some((name) => !(name in POSTGRES_PROPERTIES))) return { problem: 'property_not_allowed' };
    for (const [name, value] of Object.entries(properties)) {
      if (typeof value !== 'string' || !POSTGRES_PROPERTIES[name](value)) return { problem: 'invalid_settings' };
    }
    const written: Record<string, unknown> = {
      kind,
      host,
      port,
      database,
      username,
      connectionsPerRun,
      timeouts,
      maxRows,
      maxResponseBytes,
    };
    if (Object.keys(properties).length > 0) written.properties = properties;
    const tls = tlsOf(settings);
    if ('problem' in tls) return tls as { problem: string };
    return { settings: { ...written, ...tls } };
  },
};

/** The endpoint catalog of the Engine, in its order (08-api.md: `openai-compatible`). */
const CATALOG = [
  'chat.completions',
  'completions',
  'embeddings',
  'models.list',
  'models.retrieve',
  'responses.create',
  'responses.retrieve',
  'responses.delete',
  'responses.cancel',
  'responses.input_items',
  'moderations',
  'rerank',
  'reranking',
  'images.generations',
  'images.edits',
  'images.variations',
  'audio.speech',
  'audio.transcriptions',
  'audio.translations',
  'files.create',
  'files.list',
  'files.retrieve',
  'files.delete',
  'files.content',
  'batches.create',
  'batches.list',
  'batches.retrieve',
  'batches.cancel',
];
const DEFAULT_ENDPOINTS = CATALOG.slice(0, 5);

const OPENAI_MEMBERS = [
  'baseUrl',
  'organization',
  'project',
  'headers',
  'endpoints',
  'timeouts',
  'requestsPerRun',
  'maxRequestBytes',
  'maxResponseBytes',
  'maxDownloadBytes',
  'defaults',
  'allowedModels',
  'lockedParameters',
  'maxValues',
  'trustAliases',
  'clientCertAlias',
];
const NUMERIC = [
  'temperature',
  'top_p',
  'top_k',
  'min_p',
  'max_tokens',
  'max_completion_tokens',
  'max_output_tokens',
  'seed',
  'presence_penalty',
  'frequency_penalty',
  'repeat_penalty',
  'n',
];
const DEFAULTABLE = [...NUMERIC, 'model', 'stop', 'response_format', 'reasoning_effort'];
const CREDENTIAL_WORDS = ['auth', 'key', 'token', 'secret', 'cookie'];
const ENGINE_MANAGED = [
  'host',
  'content-length',
  'content-type',
  'accept',
  'transfer-encoding',
  'connection',
  'keep-alive',
  'proxy-connection',
  'upgrade',
  'expect',
  'te',
  'trailer',
  'openai-organization',
  'openai-project',
];
const TOKEN = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
const MiB = 1024 * 1024;

const headerValue = (value: string) =>
  value.length <= 1024 && /^[\t\x20-\x7e]*$/.test(value) && value === value.trim();
const headerName = (name: string) =>
  TOKEN.test(name) &&
  !CREDENTIAL_WORDS.some((word) => name.toLowerCase().includes(word)) &&
  !ENGINE_MANAGED.includes(name.toLowerCase());

/** The base address as it is kept: an http(s) address of its own, without a trailing slash. */
function baseUrlOf(value: string): string | undefined {
  if (value === '' || !/^[\x21-\x7e]+$/.test(value)) return undefined;
  const match = /^(https?):\/\/([^/?#]+)([^?#]*)$/i.exec(value);
  if (match === null || match[2].includes('@')) return undefined;
  const path = match[3].replace(/\/+$/, '');
  if (path.includes('//') || path.split('/').some((part) => part === '.' || part === '..')) return undefined;
  return `${match[1].toLowerCase()}://${match[2]}${path}`;
}

const nonEmptyStrings = (value: unknown): string[] | undefined =>
  Array.isArray(value) && value.every((item) => typeof item === 'string' && item !== '') ? (value as string[]) : undefined;

/** Whether [value] is the kind of value the request parameter [name] takes. */
function fits(name: string, value: unknown): boolean {
  if (NUMERIC.includes(name)) return typeof value === 'number';
  if (name === 'stop') return typeof value === 'string' || nonEmptyStrings(value) !== undefined;
  if (name === 'response_format') return objectOf(value) !== null;
  return typeof value === 'string' && value !== '';
}

/** An OpenAI compatible service: where it is, what goes with each request, and what may be asked. */
const openAiCompatible: TypeRules = {
  takesSecret: true,
  settings(given) {
    const settings = objectOf(given);
    if (settings === null || Object.keys(settings).some((key) => !OPENAI_MEMBERS.includes(key))) {
      return { problem: 'invalid_settings' };
    }
    if (settings.baseUrl === undefined) return { problem: 'invalid_settings' };
    const baseUrl = typeof settings.baseUrl === 'string' ? baseUrlOf(settings.baseUrl) : undefined;
    if (baseUrl === undefined) return { problem: 'invalid_base_url' };
    for (const key of ['organization', 'project']) {
      const value = settings[key];
      if (value === undefined) continue;
      if (typeof value !== 'string' || value === '') return { problem: 'invalid_settings' };
    }
    for (const key of ['organization', 'project']) {
      if (typeof settings[key] === 'string' && !headerValue(settings[key] as string)) return { problem: 'invalid_header' };
    }
    const headers = settings.headers === undefined ? {} : objectOf(settings.headers);
    if (headers === null || Object.keys(headers).length > 32) return { problem: 'invalid_header' };
    const seen = new Set<string>();
    for (const [name, value] of Object.entries(headers)) {
      if (typeof value !== 'string' || !headerName(name) || !headerValue(value) || seen.has(name.toLowerCase())) {
        return { problem: 'invalid_header' };
      }
      seen.add(name.toLowerCase());
    }
    const named = settings.endpoints === undefined ? DEFAULT_ENDPOINTS : nonEmptyStrings(settings.endpoints);
    if (named === undefined || named.length === 0 || named.some((id) => !CATALOG.includes(id))) {
      return { problem: 'invalid_endpoint' };
    }
    const timeouts = timeoutsOf(
      settings.timeouts,
      { connectMs: 10_000, firstByteMs: 900_000, idleMs: 300_000, quotaWaitMs: 60_000 },
      ['totalMs'],
    );
    if (timeouts === undefined) return { problem: 'invalid_timeout' };
    const limits = {
      requestsPerRun: bounded(settings.requestsPerRun, 1, 256, 1),
      maxRequestBytes: bounded(settings.maxRequestBytes, 1, 1024 * MiB, 32 * MiB),
      maxResponseBytes: bounded(settings.maxResponseBytes, 1, 256 * MiB, 8 * MiB),
      maxDownloadBytes: bounded(settings.maxDownloadBytes, 1, 16 * 1024 * MiB, 256 * MiB),
    };
    if (Object.values(limits).some((value) => value === undefined)) return { problem: 'invalid_limit' };
    const defaults = settings.defaults === undefined ? {} : objectOf(settings.defaults);
    const allowedModels = settings.allowedModels === undefined ? [] : nonEmptyStrings(settings.allowedModels);
    const locked = settings.lockedParameters === undefined ? [] : nonEmptyStrings(settings.lockedParameters);
    const maxValues = settings.maxValues === undefined ? {} : objectOf(settings.maxValues);
    if (
      defaults === null ||
      Object.entries(defaults).some(([name, value]) => !DEFAULTABLE.includes(name) || !fits(name, value)) ||
      allowedModels === undefined ||
      locked === undefined ||
      maxValues === null ||
      Object.entries(maxValues).some(([name, value]) => !NUMERIC.includes(name) || typeof value !== 'number' || !(value > 0) || !Number.isFinite(value)) ||
      locked.some((name) => !DEFAULTABLE.includes(name)) ||
      (typeof defaults.model === 'string' && allowedModels.length > 0 && !allowedModels.includes(defaults.model)) ||
      Object.entries(maxValues).some(([name, ceiling]) => typeof defaults[name] === 'number' && (defaults[name] as number) > (ceiling as number))
    ) {
      return { problem: 'invalid_request_defaults' };
    }

    const written: Record<string, unknown> = { baseUrl };
    if (settings.organization !== undefined) written.organization = settings.organization;
    if (settings.project !== undefined) written.project = settings.project;
    if (Object.keys(headers).length > 0) written.headers = headers;
    written.endpoints = CATALOG.filter((id) => named.includes(id));
    written.timeouts = {
      connectMs: timeouts.connectMs,
      firstByteMs: timeouts.firstByteMs,
      idleMs: timeouts.idleMs,
      ...(timeouts.totalMs === undefined ? {} : { totalMs: timeouts.totalMs }),
      quotaWaitMs: timeouts.quotaWaitMs,
    };
    Object.assign(written, limits);
    if (Object.keys(defaults).length > 0) written.defaults = defaults;
    if (allowedModels.length > 0) written.allowedModels = [...new Set(allowedModels)];
    if (locked.length > 0) written.lockedParameters = [...new Set(locked)];
    if (Object.keys(maxValues).length > 0) written.maxValues = maxValues;
    const tls = tlsOf(settings);
    if ('problem' in tls) return tls as { problem: string };
    // Certificates are for TLS: a plain http service has no handshake to use them in.
    if (Object.keys(tls).length > 0 && !baseUrl.startsWith('https://')) return { problem: 'invalid_settings' };
    return { settings: { ...written, ...tls } };
  },
};

/** The types the Fake defines through the API, by their name in the API. */
export const TYPE_RULES: Record<string, TypeRules> = {
  counter,
  file,
  'jdbc-pool': jdbcPool,
  'openai-compatible': openAiCompatible,
};

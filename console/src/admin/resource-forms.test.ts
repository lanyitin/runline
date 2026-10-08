import { describe, expect, test } from 'vitest';
import { RESOURCE_TYPES_ANSWER } from '../../test-support/fake-resource-types';
import { parseResourceTypes, type ResourceTypeCatalog } from '../api/admin-model';
import { formOf } from './resource-forms';

/** The catalog of the Fake Engine, which the contract holds to the Engine's. */
const CATALOG = parseResourceTypes(RESOURCE_TYPES_ANSWER);

describe('the form of a file', () => {
  const form = formOf('file', CATALOG)!;

  test('is empty for a new one, and has the path of one that exists', () => {
    expect(form.empty()).toEqual({ path: '' });
    expect(form.fromSettings({ path: 'reports/today.csv' })).toEqual({ path: 'reports/today.csv' });
  });

  test('makes the settings of the path, as it was written', () => {
    expect(form.toSettings({ path: 'reports/today.csv' }, {})).toEqual({ settings: { path: 'reports/today.csv' } });
  });

  test('asks for a path before it asks the Engine', () => {
    expect(form.toSettings({ path: '  ' }, {})).toEqual({ errors: { 'resource-path': 'required' } });
  });

  test('says what the Engine refused at the path', () => {
    expect(form.fieldOf('path_outside_root')).toBe('resource-path');
    expect(form.fieldOf('path_unusable')).toBe('resource-path');
    expect(form.fieldOf('invalid_settings')).toBe('resource-path');
  });

  test('takes no secret', () => {
    expect(form.takesSecret).toBe(false);
  });
});

describe('the form of a jdbc-pool', () => {
  const form = formOf('jdbc-pool', CATALOG)!;
  const stored = {
    kind: 'postgresql',
    host: 'db.internal',
    port: 6543,
    database: 'orders',
    username: 'reader',
    connectionsPerRun: 2,
    timeouts: { connectMs: 2000, statementMs: 30000, quotaWaitMs: 5000 },
    maxRows: 500,
    maxResponseBytes: 1024,
    properties: { ApplicationName: 'reports', tcpKeepAlive: 'true' },
  };
  const fields = {
    kind: 'postgresql',
    host: 'db.internal',
    port: '6543',
    database: 'orders',
    username: 'reader',
    connectionsPerRun: '2',
    connectMs: '2000',
    statementMs: '30000',
    quotaWaitMs: '5000',
    properties: [
      { name: 'ApplicationName', value: 'reports' },
      { name: 'tcpKeepAlive', value: 'true' },
    ],
    trustAliases: [],
    clientCertAlias: '',
  };

  test('a new one is of PostgreSQL, the only kind there is, and leaves the rest to be filled or to the defaults', () => {
    expect(form.empty()).toEqual({
      kind: 'postgresql',
      host: '',
      port: '',
      database: '',
      username: '',
      connectionsPerRun: '',
      connectMs: '',
      statementMs: '',
      quotaWaitMs: '',
      properties: [],
      trustAliases: [],
      clientCertAlias: '',
    });
  });

  test('one that exists has its settings in the fields, the properties one by one', () => {
    expect(form.fromSettings(stored)).toEqual(fields);
  });

  test('makes the settings of the fields; what was left empty is the Engine\'s default; what has no field is kept', () => {
    expect(form.toSettings(fields, { maxRows: 500, maxResponseBytes: 1024 })).toEqual({ settings: stored });
    expect(
      form.toSettings({ ...form.empty(), host: 'db', database: 'orders', username: 'reader', properties: [{ name: ' ', value: '' }] }, {}),
    ).toEqual({ settings: { kind: 'postgresql', host: 'db', database: 'orders', username: 'reader' } });
  });

  test('of an existing one, a field that was emptied is the Engine\'s default again, not what was stored', () => {
    const emptied = { ...fields, port: '', connectMs: '', statementMs: '', quotaWaitMs: '', properties: [] };
    expect(form.toSettings(emptied, stored)).toEqual({
      settings: { kind: 'postgresql', host: 'db.internal', database: 'orders', username: 'reader', connectionsPerRun: 2, maxRows: 500, maxResponseBytes: 1024 },
    });
  });

  test('asks for the host, the database and the account, and for whole numbers, before it asks the Engine', () => {
    expect(form.toSettings({ ...form.empty(), port: 'x', connectionsPerRun: '1.5', statementMs: '-3' }, {})).toEqual({
      errors: {
        'jdbc-host': 'required',
        'jdbc-database': 'required',
        'jdbc-username': 'required',
        'jdbc-port': 'wholeNumber',
        'jdbc-per-run': 'wholeNumber',
        'jdbc-statement-ms': 'wholeNumber',
      },
    });
  });

  test('says what the Engine refused at the field it is about', () => {
    expect(form.fieldOf('unsupported_database')).toBe('jdbc-kind');
    expect(form.fieldOf('property_not_allowed')).toBe('jdbc-properties');
    expect(form.fieldOf('invalid_timeout')).toBe('jdbc-timeouts');
    expect(form.fieldOf('invalid_limit')).toBe('jdbc-per-run');
    expect(form.fieldOf('invalid_settings')).toBe('jdbc-settings');
  });

  test('takes the secret of the account by its alias', () => {
    expect(form.takesSecret).toBe(true);
  });
});

describe('the form of an openai-compatible service', () => {
  const form = formOf('openai-compatible', CATALOG)!;
  const noParameters = () => form.empty().parameters;

  test('a new one has the endpoints the Engine enables by default, every parameter free, and leaves the rest to be filled or to the defaults', () => {
    const empty = form.empty();
    expect(empty).toMatchObject({
      baseUrl: '',
      organization: '',
      project: '',
      headers: [],
      endpoints: ['chat.completions', 'completions', 'embeddings', 'models.list', 'models.retrieve'],
      connectMs: '',
      firstByteMs: '',
      idleMs: '',
      totalMs: '',
      quotaWaitMs: '',
      requestsPerRun: '',
      allowedModels: '',
    });
    expect(Object.keys(empty.parameters)).toEqual([
      'model',
      'temperature',
      'top_p',
      'top_k',
      'min_p',
      'max_tokens',
      'max_completion_tokens',
      'max_output_tokens',
      'stop',
      'seed',
      'response_format',
      'presence_penalty',
      'frequency_penalty',
      'repeat_penalty',
      'n',
      'reasoning_effort',
    ]);
    expect(empty.parameters.temperature).toEqual({ value: '', locked: false, max: '' });
  });

  const stored = {
    baseUrl: 'https://llm.internal/v1',
    organization: 'org-1',
    project: 'proj-2',
    headers: { 'X-Team': 'reports' },
    endpoints: ['chat.completions', 'models.list', 'audio.speech'],
    timeouts: { connectMs: 1000, firstByteMs: 2000, idleMs: 3000, totalMs: 4000, quotaWaitMs: 5000 },
    requestsPerRun: 3,
    maxRequestBytes: 1,
    maxResponseBytes: 2,
    maxDownloadBytes: 3,
    defaults: { model: 'small', temperature: 0.2, stop: ['END'], response_format: { type: 'json_object' } },
    allowedModels: ['small', 'large'],
    lockedParameters: ['temperature', 'seed'],
    maxValues: { max_tokens: 4096 },
  };

  test('one that exists has its settings in the fields: each parameter with its default, whether it is locked and its ceiling', () => {
    const fields = form.fromSettings(stored);
    expect(fields).toMatchObject({
      baseUrl: 'https://llm.internal/v1',
      organization: 'org-1',
      project: 'proj-2',
      headers: [{ name: 'X-Team', value: 'reports' }],
      endpoints: ['chat.completions', 'models.list', 'audio.speech'],
      connectMs: '1000',
      firstByteMs: '2000',
      idleMs: '3000',
      totalMs: '4000',
      quotaWaitMs: '5000',
      requestsPerRun: '3',
      allowedModels: 'small, large',
    });
    expect(fields.parameters.model).toEqual({ value: 'small', locked: false, max: '' });
    expect(fields.parameters.temperature).toEqual({ value: '0.2', locked: true, max: '' });
    expect(fields.parameters.seed).toEqual({ value: '', locked: true, max: '' });
    expect(fields.parameters.max_tokens).toEqual({ value: '', locked: false, max: '4096' });
    expect(fields.parameters.stop).toEqual({ value: '["END"]', locked: false, max: '' });
    expect(fields.parameters.response_format).toEqual({ value: '{"type":"json_object"}', locked: false, max: '' });
  });

  test('makes the settings of the fields, and of an existing one the same settings again; what has no field is kept', () => {
    const { maxRequestBytes, maxResponseBytes, maxDownloadBytes } = stored;
    expect(form.toSettings(form.fromSettings(stored), { maxRequestBytes, maxResponseBytes, maxDownloadBytes })).toEqual({
      settings: stored,
    });
  });

  test('of an existing one, what was emptied is gone from the settings; what has no field stays', () => {
    const fields = { ...form.fromSettings(stored), organization: '', headers: [], totalMs: '', allowedModels: '', parameters: noParameters() };
    const settings = (form.toSettings(fields, stored) as { settings: Record<string, unknown> }).settings;
    expect(Object.keys(settings).sort()).toEqual(
      ['baseUrl', 'endpoints', 'maxDownloadBytes', 'maxRequestBytes', 'maxResponseBytes', 'project', 'requestsPerRun', 'timeouts'],
    );
    expect(settings.timeouts).toEqual({ connectMs: 1000, firstByteMs: 2000, idleMs: 3000, quotaWaitMs: 5000 });
  });

  test('what was left empty is the Engine\'s default: no total time, no header, no parameter, any model', () => {
    expect(form.toSettings({ ...form.empty(), baseUrl: ' http://llm:8000/v1 ', headers: [{ name: '', value: 'x' }] }, {})).toEqual({
      settings: {
        baseUrl: 'http://llm:8000/v1',
        endpoints: ['chat.completions', 'completions', 'embeddings', 'models.list', 'models.retrieve'],
      },
    });
  });

  test('a default is read as the kind of value of its parameter: a number, text, a list or an object of JSON', () => {
    const parameters = {
      ...noParameters(),
      model: { value: 'small', locked: false, max: '' },
      max_tokens: { value: '512', locked: false, max: '1024' },
      stop: { value: 'END', locked: false, max: '' },
      reasoning_effort: { value: 'low', locked: true, max: '' },
    };
    expect(form.toSettings({ ...form.empty(), baseUrl: 'http://llm/v1', parameters }, {})).toMatchObject({
      settings: {
        defaults: { model: 'small', max_tokens: 512, stop: 'END', reasoning_effort: 'low' },
        lockedParameters: ['reasoning_effort'],
        maxValues: { max_tokens: 1024 },
      },
    });
  });

  test('asks for the address, for numbers and for JSON where they are needed, before it asks the Engine', () => {
    const parameters = {
      ...noParameters(),
      temperature: { value: 'hot', locked: false, max: 'x' },
      response_format: { value: '{nope', locked: false, max: '' },
      stop: { value: '[1, 2', locked: false, max: '' },
    };
    expect(
      form.toSettings({ ...form.empty(), requestsPerRun: 'two', totalMs: '1e3', parameters }, {}),
    ).toEqual({
      errors: {
        'openai-base-url': 'required',
        'openai-per-run': 'wholeNumber',
        'openai-total-ms': 'wholeNumber',
        'openai-parameter-temperature': 'number',
        'openai-max-temperature': 'number',
        'openai-parameter-response_format': 'json',
        'openai-parameter-stop': 'json',
      },
    });
  });

  test('asks for at least one endpoint', () => {
    expect(form.toSettings({ ...form.empty(), baseUrl: 'http://llm/v1', endpoints: [] }, {})).toEqual({
      errors: { 'openai-endpoints': 'endpoints' },
    });
  });

  test('says what the Engine refused at the field it is about', () => {
    expect(form.fieldOf('invalid_base_url')).toBe('openai-base-url');
    expect(form.fieldOf('invalid_header')).toBe('openai-headers');
    expect(form.fieldOf('invalid_endpoint')).toBe('openai-endpoints');
    expect(form.fieldOf('invalid_request_defaults')).toBe('openai-parameters');
    expect(form.fieldOf('invalid_timeout')).toBe('openai-timeouts');
    expect(form.fieldOf('invalid_limit')).toBe('openai-per-run');
    expect(form.fieldOf('invalid_settings')).toBe('openai-settings');
  });

  test('takes the API key by its alias', () => {
    expect(form.takesSecret).toBe(true);
  });
});

describe('the forms there are', () => {
  test('a counter has no fields of its own, and a type the Console does not know has no form', () => {
    expect(formOf('counter', CATALOG)!.toSettings(formOf('counter', CATALOG)!.empty(), {})).toEqual({ settings: undefined });
    expect(formOf('quantum', CATALOG)).toBeUndefined();
  });
});

describe('the certificates of the types that connect over TLS (WI-52)', () => {
  for (const [type, base] of [
    ['jdbc-pool', { kind: 'postgresql', host: 'db', database: 'orders', username: 'reader' }],
    ['openai-compatible', { baseUrl: 'https://llm.internal/v1', endpoints: ['chat.completions'] }],
  ] as const) {
    const form = formOf(type, CATALOG)!;

    test(`${type}: a new one names none, so the connection is as without them`, () => {
      expect(form.empty()).toMatchObject({ trustAliases: [], clientCertAlias: '' });
      const made = form.toSettings({ ...form.fromSettings(base) }, {}) as { settings: Record<string, unknown> };
      expect(made.settings).not.toHaveProperty('trustAliases');
      expect(made.settings).not.toHaveProperty('clientCertAlias');
    });

    test(`${type}: the trusted certificates and the client certificate are read and written by alias`, () => {
      const stored = { ...base, trustAliases: ['internal-ca', 'backup-ca'], clientCertAlias: 'app-client' };
      const fields = form.fromSettings(stored);
      expect(fields).toMatchObject({ trustAliases: ['internal-ca', 'backup-ca'], clientCertAlias: 'app-client' });
      const made = form.toSettings(fields, stored) as { settings: Record<string, unknown> };
      expect(made.settings).toMatchObject({ trustAliases: ['internal-ca', 'backup-ca'], clientCertAlias: 'app-client' });
    });

    test(`${type}: certificates that were taken away are gone, not kept`, () => {
      const stored = { ...base, trustAliases: ['internal-ca'], clientCertAlias: 'app-client' };
      const made = form.toSettings({ ...form.fromSettings(stored), trustAliases: [], clientCertAlias: '' }, stored) as {
        settings: Record<string, unknown>;
      };
      expect(made.settings).not.toHaveProperty('trustAliases');
      expect(made.settings).not.toHaveProperty('clientCertAlias');
    });

    test(`${type}: an alias of the wrong kind is said at the certificates`, () => {
      expect(form.fieldOf('alias_wrong_type')).toBe('resource-certificates');
    });
  }
});

describe('the forms and the catalog of the Engine (WI-55)', () => {
  test('without the catalog there is no form for jdbc-pool or openai-compatible; counter and file have theirs', () => {
    expect(formOf('jdbc-pool', null)).toBeUndefined();
    expect(formOf('openai-compatible', null)).toBeUndefined();
    expect(formOf('counter', null)).toBeDefined();
    expect(formOf('file', null)!.toSettings({ path: 'a.txt' }, {})).toEqual({ settings: { path: 'a.txt' } });
  });

  // Another Engine, with a catalog this Console was never built with.
  const other: ResourceTypeCatalog = {
    types: ['counter', 'file', 'jdbc-pool', 'openai-compatible'],
    openAi: {
      endpoints: [
        { id: 'b.two', group: 'b', method: 'GET', path: '/b', request: 'none', response: 'json', streams: false, defaultEnabled: false, stateful: false },
        { id: 'a.one', group: 'a', method: 'POST', path: '/a', request: 'json', response: 'json', streams: true, defaultEnabled: true, stateful: false },
      ],
      requestParameters: [
        { name: 'top_q', kind: 'number', ceiling: true },
        { name: 'style', kind: 'object', ceiling: false },
        { name: 'words', kind: 'textOrList', ceiling: false },
      ],
    },
    jdbc: { databases: [{ kind: 'otherdb', properties: [] }, { kind: 'postgresql', properties: [] }] },
  };

  test('a new resource starts from what the catalog says: its default entries, its parameters, its first database kind', () => {
    expect(formOf('openai-compatible', other)!.empty()).toMatchObject({ endpoints: ['a.one'] });
    expect(Object.keys(formOf('openai-compatible', other)!.empty().parameters)).toEqual(['top_q', 'style', 'words']);
    expect(formOf('jdbc-pool', other)!.empty().kind).toBe('otherdb');
  });

  test('the settings follow the catalog: entries in its order, each default read as the kind it says', () => {
    const form = formOf('openai-compatible', other)!;
    const parameters = {
      top_q: { value: '0.5', locked: false, max: '0.9' },
      style: { value: '{"x":1}', locked: true, max: '' },
      words: { value: '["a","b"]', locked: false, max: '' },
    };

    const made = form.toSettings({ ...form.empty(), baseUrl: 'http://llm/v1', endpoints: ['a.one', 'b.two'], parameters }, {});

    expect(made).toEqual({
      settings: {
        baseUrl: 'http://llm/v1',
        endpoints: ['b.two', 'a.one'],
        defaults: { top_q: 0.5, style: { x: 1 }, words: ['a', 'b'] },
        lockedParameters: ['style'],
        maxValues: { top_q: 0.9 },
      },
    });
  });
});

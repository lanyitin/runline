import { describe, expect, test } from 'vitest';
import { formTypes, settingsSummary, takesSecret } from './resource-types';

describe('the summary of the settings of a resource', () => {
  test('of a counter is nothing: it has no settings', () => {
    expect(settingsSummary('counter', {})).toEqual([]);
  });

  test('of a file is its path under the resource root', () => {
    expect(settingsSummary('file', { path: 'logs/out.txt' })).toEqual([{ field: 'path', value: 'logs/out.txt' }]);
  });

  test('of an openai-compatible service is its root address, with the organization and project when set', () => {
    expect(settingsSummary('openai-compatible', { baseUrl: 'http://llm:8000/v1', endpoints: ['models.list'] })).toEqual([
      { field: 'baseUrl', value: 'http://llm:8000/v1' },
    ]);
    expect(
      settingsSummary('openai-compatible', { baseUrl: 'https://api/v1', organization: 'org-1', project: 'p-2' }),
    ).toEqual([
      { field: 'baseUrl', value: 'https://api/v1' },
      { field: 'organization', value: 'org-1' },
      { field: 'project', value: 'p-2' },
    ]);
  });

  test('of a jdbc-pool is the kind of database, where it is and the account', () => {
    expect(
      settingsSummary('jdbc-pool', {
        kind: 'postgresql',
        host: 'db.internal',
        port: 5432,
        database: 'orders',
        username: 'reader',
        connectionsPerRun: 2,
      }),
    ).toEqual([
      { field: 'kind', value: 'postgresql' },
      { field: 'host', value: 'db.internal' },
      { field: 'port', value: '5432' },
      { field: 'database', value: 'orders' },
      { field: 'username', value: 'reader' },
    ]);
  });

  test('leaves out a field that is not there or not a plain value, and knows nothing of a type it does not know', () => {
    expect(settingsSummary('file', {})).toEqual([]);
    expect(settingsSummary('file', { path: { nested: true } })).toEqual([]);
    expect(settingsSummary('quantum', { path: 'x' })).toEqual([]);
  });
});

describe('the types of resources', () => {
  test('a form is offered for a counter only, until the forms of the other types come (WI-50)', () => {
    expect(formTypes).toEqual(['counter']);
  });

  test('the types that refer to a secret are jdbc-pool and openai-compatible', () => {
    expect(['counter', 'file', 'jdbc-pool', 'openai-compatible'].filter(takesSecret)).toEqual([
      'jdbc-pool',
      'openai-compatible',
    ]);
  });
});

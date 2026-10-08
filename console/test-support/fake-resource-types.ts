// The resource types of the Fake Engine (08-api.md: `GET /api/v1/resource-types`, ADR-021): the
// closed set of types and the choices the Engine fixes for their settings. As in the Engine, this is
// the one description: the Fake answers it from here and its settings rules (fake-resource-settings.ts)
// accept what is here and nothing else. The contract tests hold it to the Engine's, and against a
// real Engine require the two answers to be the same.

export interface EndpointEntry {
  id: string;
  group: string;
  method: 'GET' | 'POST' | 'DELETE';
  path: string;
  request: 'none' | 'json' | 'multipart';
  response: 'json' | 'binary';
  streams: boolean;
  defaultEnabled: boolean;
  stateful: boolean;
}

export type ParameterKind = 'number' | 'text' | 'textOrList' | 'object';

export interface RequestParameter {
  name: string;
  kind: ParameterKind;
  ceiling: boolean;
}

export type PropertyRule =
  | { rule: 'text'; maxLength: number }
  | { rule: 'oneOf'; values: string[] }
  | { rule: 'pattern'; pattern: string };

export type DatabaseProperty = { name: string } & PropertyRule;

export interface DatabaseKind {
  kind: string;
  properties: DatabaseProperty[];
}

const entry = (
  id: string,
  group: string,
  method: EndpointEntry['method'],
  path: string,
  request: EndpointEntry['request'],
  more: Partial<Pick<EndpointEntry, 'response' | 'streams' | 'defaultEnabled' | 'stateful'>> = {},
): EndpointEntry => ({
  id,
  group,
  method,
  path,
  request,
  response: more.response ?? 'json',
  streams: more.streams ?? false,
  defaultEnabled: more.defaultEnabled ?? false,
  stateful: more.stateful ?? false,
});

/** The entries that can be enabled, in the Engine's order. */
export const ENDPOINTS: EndpointEntry[] = [
  entry('chat.completions', 'chat', 'POST', '/chat/completions', 'json', { streams: true, defaultEnabled: true }),
  entry('completions', 'completions', 'POST', '/completions', 'json', { streams: true, defaultEnabled: true }),
  entry('embeddings', 'embeddings', 'POST', '/embeddings', 'json', { defaultEnabled: true }),
  entry('models.list', 'models', 'GET', '/models', 'none', { defaultEnabled: true }),
  entry('models.retrieve', 'models', 'GET', '/models/{model}', 'none', { defaultEnabled: true }),
  entry('responses.create', 'responses', 'POST', '/responses', 'json', { streams: true, stateful: true }),
  entry('responses.retrieve', 'responses', 'GET', '/responses/{id}', 'none'),
  entry('responses.delete', 'responses', 'DELETE', '/responses/{id}', 'none', { stateful: true }),
  entry('responses.cancel', 'responses', 'POST', '/responses/{id}/cancel', 'none', { stateful: true }),
  entry('responses.input_items', 'responses', 'GET', '/responses/{id}/input_items', 'none'),
  entry('moderations', 'moderations', 'POST', '/moderations', 'json'),
  entry('rerank', 'rerank', 'POST', '/rerank', 'json'),
  entry('reranking', 'rerank', 'POST', '/reranking', 'json'),
  entry('images.generations', 'images', 'POST', '/images/generations', 'json'),
  entry('images.edits', 'images', 'POST', '/images/edits', 'multipart'),
  entry('images.variations', 'images', 'POST', '/images/variations', 'multipart'),
  entry('audio.speech', 'audio', 'POST', '/audio/speech', 'json', { response: 'binary', streams: true }),
  entry('audio.transcriptions', 'audio', 'POST', '/audio/transcriptions', 'multipart'),
  entry('audio.translations', 'audio', 'POST', '/audio/translations', 'multipart'),
  entry('files.create', 'files', 'POST', '/files', 'multipart', { stateful: true }),
  entry('files.list', 'files', 'GET', '/files', 'none'),
  entry('files.retrieve', 'files', 'GET', '/files/{id}', 'none'),
  entry('files.delete', 'files', 'DELETE', '/files/{id}', 'none', { stateful: true }),
  entry('files.content', 'files', 'GET', '/files/{id}/content', 'none', { response: 'binary' }),
  entry('batches.create', 'batches', 'POST', '/batches', 'json', { stateful: true }),
  entry('batches.list', 'batches', 'GET', '/batches', 'none'),
  entry('batches.retrieve', 'batches', 'GET', '/batches/{id}', 'none'),
  entry('batches.cancel', 'batches', 'POST', '/batches/{id}/cancel', 'none', { stateful: true }),
];

const parameter = (name: string, kind: ParameterKind = 'number'): RequestParameter => ({
  name,
  kind,
  ceiling: kind === 'number',
});

/** The parameters a resource may default, lock or cap, in the Engine's order. */
export const REQUEST_PARAMETERS: RequestParameter[] = [
  parameter('model', 'text'),
  parameter('temperature'),
  parameter('top_p'),
  parameter('top_k'),
  parameter('min_p'),
  parameter('max_tokens'),
  parameter('max_completion_tokens'),
  parameter('max_output_tokens'),
  parameter('stop', 'textOrList'),
  parameter('seed'),
  parameter('response_format', 'object'),
  parameter('presence_penalty'),
  parameter('frequency_penalty'),
  parameter('repeat_penalty'),
  parameter('n'),
  parameter('reasoning_effort', 'text'),
];

/** The database kinds of `jdbc-pool`, each with the extra properties it allows. */
export const DATABASES: DatabaseKind[] = [
  {
    kind: 'postgresql',
    properties: [
      { name: 'ApplicationName', rule: 'text', maxLength: 64 },
      {
        name: 'currentSchema',
        rule: 'pattern',
        pattern: '[A-Za-z_][A-Za-z0-9_$]{0,62}(?:,[A-Za-z_][A-Za-z0-9_$]{0,62}){0,7}',
      },
      { name: 'tcpKeepAlive', rule: 'oneOf', values: ['true', 'false'] },
    ],
  },
];

const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;

/** Whether [value] is what [rule] accepts: the whole value, as the Engine checks it. */
export function accepts(rule: PropertyRule, value: string): boolean {
  switch (rule.rule) {
    case 'text':
      return value.length <= rule.maxLength && !CONTROL.test(value);
    case 'oneOf':
      return rule.values.includes(value);
    case 'pattern':
      return new RegExp(`^(?:${rule.pattern})$`).test(value);
  }
}

/** The answer of `GET /api/v1/resource-types`. */
export const RESOURCE_TYPES_ANSWER = {
  types: [
    { type: 'counter' },
    { type: 'file' },
    { type: 'jdbc-pool', databases: DATABASES },
    { type: 'openai-compatible', endpoints: ENDPOINTS, requestParameters: REQUEST_PARAMETERS },
  ],
};

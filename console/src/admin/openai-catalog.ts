// What the Engine lets an `openai-compatible` resource enable and fix (08-api.md: `openai-compatible`
// 型別; ADR-019 decision 4): the endpoint catalog, in the Engine's order, with the method, the path
// under the base address and whether a new resource has it enabled; and the request parameters a
// resource may give a default, lock or put a ceiling on. The Console is served by the Engine it is
// built with (ADR-015), so this is the catalog of that Engine. The Engine still decides: an entry it
// does not have is refused as `invalid_endpoint`.

export interface CatalogEntry {
  id: string;
  method: string;
  path: string;
  /** Enabled for a new resource unless the admin says otherwise. */
  byDefault: boolean;
}

const entry = (id: string, method: string, path: string, byDefault = false): CatalogEntry => ({
  id,
  method,
  path,
  byDefault,
});

export const ENDPOINTS: CatalogEntry[] = [
  entry('chat.completions', 'POST', '/chat/completions', true),
  entry('completions', 'POST', '/completions', true),
  entry('embeddings', 'POST', '/embeddings', true),
  entry('models.list', 'GET', '/models', true),
  entry('models.retrieve', 'GET', '/models/{model}', true),
  entry('responses.create', 'POST', '/responses'),
  entry('responses.retrieve', 'GET', '/responses/{id}'),
  entry('responses.delete', 'DELETE', '/responses/{id}'),
  entry('responses.cancel', 'POST', '/responses/{id}/cancel'),
  entry('responses.input_items', 'GET', '/responses/{id}/input_items'),
  entry('moderations', 'POST', '/moderations'),
  entry('rerank', 'POST', '/rerank'),
  entry('reranking', 'POST', '/reranking'),
  entry('images.generations', 'POST', '/images/generations'),
  entry('images.edits', 'POST', '/images/edits'),
  entry('images.variations', 'POST', '/images/variations'),
  entry('audio.speech', 'POST', '/audio/speech'),
  entry('audio.transcriptions', 'POST', '/audio/transcriptions'),
  entry('audio.translations', 'POST', '/audio/translations'),
  entry('files.create', 'POST', '/files'),
  entry('files.list', 'GET', '/files'),
  entry('files.retrieve', 'GET', '/files/{id}'),
  entry('files.delete', 'DELETE', '/files/{id}'),
  entry('files.content', 'GET', '/files/{id}/content'),
  entry('batches.create', 'POST', '/batches'),
  entry('batches.list', 'GET', '/batches'),
  entry('batches.retrieve', 'GET', '/batches/{id}'),
  entry('batches.cancel', 'POST', '/batches/{id}/cancel'),
];

/** The kind of value of a request parameter: a number, text, or JSON (a list or an object). */
export type ParameterKind = 'number' | 'text' | 'json';

export interface RequestParameter {
  name: string;
  kind: ParameterKind;
}

const parameter = (name: string, kind: ParameterKind = 'number'): RequestParameter => ({ name, kind });

/**
 * The parameters a resource may default, lock or put a ceiling on, in the order of 08-api.md. A
 * ceiling is for the numbers only. `stop` is text, or a list of JSON.
 */
export const PARAMETERS: RequestParameter[] = [
  parameter('model', 'text'),
  parameter('temperature'),
  parameter('top_p'),
  parameter('top_k'),
  parameter('min_p'),
  parameter('max_tokens'),
  parameter('max_completion_tokens'),
  parameter('max_output_tokens'),
  parameter('stop', 'json'),
  parameter('seed'),
  parameter('response_format', 'json'),
  parameter('presence_penalty'),
  parameter('frequency_penalty'),
  parameter('repeat_penalty'),
  parameter('n'),
  parameter('reasoning_effort', 'text'),
];

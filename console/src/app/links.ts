// The addresses of the pages of one thing. A name that a pipeline may put a dot in is never a segment
// of the path (the Engine would take it for a file): it is in the query. Nothing here is a
// credential, and no address may hold one (ADR-017).

export const pipelineHref = (contentHash: string, pipeline: string) =>
  `/pipelines/${encodeURIComponent(contentHash)}?${new URLSearchParams({ pipeline })}`;

export const runHref = (runId: string) => `/runs/${encodeURIComponent(runId)}`;

/** The page that creates a run, with the pipeline chosen and, if given, the parameters filled in. */
export function newRunHref(
  contentHash: string,
  pipeline: string,
  parameters: Record<string, string> = {},
): string {
  const query = new URLSearchParams({ contentHash, pipeline });
  for (const [name, value] of Object.entries(parameters)) query.set(`param.${name}`, value);
  return `/runs/new?${query}`;
}

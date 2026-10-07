// The addresses of the pages of one thing. A name that a pipeline may put a dot in is never a segment
// of the path (the Engine would take it for a file): it is in the query. Nothing here is a
// credential, and no address may hold one (ADR-017).

// A version is a content hash and the person who uploaded it (ADR-020): the same bytes uploaded by
// two people are two versions, so a link to one carries `uploader` next to the hash. The name is in
// the query, as the names of pipelines and triggers are.
export const pipelineHref = (contentHash: string, pipeline: string | null, uploader?: string) =>
  `/pipelines/${encodeURIComponent(contentHash)}?${new URLSearchParams({
    ...(pipeline === null ? {} : { pipeline }),
    ...(uploader === undefined ? {} : { uploader }),
  })}`;

export const runHref = (runId: string) => `/runs/${encodeURIComponent(runId)}`;

/** The page that creates a run, with the pipeline chosen and, if given, the parameters filled in. */
export function newRunHref(
  contentHash: string,
  pipeline: string,
  parameters: Record<string, string> = {},
  uploader?: string,
): string {
  const query = new URLSearchParams({ contentHash, pipeline });
  if (uploader !== undefined) query.set('uploader', uploader);
  for (const [name, value] of Object.entries(parameters)) query.set(`param.${name}`, value);
  return `/runs/new?${query}`;
}

/** The page of a trigger, and the page that changes it: the name is in the query (it may have a dot). */
export const triggerHref = (name: string) => `/triggers/detail?${new URLSearchParams({ name })}`;
export const triggerEditHref = (name: string) => `/triggers/edit?${new URLSearchParams({ name })}`;

/** The page that makes a trigger, with the version and pipeline it is to run, if they are known. */
export function newTriggerHref(contentHash?: string, pipeline?: string, uploader?: string): string {
  if (contentHash === undefined || pipeline === undefined) return '/triggers/new';
  return `/triggers/new?${new URLSearchParams({ contentHash, pipeline, ...(uploader === undefined ? {} : { uploader }) })}`;
}

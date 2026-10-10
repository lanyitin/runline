// The calls of the resource roles and bindings (08-api.md, ADR-025), for a developer and an admin
// alike: read the roles of a pipeline of a version with what each is bound to, bind a role to an
// existing resource, unbind it. They go through the session as every call does; a name that a
// person typed goes into the address as one piece. An admin who can see several uploaders of the
// content names whose bindings with `uploader`; every answer is the state after the change.

import { jsonCall, succeed, type Transport } from './call.ts';
import { parseResourceBindings, type ResourceBindings } from './bindings-model.ts';

const q = encodeURIComponent;
const uploaderQuery = (uploader: string | undefined) =>
  uploader === undefined ? '' : `?${new URLSearchParams({ uploader })}`;

export function createBindingsApi(transport: Transport) {
  const json = jsonCall(transport);
  const base = (contentHash: string, pipeline: string) =>
    `/api/v1/definitions/${q(contentHash)}/${q(pipeline)}/resource-bindings`;

  return {
    async resourceBindings(contentHash: string, pipeline: string, uploader?: string): Promise<ResourceBindings> {
      return parseResourceBindings(
        await succeed(await json(`${base(contentHash, pipeline)}${uploaderQuery(uploader)}`)),
      );
    },
    async bindResource(
      contentHash: string,
      pipeline: string,
      role: string,
      resource: string,
      uploader?: string,
    ): Promise<ResourceBindings> {
      return parseResourceBindings(
        await succeed(
          await json(`${base(contentHash, pipeline)}/${q(role)}${uploaderQuery(uploader)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ resource }),
          }),
        ),
      );
    },
    /** Takes the binding away: the role comes to the resource of its own name again. */
    async unbindResource(
      contentHash: string,
      pipeline: string,
      role: string,
      uploader?: string,
    ): Promise<ResourceBindings> {
      return parseResourceBindings(
        await succeed(
          await json(`${base(contentHash, pipeline)}/${q(role)}${uploaderQuery(uploader)}`, {
            method: 'DELETE',
          }),
        ),
      );
    },
  };
}

// The calls of the views of a pipeline (08-api.md: Pipeline view, ADR-033), for a developer and an
// admin alike: the views a version declares, and one page of the rows of one of them. Both are GETs
// that change nothing; they go through the session as every call does. A name or a value that a
// person typed goes into the address as one piece. An admin who can see several uploaders of the
// content names whose version with `uploader`.

import { jsonCall, succeed, type Transport } from './call.ts';
import { parseViewList, parseViewPage, type ViewList, type ViewPage } from './views-model.ts';

const q = encodeURIComponent;

export interface ReadViewOptions {
  uploader?: string;
  /** Rows to a page; the Engine's own when left out. */
  limit?: number;
  /** Rows to skip. */
  offset?: number;
  /** The values of the view's parameters by name; one without a value (empty) is left out. */
  parameters?: Record<string, string>;
}

export function createViewsApi(transport: Transport) {
  const json = jsonCall(transport);
  const base = (contentHash: string, pipeline: string) =>
    `/api/v1/definitions/${q(contentHash)}/${q(pipeline)}/views`;

  return {
    async pipelineViews(contentHash: string, pipeline: string, uploader?: string): Promise<ViewList> {
      const query = uploader === undefined ? '' : `?${new URLSearchParams({ uploader })}`;
      return parseViewList(await succeed(await json(`${base(contentHash, pipeline)}${query}`)));
    },

    async readView(
      contentHash: string,
      pipeline: string,
      view: string,
      options: ReadViewOptions = {},
    ): Promise<ViewPage> {
      const query = new URLSearchParams();
      if (options.uploader !== undefined) query.set('uploader', options.uploader);
      if (options.limit !== undefined) query.set('limit', String(options.limit));
      if (options.offset !== undefined) query.set('offset', String(options.offset));
      for (const [name, value] of Object.entries(options.parameters ?? {})) {
        if (value !== '') query.set(`param.${name}`, value);
      }
      const text = query.toString();
      return parseViewPage(
        await succeed(await json(`${base(contentHash, pipeline)}/${q(view)}${text ? `?${text}` : ''}`)),
      );
    },
  };
}

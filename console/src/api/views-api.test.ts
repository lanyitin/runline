import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { viewsPipeline } from '../../test-support/fake-jars';
import { ApiFailure } from './failure';

// The views of a pipeline (ADR-033) as the Console asks for them and reads them, held against the
// Fake Engine (which the contract tests hold to the real one).

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };
const HASH = 'ef'.repeat(32);

const start = async (enabled = true) => {
  app = await createTestApp({ identity: ada });
  const backend = app.engine.backend;
  backend.seedArtifact('ada', [viewsPipeline], { contentHash: HASH });
  backend.resources.define('articles-db', { type: 'jdbc-pool', enabled });
  backend.bindings.seed('ada', 'demo-views', 'view-db', 'articles-db');
  backend.views.seedDemo('articles-db');
  return app.context.api;
};

describe('the list of the views of a pipeline', () => {
  test('is read with what each takes and shows, and whether it can be read now', async () => {
    const api = await start();

    const read = await api.pipelineViews(HASH, 'demo-views', 'ada');

    expect(read.uploader).toBe('ada');
    expect(read.views.map((v) => [v.id, v.title, v.status])).toEqual([
      ['feeds', 'Feeds', 'ready'],
      ['articles', 'Articles', 'ready'],
    ]);
    expect(read.views[0].columns[0]).toEqual({
      name: 'name',
      label: 'Feed',
      type: 'TEXT',
      tones: [],
      drill: { view: 'articles', parameter: 'feed', from: 'name' },
    });
    expect(read.views[1].parameters).toEqual([
      { name: 'feed', type: 'TEXT', required: false, options: [] },
      { name: 'state', type: 'CHOICE', required: false, options: ['kept', 'failed', 'skipped'] },
    ]);
    expect(read.views[1].columns[2].tones).toEqual([
      { value: 'kept', tone: 'POSITIVE' },
      { value: 'failed', tone: 'NEGATIVE' },
    ]);
  });

  test('says why a view cannot be read: the problem of its role', async () => {
    const api = await start(false);

    const read = await api.pipelineViews(HASH, 'demo-views', 'ada');

    expect(read.views.map((v) => v.status)).toEqual(['disabled', 'disabled']);
  });

  test('is a failure with the Engine\'s status for a version that is not there', async () => {
    const api = await start();

    await expect(api.pipelineViews('0'.repeat(64), 'demo-views')).rejects.toMatchObject({
      status: 404,
      body: { error: 'definition_not_found' },
    });
  });
});

describe('a page of a view', () => {
  test('is read with its columns, its rows as they are and whether more follow', async () => {
    const api = await start();

    const page = await api.readView(HASH, 'demo-views', 'articles', { uploader: 'ada', limit: 3 });

    expect(page.view).toBe('articles');
    expect(page.columns.map((c) => c.name)).toEqual(['id', 'title', 'state', 'fetched_at', 'feed']);
    expect(page.rows.map((r) => r[0])).toEqual([7, 6, 5]);
    expect(page.rows[0]).toEqual([7, 'Eta', 'kept', '2026-10-07T08:00:00Z', null]);
    expect([page.hasMore, page.limit, page.offset]).toEqual([true, 3, 0]);
  });

  test('is asked for with the offset and the parameters under param.<name>; a parameter without a value is left out', async () => {
    const api = await start();

    const page = await api.readView(HASH, 'demo-views', 'articles', {
      uploader: 'ada',
      limit: 2,
      offset: 2,
      parameters: { feed: '', state: 'kept' },
    });

    expect(page.rows.map((r) => r[0])).toEqual([3, 1]);
    expect([page.hasMore, page.offset]).toEqual([false, 2]);
  });

  test('puts a value that a person typed into the request as a value, however it looks', async () => {
    const api = await start();

    const page = await api.readView(HASH, 'demo-views', 'articles', {
      uploader: 'ada',
      parameters: { feed: 'a&b=c#d%20e' },
    });

    expect(page.rows).toEqual([]);
  });

  test('is a failure with the status and body of the Engine when the request is refused', async () => {
    const api = await start();

    const error = await api
      .readView(HASH, 'demo-views', 'articles', { uploader: 'ada', parameters: { state: 'nope' } })
      .catch((e) => e);

    expect(error).toBeInstanceOf(ApiFailure);
    expect(error.status).toBe(400);
    expect(error.body).toMatchObject({ error: 'invalid_view_parameter', parameter: 'state', reason: 'not_an_option' });
  });
});

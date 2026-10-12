// The jars the Fake Engine understands. The real Engine finds the pipelines of a jar by analysing
// the classes in it; the Fake does not analyse anything, so a jar made for it says in one entry
// which pipelines it holds and what the analysis would have judged of them. Everything else about
// uploading (the hash, the size, who uploaded, 200 and 201, the refusals) is the Engine's.
//
// `demoJars()` has the same pipelines as the sample jars of dev/sample-pipelines, which the contract
// tests upload to a real Engine: the same names and parameters, so that one test fits both.

import { storedZip } from './zip';

/** The entry of a jar of the Fake that lists its pipelines. The real Engine ignores it. */
export const FAKE_PIPELINES_ENTRY = 'fake-engine/pipelines.json';

export interface FakeParameter {
  name: string;
  required: boolean;
  default?: string | null;
}

export interface FakeReason {
  kind: string;
  category?: string | null;
  className?: string | null;
  member?: string | null;
  path?: string[];
  detail?: string | null;
}

/**
 * A view as a jar of the Fake declares it (08-api.md: view 宣告). The Fake does not run a query: the
 * rows it answers are those of the [table] in the database of the resource (test-support/fake-views.ts),
 * and a parameter filters the rows on the column of its own name.
 */
export interface FakeView {
  id: string;
  title: string;
  description?: string;
  resource: string;
  table: string;
  parameters?: Array<{
    name: string;
    type: 'TEXT' | 'INTEGER' | 'CHOICE';
    required?: boolean;
    options?: string[];
  }>;
  columns: Array<{
    name: string;
    label: string;
    type?: 'TEXT' | 'INTEGER' | 'TIME' | 'STATUS';
    tones?: Array<{ value: string; tone: 'POSITIVE' | 'NEUTRAL' | 'NEGATIVE' }>;
    drill?: { view: string; parameter: string; from: string } | null;
  }>;
  sort?: Array<{ column: string; descending?: boolean }>;
}

export interface FakePipeline {
  name: string;
  className: string;
  parameters?: FakeParameter[];
  files?: Array<{ scope: string; mode: string }>;
  networkUnrestricted?: boolean;
  processesUnrestricted?: boolean;
  resources?: string[];
  /** The type expected of a declared resource, by its name; only those declared with a type. */
  resourceTypes?: Record<string, string>;
  /** What a declared resource is for, by its name; only those declared with a purpose (ADR-025). */
  resourcePurposes?: Record<string, string>;
  /** The read-only views the pipeline declares (ADR-033). */
  views?: FakeView[];
  reasons?: FakeReason[];
  /** Classes the pipeline refers to: UNSAFE for as long as no allow-list entry covers one. */
  references?: string[];
}

/** A jar of the Fake with these pipelines (and a class file's name, so that it looks like a jar). */
export function fakeJar(pipelines: FakePipeline[], extra: Record<string, string> = {}): Uint8Array {
  return storedZip({
    'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n',
    [FAKE_PIPELINES_ENTRY]: JSON.stringify(pipelines),
    ...extra,
  });
}

const slow: FakePipeline = {
  name: 'demo-slow',
  className: 'samples.slow.SlowPipeline',
  parameters: [
    { name: 'label', required: false, default: 'demo' },
    { name: 'steps', required: false, default: '30' },
    { name: 'delayMillis', required: false, default: '1000' },
  ],
  // As the sample pipelines print: the allow-list has to cover this class, as it does by default.
  references: ['java.io.PrintStream'],
};
const failing: FakePipeline = {
  name: 'demo-failing',
  className: 'samples.failing.FailingPipeline',
  parameters: [{ name: 'reason', required: false, default: 'the demo failed on purpose' }],
  references: ['java.io.PrintStream'],
};
const unsafe: FakePipeline = {
  name: 'demo-unsafe',
  className: 'samples.unsafe.UnsafePipeline',
  networkUnrestricted: true,
  processesUnrestricted: true,
  reasons: [
    { kind: 'UNRESTRICTED_ACCESS', category: 'NETWORK' },
    { kind: 'UNRESTRICTED_ACCESS', category: 'PROCESSES' },
    {
      kind: 'NOT_ALLOW_LISTED',
      className: 'java.io.File',
      path: ['samples.unsafe.UnsafePipeline', 'java.io.File'],
    },
  ],
};
const resource: FakePipeline = {
  name: 'demo-resource',
  className: 'samples.resource.ResourcePipeline',
  resources: ['demo-printer'],
  references: ['java.io.PrintStream'],
};

/** As `samples.intake.IntakePipeline`: `file` is required and has no default (ADR-036). */
const intake: FakePipeline = {
  name: 'demo-intake',
  className: 'samples.intake.IntakePipeline',
  parameters: [
    { name: 'file', required: true, default: null },
    { name: 'language', required: false, default: 'en' },
  ],
  references: ['java.io.PrintStream'],
};

const typed: FakePipeline = {
  name: 'demo-typed',
  className: 'samples.typed.TypedPipeline',
  resources: ['demo-printer'],
  resourceTypes: { 'demo-printer': 'file' },
  references: ['java.io.PrintStream'],
};

/** As `samples.roles.RolesPipeline`: three roles, two typed with a purpose and one by name only. */
const roles: FakePipeline = {
  name: 'demo-roles',
  className: 'samples.roles.RolesPipeline',
  resources: ['scratch', 'report-store', 'ticket-counter'],
  resourceTypes: { 'report-store': 'file', 'ticket-counter': 'counter' },
  resourcePurposes: {
    'report-store': 'Where the weekly report is written',
    'ticket-counter': 'Limits how many tickets are handled at once',
  },
  references: ['java.io.PrintStream'],
};

/** As `samples.views.ViewsPipeline`: two views over the role `view-db`, a `jdbc-pool` it fills when it runs. */
export const viewsPipeline: FakePipeline = {
  name: 'demo-views',
  className: 'samples.views.ViewsPipeline',
  resources: ['view-db'],
  resourceTypes: { 'view-db': 'jdbc-pool' },
  resourcePurposes: { 'view-db': 'Where the demo articles are kept' },
  views: [
    {
      id: 'feeds',
      title: 'Feeds',
      description: 'The feeds and how many articles each has',
      resource: 'view-db',
      table: 'feed',
      columns: [
        {
          name: 'name',
          label: 'Feed',
          type: 'TEXT',
          drill: { view: 'articles', parameter: 'feed', from: 'name' },
        },
        { name: 'items', label: 'Articles', type: 'INTEGER' },
      ],
      sort: [{ column: 'name' }],
    },
    {
      id: 'articles',
      title: 'Articles',
      description: 'What was fetched, and what became of it',
      resource: 'view-db',
      table: 'article',
      parameters: [
        { name: 'feed', type: 'TEXT' },
        { name: 'state', type: 'CHOICE', options: ['kept', 'failed', 'skipped'] },
      ],
      columns: [
        { name: 'id', label: 'ID', type: 'INTEGER' },
        { name: 'title', label: 'Title', type: 'TEXT' },
        {
          name: 'state',
          label: 'State',
          type: 'STATUS',
          tones: [
            { value: 'kept', tone: 'POSITIVE' },
            { value: 'failed', tone: 'NEGATIVE' },
          ],
        },
        { name: 'fetched_at', label: 'Fetched', type: 'TIME' },
        { name: 'feed', label: 'Feed', type: 'TEXT' },
      ],
      sort: [{ column: 'fetched_at', descending: true }],
    },
  ],
};

export interface DemoJars {
  slow: Uint8Array;
  failing: Uint8Array;
  unsafe: Uint8Array;
  resource: Uint8Array;
  /** `demo-typed`: declares `demo-printer` and expects it to be a `file`. */
  typed: Uint8Array;
  /** `demo-roles`: the roles `report-store` (file), `ticket-counter` (counter) and `scratch` (any type). */
  roles: Uint8Array;
  /** `demo-views`: two views over the role `view-db` (a `jdbc-pool`) and a run that fills the database. */
  views: Uint8Array;
  /** `demo-intake`: a required parameter `file` without a default, and an optional `language`. */
  intake: Uint8Array;
  /** A zip file with nothing in it that is a pipeline. */
  noPipeline: Uint8Array;
  /** Bytes that are no zip file at all. */
  junk: Uint8Array;
}

export function demoJars(): DemoJars {
  return {
    slow: fakeJar([slow]),
    failing: fakeJar([failing]),
    unsafe: fakeJar([unsafe]),
    resource: fakeJar([resource]),
    typed: fakeJar([typed]),
    roles: fakeJar([roles]),
    views: fakeJar([viewsPipeline]),
    intake: fakeJar([intake]),
    noPipeline: storedZip({ 'hello.txt': 'there is no pipeline here' }),
    junk: new TextEncoder().encode('this is not a jar'),
  };
}

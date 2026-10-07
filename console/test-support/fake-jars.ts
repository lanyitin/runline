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

const typed: FakePipeline = {
  name: 'demo-typed',
  className: 'samples.typed.TypedPipeline',
  resources: ['demo-printer'],
  resourceTypes: { 'demo-printer': 'file' },
  references: ['java.io.PrintStream'],
};

export interface DemoJars {
  slow: Uint8Array;
  failing: Uint8Array;
  unsafe: Uint8Array;
  resource: Uint8Array;
  /** `demo-typed`: declares `demo-printer` and expects it to be a `file`. */
  typed: Uint8Array;
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
    noPipeline: storedZip({ 'hello.txt': 'there is no pipeline here' }),
    junk: new TextEncoder().encode('this is not a jar'),
  };
}

import { afterAll, beforeAll } from 'vitest';
import { FakeEngine } from '../test-support/fake-engine';
import { demoJars } from '../test-support/fake-jars';
import { describeInfoContract } from './info-contract';
import { describePipelinesContract } from './pipelines-contract';
import { describeSystemContract, parseCallers } from './system-contract';

const callers = parseCallers(
  'ada:developer:tok-ada-0123456789,bob:developer:tok-bob-0123456789,root:admin:tok-root-0123456789',
);
const uploadLimitBytes = 1024 * 1024;
let engine: FakeEngine;
beforeAll(async () => {
  engine = await FakeEngine.start(
    {
      version: '0.4.2',
      commitHash: 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70',
      dirty: false,
    },
    { callers, maxUploadBytes: uploadLimitBytes },
  );
});
afterAll(() => engine.stop());

describeInfoContract('the Fake Engine', () => engine.url);
describeSystemContract('the Fake Engine', () => engine.url, () => callers);
describePipelinesContract('the Fake Engine', {
  baseUrl: () => engine.url,
  callers: () => callers,
  jars: demoJars,
  uploadLimitBytes,
});

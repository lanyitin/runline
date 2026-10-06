import { afterAll, beforeAll } from 'vitest';
import { FakeEngine } from '../test-support/fake-engine';
import { describeInfoContract } from './info-contract';
import { describeSystemContract, parseCallers } from './system-contract';

const callers = parseCallers('ada:developer:tok-ada-0123456789,root:admin:tok-root-0123456789');
let engine: FakeEngine;
beforeAll(async () => {
  engine = await FakeEngine.start(
    {
      version: '0.4.2',
      commitHash: 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70',
      dirty: false,
    },
    { callers },
  );
});
afterAll(() => engine.stop());

describeInfoContract('the Fake Engine', () => engine.url);
describeSystemContract('the Fake Engine', () => engine.url, () => callers);

import { afterAll, beforeAll, describe } from 'vitest';
import { FakeEngine } from '../test-support/fake-engine';
import { demoJars } from '../test-support/fake-jars';
import { describeAuthMethodsContract, describeOidcSessionContract } from './auth-contract';
import { describeUsersContract } from './users-contract';
import { describeAdminContract } from './admin-contract';
import { describeBindingsContract } from './bindings-contract';
import { describeInfoContract } from './info-contract';
import { describeResourceTypesContract } from './resource-types-contract';
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
    {
      callers,
      maxUploadBytes: uploadLimitBytes,
      keystore: [
        { alias: 'openai-key', type: 'secret', status: 'found', fingerprint: 'f1' },
        { alias: 'corporate-ca', type: 'trusted_certificate', status: 'found', fingerprint: 'f2' },
      ],
    },
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
describeAdminContract('the Fake Engine', {
  baseUrl: () => engine.url,
  callers: () => callers,
  jars: demoJars,
  uploadLimitBytes,
});
describeBindingsContract('the Fake Engine', {
  baseUrl: () => engine.url,
  callers: () => callers,
  jars: demoJars,
  uploadLimitBytes,
});
describeResourceTypesContract('the Fake Engine', {
  baseUrl: () => engine.url,
  callers: () => callers,
  jars: demoJars,
  uploadLimitBytes,
});

// The Fake with login on and the Fake with login off are two servers: the endpoints exist only on
// the first, as on the Engine.
describeAuthMethodsContract('the Fake Engine without OIDC', {
  baseUrl: () => engine.url,
  oidcEnabled: false,
});

describe('the Fake Engine with OIDC', () => {
  let withOidc: FakeEngine;
  beforeAll(async () => {
    withOidc = await FakeEngine.start(
      { version: '0.4.2', commitHash: 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70', dirty: false },
      { oidc: {}, callers },
    );
  });
  afterAll(() => withOidc.stop());
  describeAuthMethodsContract('the Fake Engine with OIDC', {
    baseUrl: () => withOidc.url,
    oidcEnabled: true,
  });
  describeOidcSessionContract('the Fake Engine with OIDC', {
    baseUrl: () => withOidc.url,
    origin: () => withOidc.url,
    signIn: async () => {
      const name = `person-${Math.random().toString(36).slice(2, 8)}`;
      return { cookie: withOidc.oidc!.signIn({ name, role: 'developer' }), name, role: 'developer' };
    },
  });
  describeUsersContract('the Fake Engine with OIDC', {
    baseUrl: () => withOidc.url,
    origin: () => withOidc.url,
    callers: () => callers,
    signIn: async (role) => {
      const name = `person-${Math.random().toString(36).slice(2, 8)}`;
      return { cookie: withOidc.oidc!.signIn({ name, role }), name, role };
    },
  });
});

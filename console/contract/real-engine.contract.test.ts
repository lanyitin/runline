import { describeInfoContract } from './info-contract';

// Run by `npm run test:contract` against a running, packaged Engine. It is no part of `npm test`:
// it needs an Engine and its PostgreSQL. With no RUNLINE_ENGINE_URL it fails; it is never skipped.
const url = process.env.RUNLINE_ENGINE_URL;
if (!url) {
  throw new Error('RUNLINE_ENGINE_URL is not set: the contract test needs a running Engine');
}

describeInfoContract('the real Engine', () => url);

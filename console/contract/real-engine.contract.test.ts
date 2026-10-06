import { describeInfoContract } from './info-contract';
import { describeSystemContract, parseCallers } from './system-contract';

// Run by `npm run test:contract` against a running, packaged Engine. It is no part of `npm test`:
// it needs an Engine and its PostgreSQL. With no RUNLINE_ENGINE_URL, or no RUNLINE_CONTRACT_TOKENS
// (the Engine's API_TOKENS: `name:role:token,...`, with a developer and an admin in it), it fails;
// it is never skipped.
const url = process.env.RUNLINE_ENGINE_URL;
if (!url) {
  throw new Error('RUNLINE_ENGINE_URL is not set: the contract test needs a running Engine');
}
const tokens = process.env.RUNLINE_CONTRACT_TOKENS;
if (!tokens) {
  throw new Error('RUNLINE_CONTRACT_TOKENS is not set: name:role:token of a developer and an admin');
}
const callers = parseCallers(tokens);

describeInfoContract('the real Engine', () => url);
describeSystemContract('the real Engine', () => url, () => callers);

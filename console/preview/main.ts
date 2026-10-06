// A second entry of the Console, for looking at the signed-in shell in a browser before WI-34
// brings the sign-in: the same application as main.ts, started with an identity that the address
// names (`?as=developer` or `?as=admin`). It is not the entry of the build (`vite build` builds
// index.html only) and is never packed into the Engine; the browser tests build it on their own
// (e2e/preview-server.ts).
import { SettableIdentity, type Role } from '../src/app/identity.svelte';
import { startConsole } from '../src/bootstrap';

const role = new URLSearchParams(location.search).get('as');
const identity = new SettableIdentity(
  role === 'developer' || role === 'admin'
    ? { name: role === 'admin' ? 'Root' : 'Ada', role: role as Role }
    : undefined,
);
startConsole(document.getElementById('app')!, identity);

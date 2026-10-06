import { SettableIdentity } from './app/identity.svelte';
import { startConsole } from './bootstrap';

// Nobody is signed in until WI-34 brings the way to sign in.
startConsole(document.getElementById('app')!, new SettableIdentity());

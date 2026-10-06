<script lang="ts">
  import type { SignInProps } from '../src/auth/method';

  // The sign-in screen of the second method of the tests: an access code, typed in plain sight.
  let { signIn }: SignInProps = $props();
  let code = $state('');
  let refused = $state(false);

  async function open(event: SubmitEvent) {
    event.preventDefault();
    const outcome = await signIn(code);
    refused = !outcome.ok;
  }
</script>

<form onsubmit={open}>
  <label for="access-code">Access code</label>
  <input id="access-code" type="text" bind:value={code} />
  <button type="submit">Open</button>
  {#if refused}<p role="alert">The code was refused.</p>{/if}
</form>

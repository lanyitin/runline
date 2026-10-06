<script lang="ts">
  import Dialog from '../src/ui/Dialog.svelte';

  // A page that opens a dialog from a button, for the tests of the dialog.
  interface Props {
    dismissible?: boolean;
    onclose?: () => void;
  }
  let { dismissible = true, onclose }: Props = $props();
  let open = $state(false);
</script>

<button id="opener" type="button" onclick={() => (open = true)}>Open</button>
{#if open}
  <Dialog
    title="Delete it"
    {dismissible}
    onclose={() => {
      open = false;
      onclose?.();
    }}
  >
    <p id="body">The text of the dialog.</p>
    <input id="field" aria-label="field" />
    {#snippet footer()}
      <button id="last" type="button">Last</button>
    {/snippet}
  </Dialog>
{/if}

<script lang="ts">
  // A button that puts [text] on the clipboard, and says in words whether it did: there is no
  // clipboard on a page served over plain http, and a browser may refuse.
  interface Props {
    text: string;
    label: string;
    copied: string;
    failed: string;
  }
  let { text, label, copied, failed }: Props = $props();

  let status = $state<'idle' | 'copied' | 'failed'>('idle');

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      status = 'copied';
    } catch {
      status = 'failed';
    }
  }
</script>

<button class="copy" type="button" onclick={copy}>{label}</button>
<div role="status" class="copy-status">
  {#if status === 'copied'}{copied}{/if}
  {#if status === 'failed'}{failed}{/if}
</div>

<style>
  .copy {
    font: inherit;
    cursor: pointer;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-md);
    background: var(--surface);
    color: var(--accent-text);
    padding: var(--space-1) var(--space-2);
  }
  .copy-status {
    min-height: 1.25em;
    color: var(--text-secondary);
  }
</style>

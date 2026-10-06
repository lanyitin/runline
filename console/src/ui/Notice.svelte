<script lang="ts">
  import type { Snippet } from 'svelte';

  // A message on the page, in a box whose colour says how much it matters; the words say it too.
  // An urgent one is read out by a screen reader at once (`alert`), the others when it is convenient.
  interface Props {
    tone?: 'info' | 'success' | 'warning' | 'danger';
    /** `alert` for what went wrong because of what the person just did. */
    role?: 'alert' | 'status';
    children: Snippet;
  }
  let { tone = 'info', role = 'status', children }: Props = $props();
</script>

<div class="rl-notice {tone}" {role}>
  {@render children()}
</div>

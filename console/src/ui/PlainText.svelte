<script lang="ts">
  // The one way to show what a pipeline sent: its name, parameters, log lines, failure message and
  // trace, strings from inside a jar (ADR-017). The value goes into the page as a text node, so it
  // can never be read as markup; there is no property that would let it. The build refuses every
  // way of inserting a string as markup (tools/untrusted-content.ts), so this is the way.
  interface Props {
    value: string;
    /** Identifiers, class names, hashes: the monospace face. */
    mono?: boolean;
    /** Log lines and traces: keep line breaks and runs of spaces. */
    multiline?: boolean;
  }
  let { value, mono = false, multiline = false }: Props = $props();
</script>

<span class="plain" class:mono class:multiline>{value}</span>

<style>
  .mono {
    font-family: var(--font-mono);
  }
  .multiline {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
</style>

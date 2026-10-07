<script lang="ts">
  // One field of text of a resource's form, with its label, help and the error said at it. A number
  // is typed as text too (`numeric` only asks for the keyboard of digits): the form reads it.
  interface Props {
    id: string;
    label: string;
    value: string;
    help?: string;
    /** What an empty field means, e.g. the Engine's default. */
    placeholder?: string;
    error?: string;
    numeric?: boolean;
    oninput?: () => void;
  }
  let { id, label, value = $bindable(), help, placeholder, error, numeric = false, oninput }: Props = $props();
</script>

<div class="rl-field">
  <label for={id}>{label}</label>
  <input
    {id}
    class="rl-input mono"
    type="text"
    inputmode={numeric ? 'numeric' : undefined}
    autocomplete="off"
    spellcheck="false"
    {placeholder}
    aria-invalid={error ? 'true' : undefined}
    bind:value
    oninput={() => oninput?.()}
  />
  {#if help}<span class="rl-help">{help}</span>{/if}
  {#if error}<span class="rl-field-error">{error}</span>{/if}
</div>

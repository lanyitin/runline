<script lang="ts">
  import { useApp } from '../app/context';
  import type { Pair } from './resource-forms';

  // A list of names with a value each, none of them secret: the extra headers of a service, the
  // extra connection properties of a database. A row whose name is left empty is not sent. What the
  // Engine refuses of them is said below the list (`error`); what may be given, where the Engine
  // tells it, is said with the help (`allowed`).
  interface Props {
    id: string;
    legend: string;
    help: string;
    /** The names that may be given, in words; none when it is not known. */
    allowed?: string;
    add: string;
    pairs: Pair[];
    error?: string;
    onchange?: () => void;
  }
  let { id, legend, help, allowed, add, pairs = $bindable(), error, onchange }: Props = $props();

  const { i18n } = useApp();
</script>

<fieldset {id} class="pairs">
  <legend>{legend}</legend>
  {#each pairs as pair, index (index)}
    <div class="pair">
      <input
        class="rl-input mono name"
        type="text"
        autocomplete="off"
        spellcheck="false"
        aria-label={i18n.t('resources.form.pair.name')}
        aria-invalid={error ? 'true' : undefined}
        bind:value={pair.name}
        oninput={() => onchange?.()}
      />
      <input
        class="rl-input mono value"
        type="text"
        autocomplete="off"
        spellcheck="false"
        aria-label={i18n.t('resources.form.pair.value')}
        aria-invalid={error ? 'true' : undefined}
        bind:value={pair.value}
        oninput={() => onchange?.()}
      />
      <button
        class="rl-btn small"
        type="button"
        onclick={() => {
          pairs.splice(index, 1);
          onchange?.();
        }}>{i18n.t('resources.form.pair.remove')}</button
      >
    </div>
  {/each}
  <div><button class="rl-btn small" type="button" onclick={() => pairs.push({ name: '', value: '' })}>{add}</button></div>
  <span class="rl-help">{help}</span>
  {#if allowed}<span class="rl-help allowed">{allowed}</span>{/if}
  {#if error}<span class="rl-field-error">{error}</span>{/if}
</fieldset>

<style>
  .pairs {
    display: grid;
    gap: var(--space-2);
    margin: 0;
    padding: 0;
    border: 0;
  }
  .pair {
    display: grid;
    grid-template-columns: 1fr 1fr auto;
    gap: var(--space-2);
    align-items: center;
  }
</style>

<script lang="ts">
  import type { Snippet } from 'svelte';
  import { useApp } from '../app/context';
  import { isLongText } from './long-text';
  import PlainText from './PlainText.svelte';

  // The value of one parameter of a pipeline, and the default it has when left empty (the form of a
  // run and of a trigger share this). A short default is the hint of a line, and shown in a line;
  // a long one, or one with line breaks (a prompt), is shown in full below a text area. Nothing
  // here reads a default or a value as markup: both are text (PlainText, the value of a control).
  interface Props {
    /** The id of the control; the help of the default is `${id}-help`. */
    id: string;
    /** The name of the control in the form, when it has one. */
    fieldName?: string;
    /** The parameter's name, to say whose default it is. */
    parameter: string;
    defaultValue: string | null;
    value: string;
    invalid?: boolean;
    disabled?: boolean;
    /** Says after the default that leaving the value empty uses it. */
    leaveEmptyHint?: boolean;
    oninput?: () => void;
    /** What goes between the control and the default. */
    children?: Snippet;
  }
  let {
    id,
    fieldName,
    parameter,
    defaultValue,
    value = $bindable(),
    invalid = false,
    disabled = false,
    leaveEmptyHint = false,
    oninput,
    children,
  }: Props = $props();
  const { i18n } = useApp();

  const long = $derived(isLongText(defaultValue));
  const helpId = $derived(`${id}-help`);

  function fill() {
    value = defaultValue ?? '';
    oninput?.();
  }
</script>

{#if long}
  <textarea
    {id}
    class="rl-input mono"
    name={fieldName}
    rows="6"
    autocomplete="off"
    spellcheck="false"
    aria-invalid={invalid ? 'true' : undefined}
    aria-describedby={helpId}
    {disabled}
    bind:value
    {oninput}
  ></textarea>
{:else}
  <input
    {id}
    class="rl-input mono"
    type="text"
    name={fieldName}
    autocomplete="off"
    spellcheck="false"
    placeholder={defaultValue ?? ''}
    aria-invalid={invalid ? 'true' : undefined}
    aria-describedby={helpId}
    {disabled}
    bind:value
    {oninput}
  />
{/if}
{@render children?.()}
{#if defaultValue !== null}
  {#if long}
    <div class="rl-help long" id={helpId}>
      <div class="head">
        <span>{i18n.t('createRun.defaultLabel')}</span>
        <button class="rl-btn small" type="button" {disabled} onclick={fill}>
          {i18n.t('createRun.fillDefault')}
        </button>
      </div>
      <!-- A scrolling region must be reachable by keyboard to be read in full. -->
      <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
      <div
        class="default-text"
        role="region"
        tabindex="0"
        aria-label={i18n.t('createRun.defaultOf', { name: parameter })}
      >
        <PlainText value={defaultValue} mono multiline />
      </div>
      {#if leaveEmptyHint}{i18n.t('createRun.leaveEmpty')}{/if}
    </div>
  {:else}
    <span class="rl-help" id={helpId}>
      {i18n.t('createRun.default', { value: defaultValue })}
      {#if leaveEmptyHint}{i18n.t('createRun.leaveEmpty')}{/if}
    </span>
  {/if}
{:else}
  <span class="rl-help" id={helpId}></span>
{/if}

<style>
  textarea {
    resize: vertical;
    min-height: 8rem;
  }
  .long {
    display: grid;
    gap: var(--space-2);
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }
  .default-text {
    max-height: 14rem;
    overflow: auto;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-md, 6px);
    color: var(--text-secondary);
    font-size: var(--text-xs);
  }
</style>

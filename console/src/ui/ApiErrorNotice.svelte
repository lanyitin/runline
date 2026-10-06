<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import { useApp } from '../app/context';
  import { describeApiError } from '../i18n/api-error';
  import PlainText from './PlainText.svelte';

  // What went wrong with a call to the Engine, in words (ADR-015): the text comes from the code of
  // the error, in the language of the screen, never from the Engine's `message`, which is shown apart
  // and only as what the Engine said (it names the class or the entry of a jar). `problems` are one
  // line each. An answer that did not come at all is said as that.
  interface Props {
    failure: ApiFailure;
    /** Show the Engine's own words at once: for an upload, whose message names the entry. */
    expandServerMessage?: boolean;
    /** Lines the page shows itself (for a problem that is a field of its form, say): left out here. */
    leaveOutProblems?: boolean;
  }
  let { failure, expandServerMessage = false, leaveOutProblems = false }: Props = $props();

  const { i18n } = useApp();
  const described = $derived(describeApiError(i18n.translate, failure.status, failure.body));
  const unreachable = $derived(failure.status === 0);
</script>

<div class="rl-notice danger" role="alert">
  <p class="headline">
    {#if unreachable}
      {i18n.t('error.unreachable')}
    {:else}
      {described.message}
    {/if}
  </p>
  {#if !leaveOutProblems && described.problems.length > 0}
    <ul class="problems">
      {#each described.problems as line, index (index)}
        <li><PlainText value={line} /></li>
      {/each}
    </ul>
  {/if}
  {#if !unreachable && (described.serverMessage || described.code || described.errorId)}
    <details open={expandServerMessage}>
      <summary>{i18n.t('error.details')}</summary>
      <dl>
        {#if described.code}
          <dt>{i18n.t('error.code')}</dt>
          <dd><PlainText value={`${described.status} ${described.code}`} mono /></dd>
        {/if}
        {#if described.errorId}
          <dt>{i18n.t('error.errorId')}</dt>
          <dd><PlainText value={described.errorId} mono /></dd>
        {/if}
        {#if described.serverMessage}
          <dt>{i18n.t('error.serverMessage')}</dt>
          <dd><PlainText value={described.serverMessage} multiline /></dd>
        {/if}
      </dl>
    </details>
  {/if}
</div>

<style>
  .headline {
    margin: 0;
    font-weight: 600;
  }
  .problems {
    margin: var(--space-2) 0 0;
    padding-left: var(--space-5);
  }
  details {
    margin-top: var(--space-2);
  }
  summary {
    cursor: pointer;
    font-size: var(--text-xs);
  }
  dl {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: var(--space-1) var(--space-3);
    margin: var(--space-2) 0 0;
    font-size: var(--text-xs);
  }
  dt {
    font-weight: 600;
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
</style>

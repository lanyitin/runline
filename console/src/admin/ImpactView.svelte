<script lang="ts">
  import type { AllowListChange } from '../api/admin-model';
  import { useApp } from '../app/context';
  import { shortHash } from '../engine/info';
  import Badge from '../ui/Badge.svelte';
  import PlainText from '../ui/PlainText.svelte';

  // What a change of the allow-list does to the verdicts (08-api.md: the impact of a change), as the
  // preview says it would and the result says it did: how many pipelines become UNSAFE or SAFE, how
  // many cannot be read again (judged UNSAFE), which lose the permission to run although UNSAFE
  // (it is taken back), each pipeline whose verdict changes, and the entries that become needless.
  interface Props {
    change: AllowListChange;
    /** The change is done: "became" and "was taken back" instead of "would become". */
    applied: boolean;
  }
  let { change, applied }: Props = $props();

  const { i18n } = useApp();
  const impact = $derived(change.impact);
  const revoked = $derived(impact.changes.filter((c) => c.unsafeExecutionRevoked).length);
</script>

<div class="impact">
  <p class="rl-help examined">
    {i18n.t('allow.impact.examined', {
      definitions: impact.examinedDefinitions,
      artifacts: impact.examinedArtifacts,
    })}
  </p>

  <div class="stats">
    <div class="stat unsafe"><span class="n">{impact.becameUnsafe}</span> <span class="what">{i18n.t('allow.impact.unsafe')}</span></div>
    <div class="stat safe"><span class="n">{impact.becameSafe}</span> <span class="what">{i18n.t('allow.impact.safe')}</span></div>
    <div class="stat revoked"><span class="n">{revoked}</span> <span class="what">{i18n.t('allow.impact.revoked')}</span></div>
    <div class="stat unreadable"><span class="n">{impact.unreadable}</span> <span class="what">{i18n.t('allow.impact.unreadable')}</span></div>
  </div>

  {#if revoked > 0}
    <div class="rl-notice warning" role="status">
      {i18n.t(applied ? 'allow.impact.revoked.warning.applied' : 'allow.impact.revoked.warning.preview', { count: revoked })}
    </div>
  {/if}
  {#if impact.unreadable > 0}
    <div class="rl-notice info" role="status">{i18n.t('allow.impact.unreadable.note')}</div>
  {/if}

  {#if impact.changes.length === 0}
    <p class="none">{i18n.t('allow.impact.none')}</p>
  {:else}
    <h3>{i18n.t('allow.impact.changes')}</h3>
    <ul class="changes">
      {#each impact.changes as item (JSON.stringify([item.contentHash, item.uploader, item.pipeline]))}
        <li class="change">
          <span class="pipeline"><PlainText value={item.pipeline} mono /></span>
          <span class="hash rl-mono" title={item.contentHash}>{shortHash(item.contentHash)}</span>
          <span class="uploader"><PlainText value={item.uploader} /></span>
          <span class="verdicts">
            <Badge kind="verdict" value={item.from} />
            <span aria-hidden="true">→</span>
            <Badge kind="verdict" value={item.to} />
          </span>
          {#if item.unsafeExecutionRevoked}
            <span class="revoked tag">{i18n.t('allow.impact.revoked.mark')}</span>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}

  {#if change.redundantEntries.length > 0}
    <div class="redundant">
      <h3>{i18n.t('allow.impact.redundant')}</h3>
      <ul class="plain">
        {#each change.redundantEntries as entry (`${entry.kind}/${entry.name}`)}
          <li><PlainText value={`${entry.kind}: ${entry.name}`} mono /></li>
        {/each}
      </ul>
    </div>
  {/if}

  {#if change.limitations}
    <details class="limitations">
      <summary>{i18n.t('allow.limitations')}</summary>
      <PlainText value={change.limitations} multiline />
    </details>
  {/if}
</div>

<style>
  .impact {
    display: grid;
    gap: var(--space-3);
  }
  .examined {
    margin: 0;
  }
  .stats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
    gap: var(--space-3);
  }
  .stat {
    padding: var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
  }
  .stat .n {
    display: block;
    font-family: var(--font-mono);
    font-size: var(--text-xl);
    font-weight: 600;
  }
  .stat .what {
    color: var(--text-secondary);
    font-size: var(--text-xs);
  }
  .stat.unsafe .n {
    color: var(--danger-text);
  }
  .stat.safe .n {
    color: var(--success-text);
  }
  .stat.revoked .n {
    color: var(--warning-text);
  }
  h3 {
    margin: 0;
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .changes,
  .plain {
    display: grid;
    gap: var(--space-2);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .change {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
  }
  .pipeline {
    font-weight: 600;
  }
  .hash,
  .uploader {
    color: var(--text-muted);
    font-size: var(--text-xs);
  }
  .verdicts {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
  }
  .tag {
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    background: var(--warning-tint);
    color: var(--warning-text);
    font-size: var(--text-2xs);
    font-weight: 700;
  }
  .none {
    margin: 0;
    color: var(--text-secondary);
  }
  .limitations {
    color: var(--text-secondary);
    font-size: var(--text-xs);
  }
</style>

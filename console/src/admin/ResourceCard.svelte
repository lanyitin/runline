<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { CheckResult, Holder, Resource } from '../api/admin-model';
  import { useApp } from '../app/context';
  import { pipelineHref, runHref } from '../app/links';
  import { enumLabel } from '../i18n/enums';
  import { formatDuration } from '../i18n/format';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';
  import { settingsSummary, takesSecret } from './resource-types';

  // One shared resource as the resources page shows it (ADR-019): its type, the settings that are
  // not secret, the alias of its secret and whether the keystore has it (never a value), the last
  // check, how much of it is held, who holds it and who waits, and the pipeline definitions that
  // declare it, each a link to its page. A check is made here, only when the admin asks for it (it
  // may reach a service or a database); the card then shows it as the last check. Changing,
  // deleting and releasing a holder are the page's, which asks first.
  interface Props {
    resource: Resource;
    onchange: () => void;
    ondelete: () => void;
    onrelease: (holder: Holder) => void;
    /** A check was made: the resource, read again, has it as its last check. */
    onchecked: () => void;
  }
  let { resource, onchange, ondelete, onrelease, onchecked }: Props = $props();

  const { i18n, api } = useApp();
  const summary = $derived(settingsSummary(resource.type, resource.settings));
  const checkOutcome = (check: CheckResult) =>
    check.ok
      ? i18n.t('resources.check.passed')
      : i18n.t('resources.check.failed', { failure: enumLabel(i18n.translate, 'checkFailure', check.failure ?? '') });
  const duration = (seconds: number) => formatDuration(Math.round(seconds) * 1000, i18n.locale);

  let checking = $state(false);
  let checkFailure = $state.raw<ApiFailure | null>(null);

  async function check() {
    if (checking) return;
    checking = true;
    checkFailure = null;
    try {
      await api.checkResource(resource.name);
      onchecked();
    } catch (error) {
      checkFailure = error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
    } finally {
      checking = false;
    }
  }
</script>

<article class="rl-card resource">
  <header>
    <h2 class="name"><PlainText value={resource.name} mono /></h2>
    <span class="tags">
      <span class="type tag">{enumLabel(i18n.translate, 'resourceType', resource.type)}</span>
      <span class="state tag {resource.enabled ? 'on' : 'off'}">
        {resource.enabled ? i18n.t('resources.state.enabled') : i18n.t('resources.state.disabled')}
      </span>
    </span>
  </header>

  {#if summary.length > 0}
    <dl class="settings">
      {#each summary as line (line.field)}
        <div>
          <dt>{i18n.translate(`resources.setting.${line.field}`)}</dt>
          <dd><PlainText value={line.value} mono /></dd>
        </div>
      {/each}
    </dl>
  {/if}

  {#if resource.secretAlias !== null || takesSecret(resource.type)}
    <p class="secret">
      <span class="label">{i18n.t('resources.secret')}</span>
      {#if resource.secretAlias !== null}<span class="alias"><PlainText value={resource.secretAlias} mono /></span>{/if}
      <span class="secret-status {resource.secretStatus}">{enumLabel(i18n.translate, 'secretStatus', resource.secretStatus)}</span>
    </p>
  {/if}

  <p class="last-check">
    <span class="label">{i18n.t('resources.lastCheck')}</span>
    {#if resource.lastCheck === null}
      <span class="outcome">{i18n.t('resources.lastCheck.never')}</span>
    {:else}
      <span class="outcome {resource.lastCheck.ok ? 'passed' : 'failed'}">{checkOutcome(resource.lastCheck)}</span>
      <Timestamp iso={resource.lastCheck.checkedAt} />
    {/if}
  </p>

  {#if checkFailure}
    <ApiErrorNotice failure={checkFailure} />
  {/if}

  <div class="meter">
    <progress value={resource.holders.length} max={resource.capacity}></progress>
    <span class="usage rl-mono">{i18n.t('resources.usage', { held: resource.holders.length, capacity: resource.capacity })}</span>
  </div>
  {#if resource.holders.length > resource.capacity}
    <p class="rl-help over">{i18n.t('resources.overCapacity')}</p>
  {/if}

  <section class="holders">
    <h3>{i18n.t('resources.holders')}</h3>
    {#if resource.holders.length === 0}
      <p class="rl-help">{i18n.t('resources.nobodyHolds')}</p>
    {:else}
      <ul>
        {#each resource.holders as holder (holder.runId)}
          <li>
            <Link href={runHref(holder.runId)} class="rl-mono">{holder.runId.slice(0, 8)}</Link>
            <span class="pipeline"><PlainText value={holder.pipeline} mono /></span>
            <span class="rl-help">
              <Timestamp iso={holder.heldSince} />,
              {i18n.t('resources.heldFor', { duration: duration(holder.heldSeconds) })}
            </span>
            <button class="rl-btn small danger" type="button" onclick={() => onrelease(holder)}>
              {i18n.t('resources.release')}
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <section class="waiters">
    <h3>{i18n.t('resources.waiters')}</h3>
    {#if resource.waiters.length === 0}
      <p class="rl-help">{i18n.t('resources.nobodyWaits')}</p>
    {:else}
      <ol>
        {#each resource.waiters as waiter, index (waiter.runId)}
          <li>
            <span class="position rl-mono">{index + 1}</span>
            <Link href={runHref(waiter.runId)} class="rl-mono">{waiter.runId.slice(0, 8)}</Link>
            <span class="pipeline"><PlainText value={waiter.pipeline} mono /></span>
            <span class="rl-help">
              {i18n.t('resources.waitedFor', {
                duration: duration(waiter.waitedSeconds),
                names: waiter.waitingFor.join(', '),
              })}
            </span>
          </li>
        {/each}
      </ol>
    {/if}
  </section>

  <section class="declared">
    <h3>{i18n.t('resources.declaredBy')}</h3>
    {#if resource.declaredBy.count === 0}
      <p class="rl-help">{i18n.t('resources.declaredBy.none')}</p>
    {:else}
      <p class="summary rl-help">
        {i18n.t('resources.declaredBy.summary', {
          count: resource.declaredBy.count,
          triggers: resource.declaredBy.triggers,
        })}
      </p>
      <ul>
        {#each resource.declaredBy.definitions as definition (`${definition.contentHash}/${definition.uploader}/${definition.pipeline}`)}
          <li>
            <Link href={pipelineHref(definition.contentHash, definition.pipeline, definition.uploader)} class="rl-mono">
              <PlainText value={definition.pipeline} mono />
            </Link>
            <span class="rl-help">
              <PlainText value={definition.contentHash.slice(0, 12)} mono />
              · <PlainText value={definition.uploader} />
              · {definition.declaredType === null
                ? i18n.t('resources.declaredBy.anyType')
                : i18n.t('resources.declaredBy.expects', {
                    type: enumLabel(i18n.translate, 'resourceType', definition.declaredType),
                  })}
              · {i18n.t('resources.declaredBy.triggers', { count: definition.triggers })}
            </span>
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <footer>
    <span class="rl-help">
      {i18n.t('resources.madeBy', { by: resource.createdBy, updatedBy: resource.updatedBy })}
    </span>
    <span class="rl-actions">
      <button class="rl-btn small" type="button" disabled={checking} onclick={() => void check()}>
        {i18n.t('resources.check')}
      </button>
      <button class="rl-btn small" type="button" onclick={onchange}>{i18n.t('resources.change')}</button>
      <button class="rl-btn small danger" type="button" onclick={ondelete}>
        {i18n.t('resources.delete')}
      </button>
    </span>
  </footer>
</article>

<style>
  .resource {
    display: grid;
    gap: var(--space-4);
    align-content: start;
  }
  header,
  footer {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }
  h2.name {
    font-size: var(--text-md);
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  h3 {
    margin: 0 0 var(--space-2);
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .tag {
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    font-size: var(--text-2xs);
    font-weight: 700;
  }
  .tags {
    display: inline-flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  .tag.type {
    background: var(--accent-tint);
    color: var(--accent-text);
  }
  .settings {
    display: grid;
    gap: var(--space-1);
    margin: 0;
    font-size: var(--text-sm);
  }
  .settings div {
    display: grid;
    grid-template-columns: 9rem 1fr;
    gap: var(--space-2);
  }
  .settings dt {
    color: var(--text-muted);
  }
  .settings dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .secret {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-sm);
  }
  .last-check {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-sm);
  }
  .last-check .outcome.passed {
    color: var(--success-text);
  }
  .last-check .outcome.failed {
    color: var(--danger-text);
  }
  .last-check .label,
  .secret .label {
    color: var(--text-muted);
  }
  .secret-status.found {
    color: var(--success-text);
  }
  .secret-status.missing,
  .secret-status.invalid_secret {
    color: var(--danger-text);
  }
  .tag.on {
    background: var(--success-tint);
    color: var(--success-text);
  }
  .tag.off {
    background: var(--neutral-tint);
    color: var(--neutral-text);
  }
  .meter {
    display: grid;
    gap: var(--space-1);
  }
  progress {
    width: 100%;
    height: 8px;
    accent-color: var(--accent);
  }
  .over {
    margin: 0;
    color: var(--warning-text);
  }
  ul,
  ol {
    display: grid;
    gap: var(--space-2);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  li {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .position {
    min-width: 1.5em;
    color: var(--text-muted);
  }
  .pipeline {
    font-weight: 600;
  }
  p {
    margin: 0;
  }
</style>

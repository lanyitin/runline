<script lang="ts">
  import type { AllowEntry, AllowList, AllowListChange, AllowListVersion } from '../api/admin-model';
  import { createPolled } from '../api/polled.svelte';
  import AllowListChangeDialog, { type Operation } from '../admin/AllowListChangeDialog.svelte';
  import { useApp } from '../app/context';
  import { enumLabel } from '../i18n/enums';
  import { browserClock, pageVisibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // The allow-list (`GET /api/v1/allowlist` and its versions), for admins: the version in force, its
  // entries and what each lets through, the history of versions, and the ways to change it. Every
  // way goes through AllowListChangeDialog, which shows the effect first (WI-36) and only then does
  // it. An entry is a decision to trust, and the page says so.
  const { i18n, api } = useApp();

  const loaded = createPolled({
    load: async (): Promise<{ list: AllowList; versions: AllowListVersion[] }> => {
      const [list, versions] = await Promise.all([api.allowList(), api.allowListVersions(50)]);
      return { list, versions };
    },
    clock: browserClock,
    visibility: pageVisibility,
    intervalMs: 0,
    auto: false,
  });
  $effect(() => {
    void loaded.start();
    return () => loaded.dispose();
  });

  const list = $derived(loaded.data?.list ?? null);
  const versions = $derived(loaded.data?.versions ?? []);

  let search = $state('');
  let kindFilter = $state('');
  const entries = $derived(list?.entries ?? []);
  const shown = $derived(
    entries.filter(
      (e) =>
        (kindFilter === '' || e.kind === kindFilter) &&
        e.name.toLowerCase().includes(search.trim().toLowerCase()),
    ),
  );

  let operation = $state<Operation | null>(null);
  function finished(change: AllowListChange | null) {
    operation = null;
    if (change !== null) loaded.reload();
  }

  const scope = (entry: AllowEntry) =>
    entry.kind === 'class'
      ? i18n.t('allow.scope.class')
      : entry.exactOnly
        ? i18n.t('allow.scope.package.exact')
        : i18n.t('allow.scope.package');
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.allowlist.title')}</h1>
    <p class="sub">{i18n.t('page.allowlist.intro')}</p>
  </div>
  <div class="rl-actions">
    <button class="rl-btn" type="button" disabled={list === null} onclick={() => (operation = { kind: 'recheck' })}>
      {i18n.t('allow.recheck')}
    </button>
    <button class="rl-btn primary" type="button" disabled={list === null} onclick={() => (operation = { kind: 'add' })}>
      {i18n.t('allow.add')}
    </button>
  </div>
</div>

{#if loaded.status === 'failed' && loaded.error}
  <div class="rl-stack">
    <ApiErrorNotice failure={loaded.error} />
    <div><button class="rl-btn retry" type="button" onclick={() => loaded.reload()}>{i18n.t('common.retry')}</button></div>
  </div>
{:else if list === null}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else}
  <div class="rl-stack">
    <section class="rl-card current">
      <h2>{i18n.t('allow.current')}</h2>
      <dl class="rl-dl">
        <dt>{i18n.t('allow.version')}</dt>
        <dd class="version rl-mono">{list.version}</dd>
        <dt>{i18n.t('allow.changedBy')}</dt>
        <dd><PlainText value={list.changedBy} /></dd>
        <dt>{i18n.t('allow.changedAt')}</dt>
        <dd><Timestamp iso={list.changedAt} /></dd>
      </dl>
      <div class="rl-notice info trust">{i18n.t('allow.trust.note')}</div>
      {#if list.limitations}
        <details class="limitations">
          <summary>{i18n.t('allow.limitations')}</summary>
          <PlainText value={list.limitations} multiline />
        </details>
      {/if}
    </section>

    <section>
      <h2 class="group">{i18n.t('allow.entries')}</h2>
      <div class="rl-toolbar">
        <div class="rl-field">
          <label for="allow-search">{i18n.t('allow.search')}</label>
          <input id="allow-search" class="rl-input mono" type="search" autocomplete="off" bind:value={search} />
        </div>
        <div class="rl-field">
          <label for="allow-kind">{i18n.t('allow.filter.kind')}</label>
          <select id="allow-kind" class="rl-input" bind:value={kindFilter}>
            <option value="">{i18n.t('allow.filter.all')}</option>
            <option value="package">{enumLabel(i18n.translate, 'allowKind', 'package')}</option>
            <option value="class">{enumLabel(i18n.translate, 'allowKind', 'class')}</option>
          </select>
        </div>
      </div>

      {#if shown.length === 0}
        <p class="rl-empty">{i18n.t('allow.noMatch')}</p>
      {:else}
        <div class="rl-table-wrap">
          <table class="rl-table entries">
            <thead>
              <tr>
                <th scope="col">{i18n.t('allow.col.kind')}</th>
                <th scope="col">{i18n.t('allow.col.name')}</th>
                <th scope="col">{i18n.t('allow.col.scope')}</th>
                <th scope="col">{i18n.t('allow.col.by')}</th>
                <th scope="col">{i18n.t('allow.col.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {#each shown as entry (`${entry.kind}/${entry.name}`)}
                <tr>
                  <td class="kind">{enumLabel(i18n.translate, 'allowKind', entry.kind)}</td>
                  <td class="name"><PlainText value={entry.name} mono /></td>
                  <td class="scope">{scope(entry)}</td>
                  <td class="by">
                    <PlainText value={entry.updatedBy} />
                    <span class="rl-help rl-nowrap"><Timestamp iso={entry.updatedAt} /></span>
                  </td>
                  <td>
                    <div class="rl-actions">
                      <button class="rl-btn small" type="button" onclick={() => (operation = { kind: 'modify', entry })}>
                        {i18n.t('allow.change')}
                      </button>
                      <button class="rl-btn small danger" type="button" onclick={() => (operation = { kind: 'remove', entry })}>
                        {i18n.t('allow.remove')}
                      </button>
                    </div>
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
      <p class="count rl-help">{i18n.t('allow.count', { shown: shown.length, total: entries.length })}</p>
    </section>

    <section>
      <h2 class="group">{i18n.t('allow.history')}</h2>
      <div class="rl-table-wrap">
        <table class="rl-table history">
          <thead>
            <tr>
              <th scope="col">{i18n.t('allow.history.col.version')}</th>
              <th scope="col">{i18n.t('allow.history.col.action')}</th>
              <th scope="col">{i18n.t('allow.history.col.detail')}</th>
              <th scope="col">{i18n.t('allow.history.col.by')}</th>
              <th scope="col">{i18n.t('allow.history.col.judged')}</th>
              <th scope="col">{i18n.t('allow.history.col.unsafe')}</th>
              <th scope="col">{i18n.t('allow.history.col.safe')}</th>
            </tr>
          </thead>
          <tbody>
            {#each versions as version (version.version)}
              <tr>
                <td class="v rl-mono">{version.version}</td>
                <td class="action">{enumLabel(i18n.translate, 'allowAction', version.action)}</td>
                <td class="detail"><PlainText value={version.detail} /></td>
                <td class="by">
                  <PlainText value={version.changedBy} />
                  <span class="rl-help rl-nowrap"><Timestamp iso={version.changedAt} /></span>
                </td>
                <td class="judged rl-mono">{version.rejudgedDefinitions}</td>
                <td class="unsafe rl-mono">{version.becameUnsafe}</td>
                <td class="safe rl-mono">{version.becameSafe}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    </section>
  </div>
{/if}

{#if operation}
  <AllowListChangeDialog {operation} onfinished={finished} />
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .group {
    margin-bottom: var(--space-3);
    color: var(--text-muted);
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .version {
    font-size: var(--text-md);
    font-weight: 600;
  }
  .trust {
    margin-top: var(--space-4);
  }
  .limitations {
    margin-top: var(--space-3);
    color: var(--text-secondary);
    font-size: var(--text-xs);
  }
  .count {
    margin-top: var(--space-3);
  }
  td.name {
    overflow-wrap: anywhere;
  }
</style>

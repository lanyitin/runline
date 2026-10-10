<script lang="ts">
  import { tick } from 'svelte';
  import { ApiFailure } from '../api/failure';
  import type { ResourceBindings, Role } from '../api/bindings-model';
  import type { Artifact, Pipeline } from '../api/model';
  import { useApp } from '../app/context';
  import { pipelineHref } from '../app/links';
  import { enumLabel } from '../i18n/enums';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // The resource roles of one pipeline of one version (ADR-025): what each is for, which shared
  // resource it comes to, whether a run can use it, and the choice of another. A developer and an
  // admin both choose, from the shared resources that exist and are of the role's type (the Engine
  // lists them: `candidates`); neither can define or delete a resource here. The bindings are of one
  // uploader: an admin who can see several chooses whose. The purpose and the role are text from a
  // jar and are shown as text only (ADR-017).
  interface Props {
    artifact: Artifact;
    pipeline: Pipeline;
    isAdmin: boolean;
    /** The role the address asked to look at (a refused run leads here). */
    focusRole: string | null;
  }
  let { artifact, pipeline, isAdmin, focusRole }: Props = $props();

  const { i18n, api, router } = useApp();
  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  let bindings = $state.raw<ResourceBindings | null>(null);
  let loadFailure = $state.raw<ApiFailure | null>(null);
  // What the person has chosen in each role's list but not saved, and what became of the last
  // attempt at each role.
  let chosen = $state<Record<string, string>>({});
  let busy = $state<Record<string, 'saving' | 'unbinding' | undefined>>({});
  let outcome = $state.raw<Record<string, { done: string } | { failure: ApiFailure } | undefined>>({});
  let removing = $state<Record<string, boolean>>({});
  let staleFailure = $state.raw<ApiFailure | null>(null);

  let loading = 0;
  async function load() {
    const ticket = ++loading;
    loadFailure = null;
    try {
      const read = await api.resourceBindings(artifact.contentHash, pipeline.name, artifact.uploader);
      if (ticket !== loading) return;
      bindings = read;
      chosen = {};
      outcome = {};
      staleFailure = null;
    } catch (error) {
      if (ticket === loading) loadFailure = asFailure(error);
    }
  }
  // The page reads the version again after other changes; the bindings are read again only for another one.
  const whose = $derived(JSON.stringify([artifact.contentHash, artifact.uploader, pipeline.name]));
  $effect(() => {
    void whose;
    void load();
    return () => {
      loading += 1;
    };
  });

  // The role the address names is where the person is brought to.
  let broughtTo: string | null = null;
  $effect(() => {
    if (bindings === null || focusRole === null || broughtTo === focusRole) return;
    broughtTo = focusRole;
    void tick().then(() => {
      const element = document.getElementById(`role-${focusRole}`);
      element?.scrollIntoView?.({ block: 'center' });
      element?.focus({ preventScroll: true });
    });
  });

  const type = (value: string | null) =>
    value === null ? i18n.t('bindings.type.any') : enumLabel(i18n.translate, 'resourceType', value);
  const stateLabel = (problem: string | null) => {
    const key = problem === null ? 'bindings.state.ok' : `bindings.state.${problem}`;
    return i18n.translate.has(key) ? i18n.translate(key) : (problem ?? '');
  };
  const problemText = (role: Role) =>
    i18n.translate.has(`bindings.problem.${role.problem}`)
      ? i18n.translate(`bindings.problem.${role.problem}`, { resource: role.resource, role: role.role })
      : i18n.t('bindings.problem.other', { resource: role.resource, role: role.role });
  const needAttention = $derived((bindings?.roles ?? []).filter((r) => r.problem !== null));

  async function change(role: string, work: () => Promise<ResourceBindings>, kind: 'saving' | 'unbinding', done: string) {
    if (busy[role]) return;
    busy[role] = kind;
    outcome = { ...outcome, [role]: undefined };
    try {
      bindings = await work();
      chosen[role] = '';
      outcome = { ...outcome, [role]: { done } };
    } catch (error) {
      outcome = { ...outcome, [role]: { failure: asFailure(error) } };
    } finally {
      busy[role] = undefined;
    }
  }
  const save = (role: string) =>
    change(
      role,
      () => api.bindResource(artifact.contentHash, pipeline.name, role, chosen[role], artifact.uploader),
      'saving',
      i18n.t('bindings.saved', { role, resource: chosen[role] }),
    );
  const unbind = (role: string) =>
    change(
      role,
      () => api.unbindResource(artifact.contentHash, pipeline.name, role, artifact.uploader),
      'unbinding',
      i18n.t('bindings.unbound', { role }),
    );

  async function removeStale(role: string) {
    if (removing[role]) return;
    removing[role] = true;
    staleFailure = null;
    try {
      bindings = await api.unbindResource(artifact.contentHash, pipeline.name, role, artifact.uploader);
    } catch (error) {
      staleFailure = asFailure(error);
    } finally {
      removing[role] = false;
    }
  }

  function chooseUploader(uploader: string) {
    router.replace(pipelineHref(artifact.contentHash, pipeline.name, uploader));
  }
</script>

<section class="rl-card bindings" aria-labelledby="bindings-title">
  <h2 id="bindings-title">{i18n.t('pipeline.resources')}</h2>

  {#if loadFailure}
    <div class="rl-stack">
      <ApiErrorNotice failure={loadFailure} />
      <div><button class="rl-btn retry" type="button" onclick={() => void load()}>{i18n.t('common.retry')}</button></div>
    </div>
  {:else if bindings === null}
    <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
  {:else if bindings.roles.length === 0 && bindings.staleBindings.length === 0}
    <p>{i18n.t('pipeline.resources.none')}</p>
  {:else}
    {#if bindings.roles.length === 0}
      <p>{i18n.t('pipeline.resources.none')}</p>
    {:else}
      <p class="rl-help">{i18n.t('bindings.intro')}</p>
    {/if}

    {#if isAdmin && bindings.uploaders.length > 1}
      <div class="rl-field uploader-choice">
        <label for="bindings-uploader">{i18n.t('bindings.uploader')}</label>
        <select
          id="bindings-uploader"
          class="rl-input uploader"
          value={bindings.uploader}
          onchange={(event) => chooseUploader(event.currentTarget.value)}
        >
          {#each bindings.uploaders as uploader (uploader)}
            <option value={uploader}>{uploader}</option>
          {/each}
        </select>
        <span class="rl-help">{i18n.t('bindings.uploader.help')}</span>
      </div>
    {/if}

    {#if needAttention.length > 0}
      <div class="rl-notice warning cannot-run" role="status">
        <p>{i18n.t('bindings.cannotRun')}</p>
        <ul class="attention">
          {#each needAttention as role (role.role)}
            <li><PlainText value={role.role} mono /></li>
          {/each}
        </ul>
      </div>
    {/if}

    <ul class="roles">
      {#each bindings.roles as role (role.role)}
        {@const result = outcome[role.role]}
        <li
          class="role"
          id={`role-${role.role}`}
          data-role={role.role}
          tabindex="-1"
          aria-current={focusRole === role.role ? 'true' : undefined}
        >
          <div class="head">
            <h3 class="role-name"><PlainText value={role.role} mono /></h3>
            <span class="role-type">{type(role.type)}</span>
            <span class="role-state" class:ok={role.problem === null}>{stateLabel(role.problem)}</span>
          </div>

          {#if role.purpose !== null}
            <p class="role-purpose"><PlainText value={role.purpose} multiline /></p>
          {/if}

          <p class="role-current">
            {#if role.binding}
              {i18n.t('bindings.current.bound')} <PlainText value={role.binding.resource} mono />
            {:else if role.exists}
              {i18n.t('bindings.current.default', { resource: role.resource })}
            {:else}
              {i18n.t('bindings.current.none', { resource: role.resource })}
            {/if}
          </p>
          {#if role.binding}
            <p class="role-bound-by rl-help">
              {i18n.t('bindings.boundBy')} <PlainText value={role.binding.setBy} />
              · <Timestamp iso={role.binding.setAt} />
            </p>
          {/if}
          {#if role.problem !== null}
            <p class="role-problem">{problemText(role)}</p>
          {/if}

          <div class="controls">
            {#if role.candidates.length === 0}
              <p class="role-none rl-help">
                {i18n.t('bindings.choose.none')}
                {#if isAdmin}
                  <Link href="/resources">{i18n.t('bindings.choose.define')}</Link>
                {:else}
                  {i18n.t('bindings.choose.askAdmin')}
                {/if}
              </p>
            {:else}
              <label class="sr-only" for={`choose-${role.role}`}>{i18n.t('bindings.choose', { role: role.role })}</label>
              <select
                id={`choose-${role.role}`}
                class="rl-input"
                value={chosen[role.role] ?? ''}
                onchange={(event) => (chosen[role.role] = event.currentTarget.value)}
              >
                <option value="">{i18n.t('bindings.choose.placeholder')}</option>
                {#each role.candidates as candidate (candidate.name)}
                  <option value={candidate.name}>
                    {candidate.enabled ? candidate.name : i18n.t('bindings.choose.disabled', { name: candidate.name })}
                  </option>
                {/each}
              </select>
              <button
                class="rl-btn primary small save"
                type="button"
                disabled={(chosen[role.role] ?? '') === '' || busy[role.role] !== undefined}
                onclick={() => void save(role.role)}
              >
                {busy[role.role] === 'saving' ? i18n.t('bindings.saving') : i18n.t('bindings.save')}
              </button>
            {/if}
            {#if role.binding}
              <button
                class="rl-btn small unbind"
                type="button"
                disabled={busy[role.role] !== undefined}
                onclick={() => void unbind(role.role)}
              >
                {busy[role.role] === 'unbinding' ? i18n.t('bindings.unbinding') : i18n.t('bindings.unbind')}
              </button>
            {/if}
          </div>

          {#if result && 'done' in result}
            <p class="role-result" role="status">{result.done}</p>
          {:else if result && 'failure' in result}
            <div class="role-error"><ApiErrorNotice failure={result.failure} /></div>
          {/if}
        </li>
      {/each}
    </ul>

    {#if bindings.staleBindings.length > 0}
      <div class="stale">
        <h3>{i18n.t('bindings.stale.title')}</h3>
        <p class="rl-help">{i18n.t('bindings.stale.help')}</p>
        <ul class="plain-list">
          {#each bindings.staleBindings as stale (stale.role)}
            <li>
              <PlainText value={stale.role} mono /> → <PlainText value={stale.resource} mono />
              <button
                class="rl-btn small remove"
                type="button"
                disabled={removing[stale.role]}
                onclick={() => void removeStale(stale.role)}
              >
                {i18n.t('bindings.stale.remove')}
              </button>
            </li>
          {/each}
        </ul>
        {#if staleFailure}<ApiErrorNotice failure={staleFailure} />{/if}
      </div>
    {/if}
  {/if}
</section>

<style>
  .muted {
    color: var(--text-secondary);
  }
  .uploader-choice {
    max-width: 22rem;
    margin: var(--space-3) 0;
  }
  .cannot-run {
    margin: var(--space-3) 0;
  }
  .cannot-run p {
    margin: 0;
    font-weight: 600;
  }
  .attention {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-4);
    margin: var(--space-2) 0 0;
    padding: 0;
    list-style: none;
  }
  .roles,
  .plain-list {
    display: grid;
    gap: var(--space-3);
    margin: var(--space-3) 0 0;
    padding: 0;
    list-style: none;
  }
  .role {
    display: grid;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
  }
  .role[aria-current='true'] {
    border-color: var(--accent, var(--text-secondary));
    box-shadow: 0 0 0 2px var(--accent, var(--text-secondary));
  }
  .head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: var(--space-2) var(--space-3);
  }
  h3 {
    margin: 0;
    font-size: var(--text-md);
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .role-type {
    color: var(--text-muted);
    font-size: var(--text-sm);
  }
  .role-state {
    margin-left: auto;
    color: var(--warning-text);
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .role-state.ok {
    color: var(--success-text);
  }
  p {
    margin: 0;
  }
  .role-problem {
    color: var(--warning-text);
    font-weight: 600;
  }
  .controls {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .controls select {
    max-width: 20rem;
  }
  .role-result {
    color: var(--success-text);
  }
  .stale {
    margin-top: var(--space-4);
  }
  .stale li {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
</style>

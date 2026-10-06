<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Resource } from '../api/admin-model';
  import { useApp } from '../app/context';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Dialog from '../ui/Dialog.svelte';

  // Defines a shared resource (`POST /api/v1/resources`) or changes the capacity and whether it is
  // enabled (`PATCH`). It says what lowering the capacity and disabling do (ADR-007: nothing is taken
  // back from the holders; the runs that wait for a disabled resource fail), and warns, with the
  // number, when disabling would fail runs that are waiting now. What the Engine refuses is said at
  // the field it is about, or in the dialog.
  interface Props {
    /** The resource to change; none to define a new one. */
    resource?: Resource;
    /** Done: with the resource as the Engine gives it, or null when the dialog was left. */
    onfinished: (resource: Resource | null) => void;
  }
  let { resource, onfinished }: Props = $props();

  const { i18n, api } = useApp();
  // svelte-ignore state_referenced_locally
  const editing = resource !== undefined;
  // svelte-ignore state_referenced_locally
  let name = $state(resource?.name ?? '');
  // svelte-ignore state_referenced_locally
  let capacity = $state(String(resource?.capacity ?? 1));
  // svelte-ignore state_referenced_locally
  let enabled = $state(resource?.enabled ?? true);
  let errors = $state<Record<string, string>>({});
  let failure = $state.raw<ApiFailure | null>(null);
  let busy = $state(false);

  const waiting = $derived(resource?.waiters.length ?? 0);
  const failsWaiters = $derived(editing && resource!.enabled && !enabled && waiting > 0);
  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  async function save() {
    if (busy) return;
    failure = null;
    const found: Record<string, string> = {};
    if (!editing && name.trim() === '') found['resource-name'] = i18n.t('resources.form.name.required');
    const count = /^\d+$/.test(capacity.trim()) ? Number(capacity.trim()) : NaN;
    if (!Number.isInteger(count) || count < 1) {
      found['resource-capacity'] = i18n.t('resources.form.capacity.invalid');
    }
    if (editing && Object.keys(found).length === 0) {
      const changes = count !== resource!.capacity || enabled !== resource!.enabled;
      if (!changes) found['resource-form'] = i18n.t('resources.form.nothing');
    }
    errors = found;
    if (Object.keys(found).length > 0) return;

    busy = true;
    try {
      const done = editing
        ? await api.updateResource(resource!.name, {
            ...(count !== resource!.capacity ? { capacity: count } : {}),
            ...(enabled !== resource!.enabled ? { enabled } : {}),
          })
        : await api.createResource({ name: name.trim(), capacity: count });
      onfinished(done);
    } catch (error) {
      const refused = asFailure(error);
      if ((refused.body as { error?: string } | null)?.error === 'resource_exists') {
        errors = { 'resource-name': i18n.t('error.resource_exists') };
      } else {
        failure = refused;
      }
    } finally {
      busy = false;
    }
  }
</script>

<Dialog
  title={editing ? i18n.t('resources.form.change.title', { name: resource!.name }) : i18n.t('resources.form.create.title')}
  dismissible={!busy}
  onclose={() => onfinished(null)}
>
  <form
    class="form"
    novalidate
    onsubmit={(event) => {
      event.preventDefault();
      void save();
    }}
  >
    {#if !editing}
      <div class="rl-field">
        <label for="resource-name">{i18n.t('resources.form.name')}</label>
        <input
          id="resource-name"
          class="rl-input mono"
          type="text"
          autocomplete="off"
          spellcheck="false"
          data-autofocus
          aria-invalid={errors['resource-name'] ? 'true' : undefined}
          bind:value={name}
          oninput={() => delete errors['resource-name']}
        />
        <span class="rl-help">{i18n.t('resources.form.name.help')}</span>
        {#if errors['resource-name']}<span class="rl-field-error">{errors['resource-name']}</span>{/if}
      </div>
    {/if}

    <div class="rl-field">
      <label for="resource-capacity">{i18n.t('resources.form.capacity')}</label>
      <input
        id="resource-capacity"
        class="rl-input mono"
        type="text"
        inputmode="numeric"
        autocomplete="off"
        data-autofocus={editing ? true : undefined}
        aria-invalid={errors['resource-capacity'] ? 'true' : undefined}
        bind:value={capacity}
        oninput={() => {
          delete errors['resource-capacity'];
          delete errors['resource-form'];
        }}
      />
      <span class="rl-help">{i18n.t('resources.form.capacity.help')}</span>
      {#if errors['resource-capacity']}<span class="rl-field-error">{errors['resource-capacity']}</span>{/if}
    </div>

    {#if editing}
      <div class="rl-field">
        <label class="enabled">
          <input id="resource-enabled" type="checkbox" bind:checked={enabled} onchange={() => delete errors['resource-form']} />
          {i18n.t('resources.form.enabled')}
        </label>
        <span class="rl-help">{i18n.t('resources.form.enabled.help')}</span>
        {#if failsWaiters}
          <div class="rl-notice warning" role="status">
            {i18n.t('resources.form.waitersWarning', { count: waiting })}
          </div>
        {/if}
      </div>
      {#if errors['resource-form']}<span class="rl-field-error">{errors['resource-form']}</span>{/if}
    {/if}

    {#if failure}
      <ApiErrorNotice {failure} />
    {/if}
  </form>

  {#snippet footer()}
    <button class="rl-btn" type="button" disabled={busy} onclick={() => onfinished(null)}>{i18n.t('common.cancel')}</button>
    <button
      class="rl-btn {failsWaiters ? 'danger solid' : 'primary'}"
      type="button"
      disabled={busy}
      onclick={() => void save()}
    >
      {i18n.t(!editing ? 'resources.form.define' : failsWaiters ? 'resources.form.saveFail' : 'resources.form.save')}
    </button>
  {/snippet}
</Dialog>

<style>
  .form {
    display: grid;
    gap: var(--space-4);
  }
  .enabled {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--text);
    font-size: var(--text-sm);
  }
</style>

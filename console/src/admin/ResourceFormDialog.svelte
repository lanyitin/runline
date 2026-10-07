<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Resource } from '../api/admin-model';
  import { useApp } from '../app/context';
  import { enumLabel } from '../i18n/enums';
  import FileFields from './FileFields.svelte';
  import JdbcPoolFields from './JdbcPoolFields.svelte';
  import OpenAiFields from './OpenAiFields.svelte';
  import { formOf } from './resource-forms';
  import { formTypes } from './resource-types';
  import SecretAliasField from './SecretAliasField.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Dialog from '../ui/Dialog.svelte';
  import PlainText from '../ui/PlainText.svelte';

  // Defines a shared resource (`POST /api/v1/resources`) or changes it (`PATCH`). A new resource's
  // type is chosen first, among the types the Console has a form for (resource-types.ts; ADR-019),
  // and the fields of that type follow (resource-forms.ts: what they make of the settings; one
  // component of fields per type); the name and the type cannot be changed afterwards, so a change
  // shows them as they are. No form takes a secret value: a type that has a secret names it by an
  // alias chosen among the keystore's (SecretAliasField). A change sends only what changed: the
  // settings as a whole when they differ from the stored ones (which makes the Engine forget the
  // last check), the alias when another is chosen. It says what lowering the capacity and disabling
  // do (ADR-007: nothing is taken back from the holders; the runs that wait for a disabled resource
  // fail), and warns, with the number, when disabling would fail runs that are waiting now. What the
  // Engine refuses is said at the field its `problem` is about, or in the dialog.
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
  /** The type chosen for a new resource; '' until one is. */
  // svelte-ignore state_referenced_locally
  let type = $state(resource?.type ?? '');
  // svelte-ignore state_referenced_locally
  let name = $state(resource?.name ?? '');
  // svelte-ignore state_referenced_locally
  let capacity = $state(String(resource?.capacity ?? 1));
  // svelte-ignore state_referenced_locally
  let enabled = $state(resource?.enabled ?? true);
  // svelte-ignore state_referenced_locally
  let secretAlias = $state(resource?.secretAlias ?? '');
  const form = $derived(formOf(type));
  /** The fields of the type, as typed; they start again when another type is chosen. */
  // svelte-ignore state_referenced_locally
  let fields = $state<any>(
    resource === undefined ? undefined : formOf(resource.type)?.fromSettings(resource.settings),
  );
  let errors = $state<Record<string, string>>({});
  let failure = $state.raw<ApiFailure | null>(null);
  let busy = $state(false);

  const waiting = $derived(resource?.waiters.length ?? 0);
  const failsWaiters = $derived(editing && resource!.enabled && !enabled && waiting > 0);
  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  function chooseType() {
    delete errors['resource-type'];
    fields = formOf(type)?.empty();
  }

  /** The fields at these ids changed: what was said at them is no longer so. */
  function cleared(...ids: string[]) {
    for (const id of [...ids, 'resource-form']) delete errors[id];
  }

  /** The field a `problem` of `invalid_resource` is about, for any type, or for this one. */
  const fieldOf = (problem: string): string | undefined =>
    ({
      name: 'resource-name',
      capacity: 'resource-capacity',
      nothing_to_change: 'resource-form',
      invalid_secret_alias: 'resource-secret',
    })[problem] ?? form?.fieldOf(problem);

  /** What the Engine refused, in words: the type's own words for it when it has them. */
  const problemText = (problem: string) =>
    i18n.translate(
      i18n.translate.has(`resources.problem.${problem}.${type}`) ? `resources.problem.${problem}.${type}` : `resources.problem.${problem}`,
    );

  /** Settings written the same way whatever the order of their members, to tell whether they changed. */
  const canonical = (value: unknown): string =>
    Array.isArray(value)
      ? `[${value.map(canonical).join(',')}]`
      : typeof value === 'object' && value !== null
        ? `{${Object.keys(value)
            .sort()
            .map((key) => `${JSON.stringify(key)}:${canonical((value as Record<string, unknown>)[key])}`)
            .join(',')}}`
        : JSON.stringify(value);

  async function save() {
    if (busy) return;
    failure = null;
    const found: Record<string, string> = {};
    if (!editing && type === '') {
      errors = { 'resource-type': i18n.t('resources.form.type.required') };
      return;
    }
    if (!editing && name.trim() === '') found['resource-name'] = i18n.t('resources.form.name.required');
    const count = /^\d+$/.test(capacity.trim()) ? Number(capacity.trim()) : NaN;
    if (!Number.isInteger(count) || count < 1) {
      found['resource-capacity'] = i18n.t('resources.form.capacity.invalid');
    }
    const made = form?.toSettings(fields, resource?.settings ?? {}) ?? { settings: undefined };
    if ('errors' in made) {
      for (const [id, code] of Object.entries(made.errors)) found[id] = i18n.translate(`resources.form.error.${code}`);
    }
    const settings = 'settings' in made ? made.settings : undefined;
    const alias = form?.takesSecret && secretAlias !== '' ? secretAlias : undefined;
    const change = editing
      ? {
          ...(count !== resource!.capacity ? { capacity: count } : {}),
          ...(enabled !== resource!.enabled ? { enabled } : {}),
          ...(settings !== undefined && canonical(settings) !== canonical(resource!.settings) ? { settings } : {}),
          ...(alias !== undefined && alias !== resource!.secretAlias ? { secretAlias: alias } : {}),
        }
      : {};
    if (editing && Object.keys(found).length === 0 && Object.keys(change).length === 0) {
      found['resource-form'] = i18n.t('resources.form.nothing');
    }
    errors = found;
    if (Object.keys(found).length > 0) return;

    busy = true;
    try {
      const done = editing
        ? await api.updateResource(resource!.name, change)
        : await api.createResource({
            name: name.trim(),
            type,
            capacity: count,
            ...(settings !== undefined ? { settings } : {}),
            ...(alias !== undefined ? { secretAlias: alias } : {}),
          });
      onfinished(done);
    } catch (error) {
      const refused = asFailure(error);
      const body = refused.body as { error?: string; problem?: string } | null;
      const at = body?.error === 'invalid_resource' && body.problem ? fieldOf(body.problem) : undefined;
      if (body?.error === 'resource_exists') {
        errors = { 'resource-name': i18n.t('error.resource_exists') };
      } else if (at !== undefined) {
        errors = { [at]: problemText(body!.problem!) };
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
    {#if editing}
      <dl class="fixed">
        <div>
          <dt>{i18n.t('resources.form.name')}</dt>
          <dd><PlainText value={resource!.name} mono /></dd>
        </div>
        <div>
          <dt>{i18n.t('resources.form.type')}</dt>
          <dd>{enumLabel(i18n.translate, 'resourceType', resource!.type)}</dd>
        </div>
      </dl>
      <span class="rl-help">{i18n.t('resources.form.fixed')}</span>
    {:else}
      <div class="rl-field">
        <label for="resource-type">{i18n.t('resources.form.type')}</label>
        <select
          id="resource-type"
          class="rl-input"
          data-autofocus
          aria-invalid={errors['resource-type'] ? 'true' : undefined}
          bind:value={type}
          onchange={chooseType}
        >
          <option value="">{i18n.t('resources.form.type.choose')}</option>
          {#each formTypes as option (option)}
            <option value={option}>{enumLabel(i18n.translate, 'resourceType', option)}</option>
          {/each}
        </select>
        <span class="rl-help">{i18n.t('resources.form.type.help')}</span>
        {#if errors['resource-type']}<span class="rl-field-error">{errors['resource-type']}</span>{/if}
      </div>
    {/if}

    {#if editing || type !== ''}
      {#if !editing}
        <div class="rl-field">
          <label for="resource-name">{i18n.t('resources.form.name')}</label>
          <input
            id="resource-name"
            class="rl-input mono"
            type="text"
            autocomplete="off"
            spellcheck="false"
            aria-invalid={errors['resource-name'] ? 'true' : undefined}
            bind:value={name}
            oninput={() => cleared('resource-name')}
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

      {#if fields !== undefined}
        {#if type === 'file'}
          <FileFields bind:fields {errors} onchange={cleared} />
        {:else if type === 'jdbc-pool'}
          <JdbcPoolFields bind:fields {errors} {capacity} onchange={cleared} />
        {:else if type === 'openai-compatible'}
          <OpenAiFields bind:fields {errors} {capacity} onchange={cleared} />
        {/if}
      {/if}
      {#if form?.takesSecret}
        <SecretAliasField
          bind:value={secretAlias}
          current={resource?.secretAlias ?? null}
          {type}
          error={errors['resource-secret']}
          onchange={() => cleared('resource-secret')}
        />
      {/if}
    {/if}

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
  .fixed {
    display: grid;
    gap: var(--space-1);
    margin: 0;
  }
  .fixed div {
    display: grid;
    grid-template-columns: 8rem 1fr;
    gap: var(--space-2);
  }
  .fixed dt {
    color: var(--text-muted);
  }
  .fixed dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
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

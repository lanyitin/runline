<script lang="ts" module>
  import type { AllowEntry } from '../api/admin-model';

  /** What the dialog does: make an entry, change one, remove one, or judge everything again. */
  export type Operation =
    | { kind: 'add' }
    | { kind: 'modify'; entry: AllowEntry }
    | { kind: 'remove'; entry: AllowEntry }
    | { kind: 'recheck' };
</script>

<script lang="ts">
  import { onMount } from 'svelte';
  import { ApiFailure } from '../api/failure';
  import type { AllowListChange } from '../api/admin-model';
  import { useApp } from '../app/context';
  import { describeApiError } from '../i18n/api-error';
  import { enumLabel } from '../i18n/enums';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Dialog from '../ui/Dialog.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import ImpactView from './ImpactView.svelte';

  // The way a change of the allow-list is made (WI-36, 08-api.md: preview): the form for an entry
  // (add, change), the effect the Engine says it would have (`preview=true`: which pipelines become
  // UNSAFE or SAFE, which lose the permission to run although UNSAFE, which entries become needless),
  // and only then the change itself, with its result. Removing an entry and judging everything again
  // start at the effect. Nothing is changed before the person says so, and the answers that refuse
  // (the entry exists, another covers it, the name is wrong) are said where they are about.
  interface Props {
    operation: Operation;
    /** The dialog is done: with the change that was applied, or null when it was left. */
    onfinished: (change: AllowListChange | null) => void;
  }
  let { operation, onfinished }: Props = $props();

  const { i18n, api } = useApp();
  // The operation of a dialog does not change for as long as it is open.
  // svelte-ignore state_referenced_locally
  const op = operation;
  const entry = op.kind === 'modify' || op.kind === 'remove' ? op.entry : null;

  // `working`: the effect of an operation that starts at the effect is being worked out.
  type Step = 'form' | 'working' | 'preview' | 'applied';
  // svelte-ignore state_referenced_locally
  let step = $state<Step>(op.kind === 'add' || op.kind === 'modify' ? 'form' : 'working');
  let kind = $state<'package' | 'class'>(entry?.kind === 'class' ? 'class' : 'package');
  let name = $state(entry?.name ?? '');
  let exact = $state(entry?.exactOnly === true);
  let nameError = $state<string | null>(null);
  let refusal = $state<{ code: 'entry_exists' | 'entry_covered'; entry: AllowEntry } | null>(null);
  let failure = $state.raw<ApiFailure | null>(null);
  let preview = $state.raw<AllowListChange | null>(null);
  let result = $state.raw<AllowListChange | null>(null);
  let busy = $state(false);

  const title = $derived(
    i18n.t(`allow.change.${op.kind}.title`, { name: entry?.name ?? '' }),
  );
  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  /** The call of the operation, as a preview or for real. */
  function call(previewOnly: boolean): Promise<AllowListChange> {
    const options = { preview: previewOnly };
    switch (op.kind) {
      case 'add':
        return api.addEntry(
          { kind, name: name.trim(), ...(kind === 'package' ? { exactOnly: exact } : {}) },
          options,
        );
      case 'modify': {
        const change: { name?: string; exactOnly?: boolean } = {};
        if (name.trim() !== op.entry.name) change.name = name.trim();
        if (op.entry.kind === 'package' && exact !== (op.entry.exactOnly === true)) {
          change.exactOnly = exact;
        }
        return api.modifyEntry(op.entry.kind, op.entry.name, change, options);
      }
      case 'remove':
        return api.removeEntry(op.entry.kind, op.entry.name, options);
      case 'recheck':
        return api.recheck(options);
    }
  }

  /** What is wrong with the entry of the form, at the field, and what is left for the dialog. */
  function place(refused: ApiFailure) {
    const body = (refused.body ?? {}) as {
      error?: string;
      problem?: string;
      existing?: AllowEntry;
      coveredBy?: AllowEntry;
    };
    const described = describeApiError(i18n.translate, refused.status, refused.body);
    if (step === 'form' && body.error === 'entry_exists' && body.existing) {
      refusal = { code: 'entry_exists', entry: body.existing };
    } else if (step === 'form' && body.error === 'entry_covered' && body.coveredBy) {
      refusal = { code: 'entry_covered', entry: body.coveredBy };
    } else if (step === 'form' && body.error === 'invalid_entry' && body.problem) {
      nameError = described.problems[0] ?? described.message;
    } else {
      failure = refused;
    }
  }

  async function showEffect() {
    if (busy) return;
    failure = null;
    refusal = null;
    nameError = null;
    if (step === 'form') {
      if (name.trim() === '') {
        nameError = i18n.t('allow.form.name.required');
        return;
      }
      if (
        op.kind === 'modify' &&
        name.trim() === op.entry.name &&
        (op.entry.kind === 'class' || exact === (op.entry.exactOnly === true))
      ) {
        nameError = i18n.t('allow.form.nothing');
        return;
      }
    }
    busy = true;
    try {
      preview = await call(true);
      step = 'preview';
    } catch (error) {
      place(asFailure(error));
    } finally {
      busy = false;
    }
  }

  async function apply() {
    if (busy) return;
    failure = null;
    busy = true;
    try {
      result = await call(false);
      step = 'applied';
    } catch (error) {
      failure = asFailure(error);
    } finally {
      busy = false;
    }
  }

  // Removing and judging again start at the effect.
  onMount(() => {
    if (op.kind === 'remove' || op.kind === 'recheck') void showEffect();
  });

  const leave = () => onfinished(step === 'applied' ? result : null);
  const applyLabel = $derived(
    i18n.t(op.kind === 'remove' ? 'allow.apply.remove' : op.kind === 'recheck' ? 'allow.apply.recheck' : 'allow.apply'),
  );
  const dangerous = $derived((preview?.impact.becameUnsafe ?? 0) > 0);
</script>

<Dialog {title} dismissible={!busy} onclose={leave}>
  <div data-step={step} class="step">
    {#if step === 'form'}
      <div class="rl-notice info">{i18n.t('allow.trust')}</div>

      {#if op.kind === 'add'}
        <fieldset class="kinds">
          <legend>{i18n.t('allow.form.kind')}</legend>
          <label><input type="radio" name="entry-kind" value="package" bind:group={kind} /> {i18n.t('allow.form.kind.package')}</label>
          <label><input type="radio" name="entry-kind" value="class" bind:group={kind} /> {i18n.t('allow.form.kind.class')}</label>
        </fieldset>
      {/if}

      <div class="rl-field">
        <label for="entry-name">{i18n.t('allow.form.name')}</label>
        <input
          id="entry-name"
          class="rl-input mono"
          type="text"
          autocomplete="off"
          spellcheck="false"
          data-autofocus
          aria-invalid={nameError ? 'true' : undefined}
          bind:value={name}
          oninput={() => {
            nameError = null;
            refusal = null;
          }}
        />
        <span class="rl-help">{i18n.t(kind === 'class' ? 'allow.form.name.help.class' : 'allow.form.name.help.package')}</span>
        {#if nameError}<span class="rl-field-error">{nameError}</span>{/if}
      </div>

      {#if kind === 'package'}
        <label class="exact">
          <input id="entry-exact" type="checkbox" bind:checked={exact} />
          {i18n.t('allow.form.exact')}
        </label>
      {/if}

      {#if refusal}
        <div class="refusal rl-notice danger" role="alert">
          {i18n.t(refusal.code === 'entry_exists' ? 'allow.refusal.exists' : 'allow.refusal.covered', {
            kind: enumLabel(i18n.translate, 'allowKind', refusal.entry.kind),
            name: refusal.entry.name,
            by: refusal.entry.createdBy,
          })}
        </div>
      {/if}
    {:else if step === 'working'}
      {#if busy}
        <p role="status" aria-busy="true">{i18n.t('allow.previewing')}</p>
      {/if}
    {:else if step === 'preview' && preview}
      <p class="rl-help">{i18n.t('allow.preview.note')}</p>
      <ImpactView change={preview} applied={false} />
    {:else if step === 'applied' && result}
      <div class="rl-notice success" role="status">
        {i18n.t(op.kind === 'recheck' ? 'allow.applied.recheck' : 'allow.applied', { version: result.version })}
      </div>
      <ImpactView change={result} applied />
    {/if}

    {#if busy && (step === 'form' || step === 'preview')}
      <p class="rl-help" role="status" aria-busy="true">
        {i18n.t(step === 'form' ? 'allow.previewing' : 'allow.applying')}
      </p>
    {/if}

    {#if failure}
      <ApiErrorNotice {failure} />
    {/if}
  </div>

  {#snippet footer()}
    {#if step === 'form'}
      <button class="rl-btn" type="button" disabled={busy} onclick={leave}>{i18n.t('common.cancel')}</button>
      <button class="rl-btn primary" type="button" disabled={busy} onclick={showEffect}>{i18n.t('allow.preview')}</button>
    {:else if step === 'working'}
      <button class="rl-btn" type="button" disabled={busy} onclick={leave}>{i18n.t('common.cancel')}</button>
      {#if !busy}
        <button class="rl-btn primary" type="button" onclick={showEffect}>{i18n.t('common.retry')}</button>
      {/if}
    {:else if step === 'preview'}
      {#if op.kind === 'add' || op.kind === 'modify'}
        <button class="rl-btn" type="button" disabled={busy} onclick={() => { step = 'form'; preview = null; failure = null; }}>
          {i18n.t('allow.back')}
        </button>
      {/if}
      <button class="rl-btn" type="button" disabled={busy} onclick={leave}>{i18n.t('common.cancel')}</button>
      <button class="rl-btn {dangerous ? 'danger solid' : 'primary'}" type="button" disabled={busy} onclick={apply}>
        {applyLabel}
      </button>
    {:else}
      <button class="rl-btn primary" type="button" data-autofocus onclick={leave}>{i18n.t('allow.close')}</button>
    {/if}
  {/snippet}
</Dialog>

<style>
  .step {
    display: grid;
    gap: var(--space-4);
  }
  fieldset {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-4);
    margin: 0;
    padding: 0;
    border: 0;
  }
  legend {
    margin-bottom: var(--space-2);
    padding: 0;
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .exact {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
</style>

<script lang="ts">
  import type { ViewDeclaration } from '../api/views-model';
  import { useApp } from '../app/context';
  import PlainText from '../ui/PlainText.svelte';
  import { invalidParameters } from './view-state';

  // The inputs of a view, from the parameters it declares (WI-1302): text, a whole number, or one
  // of fixed options; a required one is marked. What is typed is the person's draft until they ask
  // to see the table; a value that cannot be right is said at its field, with the name of the
  // parameter, before anything is asked of the Engine.
  interface Props {
    view: ViewDeclaration;
    /** The values the table on show was asked for (from the address). */
    applied: Record<string, string>;
    onapply: (values: Record<string, string>) => void;
  }
  let { view, applied, onapply }: Props = $props();

  const { i18n } = useApp();

  let draft = $state<Record<string, string>>({});
  // Whether the person has tried to show the table with this draft: a field that is still empty
  // is not called a mistake before that.
  let attempted = $state(false);
  $effect(() => {
    draft = { ...applied };
    attempted = false;
  });

  const problems = $derived(
    attempted
      ? invalidParameters(view, draft)
      : invalidParameters(view, applied).filter((p) => p.reason !== 'missing'),
  );
  const problemOf = (name: string) => problems.find((p) => p.name === name);

  function submit(event: SubmitEvent) {
    event.preventDefault();
    attempted = true;
    if (invalidParameters(view, draft).length === 0) onapply(draft);
  }
</script>

<form class="view-params" onsubmit={submit} novalidate>
  {#each view.parameters as parameter (parameter.name)}
    {@const id = `view-param-${parameter.name}`}
    {@const problem = problemOf(parameter.name)}
    <div class="rl-field">
      <label for={id}>
        <PlainText value={parameter.name} mono />
        {#if parameter.required}<span class="required">({i18n.t('common.required')})</span>{/if}
      </label>
      {#if parameter.type === 'CHOICE'}
        <select
          {id}
          class="rl-input"
          data-param={parameter.name}
          aria-required={parameter.required}
          aria-invalid={problem ? 'true' : undefined}
          value={draft[parameter.name] ?? ''}
          onchange={(event) => (draft[parameter.name] = event.currentTarget.value)}
        >
          <option value="">{parameter.required ? i18n.t('views.param.choose') : i18n.t('views.param.any')}</option>
          {#each parameter.options as option (option)}
            <option value={option}>{option}</option>
          {/each}
        </select>
      {:else}
        <input
          {id}
          class="rl-input"
          type="text"
          inputmode={parameter.type === 'INTEGER' ? 'numeric' : undefined}
          autocomplete="off"
          data-param={parameter.name}
          aria-required={parameter.required}
          aria-invalid={problem ? 'true' : undefined}
          value={draft[parameter.name] ?? ''}
          oninput={(event) => (draft[parameter.name] = event.currentTarget.value)}
        />
      {/if}
      {#if problem}
        <p class="field-error" data-param-error={parameter.name}>
          {i18n.t(`views.invalid.${problem.reason}`, { parameter: parameter.name })}
        </p>
      {/if}
    </div>
  {/each}
  <div class="submit">
    <button class="rl-btn primary small apply" type="submit">{i18n.t('views.apply')}</button>
  </div>
</form>

<style>
  form {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    gap: var(--space-3) var(--space-4);
    margin: var(--space-3) 0;
  }
  .rl-field {
    min-width: 12rem;
  }
  .required {
    color: var(--text-muted);
    font-size: var(--text-xs);
    font-weight: 400;
  }
  .field-error {
    margin: var(--space-1) 0 0;
    color: var(--danger-text);
    font-size: var(--text-sm);
  }
  .submit {
    align-self: flex-end;
  }
</style>

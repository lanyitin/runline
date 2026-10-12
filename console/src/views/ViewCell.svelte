<script lang="ts">
  import type { Cell, ViewColumn } from '../api/views-model';
  import { useApp } from '../app/context';
  import { formatDateTime } from '../i18n/format';
  import PlainText from '../ui/PlainText.svelte';
  import { instantOf } from './view-state';

  // One value of a table. It comes from the pipeline's database and is untrusted text (ADR-017):
  // it goes into the page as a text node and is never read as markup, and nothing is made of it,
  // no link and no style. A status is told by a mark, a colour and a word (the tone), so that the
  // colour is never the only sign. A time that says its zone is shown in the time of the person
  // and in their language, and what the database gave is its tooltip. Anything else is as given.
  interface Props {
    column: ViewColumn;
    value: Cell;
  }
  let { column, value }: Props = $props();

  const { i18n } = useApp();
  const text = $derived(value === null ? '' : String(value));
  const tone = $derived(
    column.type === 'STATUS' && value !== null
      ? (column.tones.find((t) => t.value === text)?.tone ?? 'NEUTRAL')
      : null,
  );
  const instant = $derived(column.type === 'TIME' ? instantOf(value) : null);
  const MARKS: Record<string, string> = { POSITIVE: '✓', NEGATIVE: '✕', NEUTRAL: '–' };
  const toneWord = $derived(
    tone !== null && i18n.translate.has(`views.tone.${tone}`) ? i18n.translate(`views.tone.${tone}`) : (tone ?? ''),
  );
</script>

{#if value === null}
  <span class="empty"><span aria-hidden="true">—</span><span class="sr-only">{i18n.t('views.cell.empty')}</span></span>
{:else if tone !== null}
  <span class="status {tone.toLowerCase()}" data-tone={tone}>
    <span class="mark" aria-hidden="true">{MARKS[tone] ?? '–'}</span>
    <PlainText value={text} />
    <span class="sr-only"> ({toneWord})</span>
  </span>
{:else if instant !== null}
  <time datetime={instant.toISOString()} title={text}>{formatDateTime(instant.toISOString(), i18n.locale)}</time>
{:else}
  <PlainText value={text} />
{/if}

<style>
  .empty {
    color: var(--text-muted);
  }
  .status {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 1px var(--space-2);
    border-radius: var(--radius-pill);
    font-weight: 600;
  }
  .mark {
    font-weight: 700;
  }
  .positive {
    background: var(--success-tint);
    color: var(--success-text);
  }
  .negative {
    background: var(--danger-tint);
    color: var(--danger-text);
  }
  .neutral {
    background: var(--neutral-tint);
    color: var(--neutral-text);
  }
</style>

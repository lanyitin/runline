<script lang="ts">
  import type { ViewPage } from '../api/views-model';
  import { useApp } from '../app/context';
  import PlainText from '../ui/PlainText.svelte';
  import ViewCell from './ViewCell.svelte';
  import { drillState, type ViewState } from './view-state';

  // The rows of one page of a view, as a table (WI-1302). The labels of the columns are the
  // pipeline's words in the one language it wrote them in; the cells are shown by ViewCell, which
  // makes nothing of what they say. A cell of a column that declares a drill is a button that opens
  // the view it points to, with the value of the row: a button and never a link, because where it
  // leads is the declaration's and not the data's.
  interface Props {
    page: ViewPage;
    /** The titles of the views of the pipeline by id: what a drill opens, for its tooltip. */
    titles: Record<string, string>;
    ondrill: (state: ViewState) => void;
  }
  let { page, titles, ondrill }: Props = $props();

  const { i18n } = useApp();
</script>

<div class="rl-table-wrap">
  <table class="rl-table rl-sticky-first">
    <thead>
      <tr>
        {#each page.columns as column (column.name)}
          <th scope="col"><PlainText value={column.label} /></th>
        {/each}
      </tr>
    </thead>
    <tbody>
      {#each page.rows as row, index (index)}
        <tr>
          {#each page.columns as column, position (column.name)}
            {@const target = drillState(column, page.columns, row)}
            <td class:number={column.type === 'INTEGER'}>
              {#if target !== null}
                <button
                  class="drill"
                  type="button"
                  title={i18n.t('views.drill', { title: titles[target.view!] ?? target.view ?? '' })}
                  onclick={() => ondrill(target)}
                >
                  <ViewCell {column} value={row[position]} />
                </button>
              {:else}
                <ViewCell {column} value={row[position]} />
              {/if}
            </td>
          {/each}
        </tr>
      {/each}
    </tbody>
  </table>
</div>

<style>
  td {
    overflow-wrap: anywhere;
  }
  .number {
    font-variant-numeric: tabular-nums;
  }
  .drill {
    padding: 0;
    border: 0;
    background: none;
    color: var(--accent-text, inherit);
    font: inherit;
    text-align: left;
    text-decoration: underline;
    text-underline-offset: 2px;
    cursor: pointer;
  }
</style>

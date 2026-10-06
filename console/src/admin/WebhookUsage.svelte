<script lang="ts">
  import { useApp } from '../app/context';
  import CopyButton from '../ui/CopyButton.svelte';
  import PlainText from '../ui/PlainText.svelte';

  // How a system outside calls a webhook trigger (08-api.md: Webhook entrance): the address on the
  // Engine, the two headers, and an example to run. [secret] is there only while it is shown (the
  // dialog of a new secret); everywhere else the example has a place for it, because the Console
  // does not have it.
  interface Props {
    /** The `webhookPath` of the trigger. */
    webhookPath: string;
    secret?: string;
  }
  let { webhookPath, secret }: Props = $props();

  const { i18n } = useApp();
  const url = $derived(`${location.origin}${webhookPath}`);
  const example = $derived(
    [
      `curl -X POST '${url}' \\`,
      `  -H 'X-Runline-Webhook-Secret: ${secret ?? '<secret>'}' \\`,
      `  -H "X-Runline-Delivery-Id: $(uuidgen)"`,
    ].join('\n'),
  );
</script>

<div class="usage">
  <div>
    <span class="rl-label">{i18n.t('webhook.url')}</span>
    <p class="line"><span class="verb rl-mono">POST</span> <span class="url rl-mono"><PlainText value={url} mono /></span></p>
  </div>

  <div>
    <span class="rl-label">{i18n.t('webhook.headers')}</span>
    <dl class="rl-dl headers">
      <dt class="rl-mono">X-Runline-Webhook-Secret</dt>
      <dd>{i18n.t('webhook.header.secret')}</dd>
      <dt class="rl-mono">X-Runline-Delivery-Id</dt>
      <dd>{i18n.t('webhook.header.delivery')}</dd>
    </dl>
  </div>

  <div>
    <span class="rl-label">{i18n.t('webhook.example')}</span>
    <pre class="example rl-mono"><PlainText value={example} mono multiline /></pre>
    <CopyButton
      text={example}
      label={i18n.t('webhook.copyExample')}
      copied={i18n.t('common.copied')}
      failed={i18n.t('common.copyFailed')}
    />
  </div>

  <p class="rl-help">{i18n.t('webhook.note')}</p>
</div>

<style>
  .usage {
    display: grid;
    gap: var(--space-4);
  }
  .line {
    margin: var(--space-1) 0 0;
    overflow-wrap: anywhere;
  }
  .verb {
    margin-right: var(--space-2);
    color: var(--text-muted);
    font-weight: 600;
  }
  .headers {
    margin-top: var(--space-2);
  }
  .headers dt {
    text-transform: none;
    letter-spacing: 0;
    font-size: var(--text-xs);
    color: var(--text);
  }
  .example {
    margin: var(--space-2) 0;
    padding: var(--space-3) var(--space-4);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
    font-size: var(--text-xs);
    overflow-x: auto;
  }
</style>

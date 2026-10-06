<script lang="ts">
  import { useApp } from '../app/context';
  import { enumLabel } from '../i18n/enums';
  import StatusDot from './StatusDot.svelte';

  // A pill with a dot and the value in words: a run's state or a verdict. The words are always
  // there, so the colour is never the only sign.
  interface Props {
    kind: 'runState' | 'verdict';
    value: string;
  }
  let { kind, value }: Props = $props();

  type Tone = 'success' | 'danger' | 'warning' | 'running' | 'neutral';
  const TONES: Record<Props['kind'], Record<string, Tone>> = {
    runState: {
      QUEUED: 'neutral',
      WAITING_FOR_RESOURCES: 'warning',
      INITIALIZING: 'running',
      RUNNING: 'running',
      TIMED_OUT_UNFINISHED: 'warning',
      SUCCEEDED: 'success',
      FAILED: 'danger',
      CANCELLED: 'neutral',
      INTERRUPTED: 'warning',
      TIMED_OUT: 'danger',
    },
    verdict: { SAFE: 'success', UNSAFE: 'danger' },
  };

  const { i18n } = useApp();
  const tone = $derived(TONES[kind][value] ?? 'neutral');
</script>

<span class="badge {tone}">
  <StatusDot {tone} pulse={tone === 'running'} />
  {enumLabel(i18n.translate, kind, value)}
</span>

<style>
  .badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    font-size: var(--text-2xs);
    font-weight: 700;
    letter-spacing: 0.02em;
    white-space: nowrap;
  }
  .success {
    background: var(--success-tint);
    color: var(--success-text);
  }
  .danger {
    background: var(--danger-tint);
    color: var(--danger-text);
  }
  .warning {
    background: var(--warning-tint);
    color: var(--warning-text);
  }
  .running {
    background: var(--running-tint);
    color: var(--running-text);
  }
  .neutral {
    background: var(--neutral-tint);
    color: var(--neutral-text);
  }
</style>

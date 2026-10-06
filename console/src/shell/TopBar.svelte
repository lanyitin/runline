<script lang="ts">
  import { useApp } from '../app/context';
  import type { Identity } from '../app/identity.svelte';
  import EngineChip from '../engine/EngineChip.svelte';
  import LanguageSwitcher from '../ui/LanguageSwitcher.svelte';
  import Breadcrumb, { type Crumb } from './Breadcrumb.svelte';

  // The top bar of a signed-in user: where the user is, the language, the Engine's version (wide
  // screens; the sidebar has it always), and who is signed in, with the role in words.
  interface Props {
    crumbs: Crumb[];
    identity: Identity;
  }
  let { crumbs, identity }: Props = $props();

  const { i18n } = useApp();
</script>

<header class="topbar">
  <Breadcrumb {crumbs} />
  <div class="right">
    <LanguageSwitcher />
    <div class="engine"><EngineChip placement="topbar" /></div>
    <div class="user">
      <span class="name">{identity.name}</span>
      <span class="role {identity.role}">{i18n.t(`role.${identity.role}`)}</span>
    </div>
  </div>
</header>

<style>
  .topbar {
    position: sticky;
    top: 0;
    z-index: 30;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-4);
    height: var(--topbar-height);
    padding: 0 var(--space-6);
    background: var(--surface);
    border-bottom: 1px solid var(--border);
  }
  .right {
    display: flex;
    align-items: center;
    gap: var(--space-5);
  }
  .user {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    line-height: 1.2;
  }
  .name {
    font-size: var(--text-xs);
    font-weight: 600;
  }
  .role {
    padding: 0 var(--space-1);
    border-radius: 4px;
    font-size: 0.625rem;
    font-weight: 600;
    text-transform: uppercase;
    background: var(--neutral-tint);
    color: var(--neutral-text);
  }
  .role.admin {
    background: var(--indigo-tint);
    color: var(--indigo-text);
  }
  /* The sidebar always shows the Engine; the top bar repeats it where there is room. */
  @media (max-width: 1100px) {
    .engine {
      display: none;
    }
  }
</style>

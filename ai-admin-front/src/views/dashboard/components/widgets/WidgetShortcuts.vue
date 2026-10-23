<script setup lang="ts">
import { computed } from 'vue'
import type { DashboardWidgetConfig } from '@/types/operationsDashboard'
import { availableDashboardShortcuts, resolveShortcutIds } from '../../dashboardShortcuts'

const props = defineProps<{
  config: DashboardWidgetConfig
  permissions: readonly string[]
}>()
const emit = defineEmits<{ open: [path: string] }>()
const shortcuts = computed(() => {
  const available = new Map(availableDashboardShortcuts(props.permissions).map(item => [item.id, item]))
  return resolveShortcutIds(props.config).flatMap(id => available.get(id) ? [available.get(id)!] : [])
})
</script>

<template>
  <nav class="shortcut-strip" aria-label="快捷入口" data-testid="shortcut-strip">
    <a
      v-for="shortcut in shortcuts"
      :key="shortcut.id"
      class="shortcut-strip__link"
      :href="shortcut.to"
      :title="shortcut.detail"
      :data-shortcut="shortcut.id"
      @click.prevent="emit('open', shortcut.to)"
    >
      <span class="shortcut-strip__icon"><component :is="shortcut.icon" aria-hidden="true" /></span>
      <span>{{ shortcut.label }}</span>
    </a>
    <p v-if="!shortcuts.length" class="shortcut-strip__empty">把常用功能放在这里，点击“自定义”添加入口。</p>
  </nav>
</template>

<style scoped lang="scss">
.shortcut-strip {
  display: flex;
  flex-wrap: wrap;
  align-content: center;
  align-items: center;
  gap: 4px 8px;
}

.shortcut-strip__link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 36px;
  padding: 5px 8px;
  border-radius: 8px;
  color: var(--ops-text);
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  transition: background-color .16s ease, color .16s ease;

  &:hover, &:focus-visible {
    color: var(--ops-blue);
    background: var(--ops-active-background);
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
  }

  &:hover .shortcut-strip__icon, &:focus-visible .shortcut-strip__icon {
    color: var(--ops-blue);
  }
}

.shortcut-strip__icon {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  color: var(--ops-blue);

  svg { width: 17px; height: 17px; }
}

.shortcut-strip__empty {
  margin: 0;
  padding: 4px 0;
  font-size: 12px;
  color: var(--ops-text-secondary);
}

@media (pointer: coarse) {
  .shortcut-strip__link { min-height: 44px; }
}

@container (max-width: 600px) {
  .shortcut-strip { gap: 4px 6px; }
  .shortcut-strip__link { padding-inline: 6px; }
}

@media (prefers-reduced-motion: reduce) {
  .shortcut-strip__link { transition: none; }
}
</style>

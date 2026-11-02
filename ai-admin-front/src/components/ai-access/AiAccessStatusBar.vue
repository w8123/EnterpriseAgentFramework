<template>
  <div class="ai-status-bar" role="status" aria-label="接入状态">
    <div v-for="item in items" :key="item.label" class="ai-status-bar__item" :data-status-item="item.label">
      <span class="ai-status-bar__label">{{ item.label }}</span>
      <strong class="ai-status-bar__value" :class="item.tone ? `is-${item.tone}` : ''">
        <i v-if="item.dot" class="ai-status-bar__dot" aria-hidden="true" />{{ item.value }}
      </strong>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  items: { label: string; value: string | number; tone?: 'ok' | 'warn' | 'danger'; dot?: boolean }[]
}>()
</script>

<style scoped lang="scss">
.ai-status-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 0;
  padding: 6px 0;
}

.ai-status-bar__item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 0 20px;
  border-left: 1px solid var(--border-divider);

  &:first-child { padding-left: 0; border-left: 0; }
}

.ai-status-bar__label { color: var(--text-on-wallpaper-secondary); font-size: 13px; white-space: nowrap; }
.ai-status-bar__value { display: inline-flex; white-space: nowrap; align-items: center; gap: 6px; color: var(--text-on-wallpaper); font-size: 14px; }
.ai-status-bar__value.is-ok { color: var(--status-success); }
.ai-status-bar__value.is-warn { color: var(--status-warning); }
.ai-status-bar__value.is-danger { color: var(--status-danger); }
.ai-status-bar__dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }

@media (max-width: 640px) {
  .ai-status-bar__item { flex: 1 1 45%; border-left: 0; }
}
</style>

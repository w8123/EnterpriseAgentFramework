<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassFilterBar } from '@/components/common/liquid-glass'

withDefaults(defineProps<{ loading?: boolean }>(), { loading: false })
defineEmits<{ query: []; refresh: [] }>()
</script>

<template>
  <div class="a2a-list-toolbar">
    <LiquidGlassFilterBar :loading="loading" :show-reset="false" @query="$emit('query')">
      <slot />
      <template #actions>
        <LiquidGlassButton native-type="submit" :icon="Search" :loading="loading">搜索</LiquidGlassButton>
      </template>
    </LiquidGlassFilterBar>
    <div class="a2a-list-toolbar__actions">
      <LiquidGlassButton circle :icon="Refresh" :loading="loading" aria-label="刷新" title="刷新" @click="$emit('refresh')" />
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.a2a-list-toolbar { display: flex; flex-wrap: wrap; align-items: flex-start; gap: var(--layout-control-gap); min-width: 0; }
.a2a-list-toolbar > :deep(.liquid-glass-filter-bar) { flex: 1 1 420px; min-width: 0; }
.a2a-list-toolbar__actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--layout-control-gap); }
@media (max-width: 640px) { .a2a-list-toolbar__actions { width: 100%; justify-content: flex-end; } }
</style>

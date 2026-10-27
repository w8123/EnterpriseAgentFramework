<script setup lang="ts">
defineProps<{ label?: string; count?: number }>()
</script>

<template>
  <div class="mcp-list-toolbar">
    <p v-if="label || $slots.meta" class="mcp-list-toolbar__summary">{{ label }} <strong v-if="count != null">{{ count }}</strong><slot name="meta" /></p>
    <div v-if="$slots.default" class="mcp-list-toolbar__actions">
      <slot />
    </div>
  </div>
</template>

<style scoped lang="scss">
.mcp-list-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--layout-control-gap);
}
.mcp-list-toolbar__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--text-on-wallpaper-secondary);
  font-size: 13px;
}
.mcp-list-toolbar__summary strong { color: var(--text-on-wallpaper); font-variant-numeric: tabular-nums; }
.mcp-list-toolbar__actions { display: flex; align-items: center; gap: var(--layout-control-gap); margin-inline-start: auto; }
</style>

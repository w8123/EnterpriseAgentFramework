<script setup lang="ts">
import type { Component } from 'vue'
import LiquidGlassCard from './LiquidGlassCard.vue'
import type { LiquidGlassSource } from './liquidGlassContext'

defineProps<{ title?: string; description?: string; icon?: Component; source?: LiquidGlassSource; compact?: boolean }>()
</script>

<template>
  <component :is="compact ? 'div' : LiquidGlassCard" class="liquid-empty-state" :class="{ 'liquid-empty-state--compact': compact }" :source="compact ? undefined : source" role="status">
    <span v-if="icon" class="liquid-empty-state__icon" aria-hidden="true"><el-icon><component :is="icon" /></el-icon></span>
    <h3 v-if="title">{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <div v-if="$slots.default" class="liquid-empty-state__actions"><slot /></div>
  </component>
</template>

<style scoped lang="scss">
.liquid-empty-state {
  display: grid;
  width: min(620px, 100%);
  min-height: 200px;
  place-items: center;
  align-content: center;
  gap: 10px;
  margin-inline: auto;
  padding: clamp(20px, 4vw, 36px);
  text-align: center;
  h3, p { margin: 0; }
  h3 { color: var(--text-primary); font-size: 16px; line-height: 1.5; }
  p { max-width: 52ch; color: var(--text-secondary); font-size: 13px; line-height: 1.65; overflow-wrap: anywhere; }
}
.liquid-empty-state__icon {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-lg);
  color: var(--brand-on-surface);
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
  font-size: 23px;
}
.liquid-empty-state__actions { display: flex; flex-wrap: wrap; justify-content: center; gap: var(--layout-control-gap); margin-top: 6px; }
.liquid-empty-state__actions:empty { display: none; }
.liquid-empty-state--compact {
  min-height: 112px;
  padding: 20px;
  gap: 8px;
}
@media (max-width: 520px) {
  .liquid-empty-state__actions { flex-direction: column; width: 100%; }
}
</style>

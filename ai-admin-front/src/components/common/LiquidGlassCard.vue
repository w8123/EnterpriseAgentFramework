<script setup lang="ts">
import { provideLiquidGlassSource, useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

const props = withDefaults(defineProps<{
  as?: 'article' | 'section' | 'div'
  source?: LiquidGlassSource
  interactive?: boolean
}>(), { as: 'section', interactive: false })
const source = useLiquidGlassSource(() => props.source)
provideLiquidGlassSource(source)
</script>

<template>
  <component :is="props.as" class="liquid-glass-card glass-surface-frost glass-loading-surface"
    :class="{ 'glass-workspace-adaptive': source === 'wallpaper', 'is-interactive': props.interactive }">
    <slot />
  </component>
</template>

<style scoped lang="scss">
.liquid-glass-card {
  --el-text-color-primary: var(--text-primary);
  --el-text-color-regular: var(--text-secondary);
  --el-text-color-secondary: var(--text-muted);
  --el-color-primary: var(--brand-on-surface);
  --el-color-success: var(--status-success);
  --el-color-warning: var(--status-warning);
  --el-color-danger: var(--status-danger);
  --el-color-info: var(--status-info);
  position: relative;
  min-width: 0;
  padding: var(--layout-panel-padding);
  border-radius: var(--radius-xl);
  color: var(--text-primary);
}
.liquid-glass-card.is-interactive {
  cursor: pointer;
  transition: border-color var(--motion-duration-fast) ease, box-shadow var(--motion-duration-fast) ease;
  &:hover { border-color: var(--border-focus); }
  &:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }
}
@media (prefers-reduced-motion: reduce) {
  .liquid-glass-card.is-interactive { transition: none; }
}
@media (forced-colors: active) {
  .liquid-glass-card { border: 1px solid CanvasText; background: Canvas; color: CanvasText; }
}
</style>

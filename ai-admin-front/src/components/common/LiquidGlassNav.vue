<script setup lang="ts">
import LiquidGlassLayer from './LiquidGlassLayer.vue'
import { useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

const props = withDefaults(defineProps<{ source?: LiquidGlassSource; columns?: number }>(), { columns: 0 })
const source = useLiquidGlassSource(() => props.source)
</script>

<template>
  <nav class="liquid-glass-nav glass-surface-lens liquid-glass-host"
    :class="{ 'glass-workspace-adaptive': source === 'wallpaper', 'is-grid': props.columns > 0 }"
    :style="props.columns > 0 ? { '--liquid-nav-columns': props.columns } : undefined">
    <LiquidGlassLayer :source="source" :bezel="8" :depth="12" />
    <div class="liquid-glass-nav__items"><slot /></div>
  </nav>
</template>

<style scoped lang="scss">
.liquid-glass-nav { display: inline-flex; position: relative; max-width: 100%; min-width: 0; padding: 4px; border-radius: var(--glass-control-radius); isolation: isolate; }
.liquid-glass-nav.glass-workspace-adaptive {
  --liquid-nav-text: var(--text-on-wallpaper-secondary);
  --liquid-nav-hover-text: var(--text-on-wallpaper);
  --liquid-nav-active-text: var(--text-on-wallpaper);
}
.liquid-glass-nav__items { display: flex; position: relative; z-index: 1; min-width: 0; width: 100%; gap: 4px; flex-wrap: wrap; }
.liquid-glass-nav :deep(.liquid-glass-nav__items > :is(a, button)) {
  display: inline-flex; min-width: 0; min-height: var(--control-height); align-items: center; justify-content: center; gap: 9px;
  margin: 0; padding: 6px 16px; border: 1px solid transparent; border-radius: var(--glass-control-radius); background: transparent;
  color: var(--liquid-nav-text, var(--text-secondary)); font: inherit; font-size: 13px; font-weight: 500; line-height: 1.4; text-decoration: none; cursor: pointer;
  transition: background var(--motion-duration-fast) ease, color var(--motion-duration-fast) ease;
}
.liquid-glass-nav :deep(.liquid-glass-nav__items > :is(a, button):hover) { color: var(--liquid-nav-hover-text, var(--text-primary)); background: var(--surface-hover); }
.liquid-glass-nav :deep(.liquid-glass-nav__items > :is(.is-active, [aria-current='page'], [aria-pressed='true'])) {
  color: var(--liquid-nav-active-text, var(--brand-on-surface)); background: var(--surface-glass-inset); border-color: var(--border-glass-edge); box-shadow: var(--glass-rim-double); font-weight: 650;
}
.liquid-glass-nav :deep(.liquid-glass-nav__items > :is(a, button):focus-visible) { outline: 2px solid var(--border-focus); outline-offset: -2px; }
.liquid-glass-nav.is-grid { display: flex; width: 100%; border-radius: var(--radius-xl); }
.liquid-glass-nav.is-grid .liquid-glass-nav__items { display: grid; grid-template-columns: repeat(var(--liquid-nav-columns), minmax(0, 1fr)); }
.liquid-glass-nav.is-grid :deep(.liquid-glass-nav__items > :is(a, button)) { justify-content: flex-start; padding: 10px 16px; border-radius: var(--radius-lg); text-align: left; }
@media (max-width: 760px) {
  .liquid-glass-nav.is-grid .liquid-glass-nav__items { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .liquid-glass-nav :deep(.liquid-glass-nav__items > :is(a, button)) { padding-inline: 10px; }
}
@media (prefers-reduced-motion: reduce) { .liquid-glass-nav :deep(.liquid-glass-nav__items > :is(a, button)) { transition: none; } }
@media (forced-colors: active) {
  .liquid-glass-nav :deep(.liquid-glass-nav__items > :is(a, button)) { color: ButtonText; }
  .liquid-glass-nav :deep(.liquid-glass-nav__items > :is(.is-active, [aria-current='page'], [aria-pressed='true'])) { color: HighlightText; background: Highlight; border-color: Highlight; }
}
</style>

<script setup lang="ts">
import { useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  source?: LiquidGlassSource
  presentation?: 'segmented' | 'underline'
}>(), { presentation: 'segmented' })
const source = useLiquidGlassSource(() => props.source)
</script>

<template>
  <el-tabs v-bind="$attrs" class="liquid-glass-tabs" :class="{ 'samples-wallpaper': source === 'wallpaper', 'liquid-glass-tabs--underline': props.presentation === 'underline' }">
    <template v-for="(_, name) in $slots" #[name]="slotProps"><slot :name="name" v-bind="slotProps || {}" /></template>
  </el-tabs>
</template>

<style scoped lang="scss">
.liquid-glass-tabs { --liquid-tab-ink: var(--text-secondary); --liquid-tab-active: var(--brand-on-surface); --liquid-tab-selected: var(--surface-glass-inset); --liquid-tab-fallback: var(--surface-fallback-control); min-width: 0; }
.liquid-glass-tabs.samples-wallpaper {
  // Scope only navigation ink; content and teleported forms keep their own reading surfaces.
  --liquid-tab-ink: var(--text-on-wallpaper-secondary);
  --liquid-tab-active: var(--text-on-wallpaper);
  --liquid-tab-selected: color-mix(in srgb, var(--text-on-wallpaper) 10%, transparent);
  --liquid-tab-fallback: var(--surface-fallback-shell);
}
.liquid-glass-tabs :deep(.el-tabs__nav-wrap::after), .liquid-glass-tabs :deep(.el-tabs__active-bar) { display: none; }
.liquid-glass-tabs :deep(.el-tabs__nav) {
  display: flex; gap: 4px; padding: 4px; border: 1px solid var(--border-glass-edge); border-radius: var(--glass-control-radius);
  background: var(--surface-glass-navigation); box-shadow: var(--glass-rim-double); -webkit-backdrop-filter: blur(var(--glass-blur-frost)); backdrop-filter: blur(var(--glass-blur-frost));
}
.liquid-glass-tabs :deep(.el-tabs__item) {
  height: var(--control-height); padding: 0 18px !important; border: 1px solid transparent; border-radius: var(--glass-control-radius);
  color: var(--liquid-tab-ink); font-size: 13px; font-weight: 500;
}
.liquid-glass-tabs :deep(.el-tabs__item.is-active) { color: var(--liquid-tab-active); background: var(--liquid-tab-selected); border-color: var(--border-glass-edge); box-shadow: var(--glass-rim-double); font-weight: 650; }
.liquid-glass-tabs :deep(.el-tabs__item.is-focus) { outline: 2px solid var(--border-focus); outline-offset: -2px; }
.liquid-glass-tabs--underline { --liquid-tab-fallback: transparent; }
.liquid-glass-tabs--underline :deep(.el-tabs__header) { margin-bottom: 0; }
.liquid-glass-tabs--underline :deep(.el-tabs__nav-wrap) { border-bottom: 1px solid var(--border-divider); }
.liquid-glass-tabs--underline :deep(.el-tabs__nav) {
  gap: 28px; padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none;
  -webkit-backdrop-filter: none; backdrop-filter: none;
}
.liquid-glass-tabs--underline :deep(.el-tabs__item) {
  position: relative; height: 52px; padding: 0 2px !important; border: 0; border-radius: 0; font-size: 13px;
}
.liquid-glass-tabs--underline :deep(.el-tabs__item.is-active) {
  background: transparent; border: 0; box-shadow: none;
}
.liquid-glass-tabs--underline :deep(.el-tabs__item.is-active::after) {
  position: absolute; right: 0; bottom: 0; left: 0; height: 3px; border-radius: 3px 3px 0 0;
  background: currentColor; content: '';
}
.liquid-glass-tabs--underline :deep(.el-tabs__item.is-focus) { outline-offset: -4px; }
:root[data-reduce-transparency='true'] .liquid-glass-tabs :deep(.el-tabs__nav) { background: var(--liquid-tab-fallback); -webkit-backdrop-filter: none; backdrop-filter: none; }
@media (prefers-reduced-transparency: reduce) {
  .liquid-glass-tabs :deep(.el-tabs__nav) { background: var(--liquid-tab-fallback); -webkit-backdrop-filter: none; backdrop-filter: none; }
}
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .liquid-glass-tabs :deep(.el-tabs__nav) { background: var(--liquid-tab-fallback); }
}
@media (forced-colors: active) {
  .liquid-glass-tabs :deep(.el-tabs__nav) { background: Canvas; border-color: CanvasText; }
  .liquid-glass-tabs :deep(.el-tabs__item) { color: CanvasText; }
  .liquid-glass-tabs :deep(.el-tabs__item.is-active) { color: HighlightText; background: Highlight; }
}
</style>

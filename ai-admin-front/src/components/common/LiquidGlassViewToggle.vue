<script setup lang="ts">
import { Grid, Menu } from '@element-plus/icons-vue'
import LiquidGlassLayer from './LiquidGlassLayer.vue'
import { useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

const props = defineProps<{ source?: LiquidGlassSource }>()
const model = defineModel<'table' | 'card'>({ required: true })
const source = useLiquidGlassSource(() => props.source)
const views = [{ value: 'table' as const, label: '表格视图', icon: Grid }, { value: 'card' as const, label: '卡片视图', icon: Menu }]
</script>

<template>
  <div class="liquid-view-toggle glass-surface-lens liquid-glass-host" :class="{ 'glass-workspace-adaptive': source === 'wallpaper' }" role="group" aria-label="列表视图">
    <LiquidGlassLayer :source="source" :bezel="7" :depth="12" />
    <span class="liquid-view-toggle__bubble glass-surface-selection" :class="{ 'is-card': model === 'card' }" aria-hidden="true" />
    <el-tooltip v-for="view in views" :key="view.value" :content="view.label" placement="top">
      <button type="button" :aria-label="view.label" :aria-pressed="model === view.value" @click="model = view.value">
        <el-icon><component :is="view.icon" /></el-icon>
      </button>
    </el-tooltip>
  </div>
</template>

<style scoped lang="scss">
.liquid-view-toggle {
  position: relative;
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  height: var(--control-height);
  padding: 3px;
  border-radius: var(--glass-control-radius);
  isolation: isolate;
}
.liquid-view-toggle__bubble {
  position: absolute;
  inset: 3px auto 3px 3px;
  width: 32px;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--glass-control-radius);
  background: var(--surface-solid-control);
  box-shadow: var(--inner-highlight);
  transition: transform var(--motion-duration-fast) var(--motion-easing-standard);
  &.is-card { transform: translateX(32px); }
}
.liquid-view-toggle button {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 32px;
  height: 100%;
  padding: 0;
  border: 0;
  border-radius: var(--glass-control-radius);
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
  &[aria-pressed='true'] { color: var(--brand-on-surface); }
  &:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 2px; }
}
@media (prefers-reduced-motion: reduce) {
  .liquid-view-toggle__bubble { transition: none; }
}
@media (forced-colors: active) {
  .liquid-view-toggle__bubble { background: Highlight; }
  .liquid-view-toggle button[aria-pressed='true'] { color: HighlightText; }
}
</style>

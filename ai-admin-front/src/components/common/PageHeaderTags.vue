<script setup lang="ts">
import { useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

const props = withDefaults(defineProps<{
  collapsed?: boolean
  source?: LiquidGlassSource
  ariaLabel?: string
}>(), { collapsed: false, ariaLabel: '页面信息' })
const source = useLiquidGlassSource(() => props.source)
</script>

<template>
  <div
    class="page-header-tags"
    :class="{ 'is-collapsed': props.collapsed, 'glass-workspace-adaptive': source === 'wallpaper' }"
    role="group"
    :aria-label="props.ariaLabel"
    :aria-hidden="props.collapsed"
    :inert="props.collapsed"
  >
    <div class="page-header-tags__items"><slot /></div>
  </div>
</template>

<style scoped lang="scss">
.page-header-tags {
  --header-tag-base: var(--surface-solid-control);
  display: grid;
  grid-template-rows: 1fr;
  min-width: 0;
  margin-top: var(--layout-page-header-meta-gap);
  overflow: hidden;
  color: var(--text-secondary);
  opacity: 1;
  transition:
    grid-template-rows var(--motion-duration-normal) var(--motion-easing-standard),
    margin var(--motion-duration-normal) var(--motion-easing-standard),
    opacity var(--motion-duration-fast) ease;
}

.page-header-tags.glass-workspace-adaptive {
  --header-tag-base: transparent;
}

.page-header-tags__items {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--layout-page-header-tag-gap);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.page-header-tags :deep(.el-tag) {
  --header-tag-tone: var(--brand-on-surface);
  --el-tag-text-color: var(--header-tag-tone);
  --el-tag-bg-color: color-mix(in srgb, var(--header-tag-tone) 7%, var(--header-tag-base));
  --el-tag-border-color: color-mix(in srgb, var(--header-tag-tone) 16%, transparent);
  height: 22px;
  max-width: 100%;
  padding-inline: 9px;
  border: 1px solid var(--el-tag-border-color);
  border-radius: var(--radius-pill);
  color: var(--el-tag-text-color);
  background: var(--el-tag-bg-color);
  box-shadow: none;
  font-size: 12px;
  font-weight: 500;
}

.page-header-tags :deep(.el-tag__content) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-header-tags :deep(.el-tag--info) { --header-tag-tone: var(--text-secondary); }
.page-header-tags :deep(.el-tag--success) { --header-tag-tone: var(--status-success); }
.page-header-tags :deep(.el-tag--warning) { --header-tag-tone: var(--status-warning); }
.page-header-tags :deep(.el-tag--danger) { --header-tag-tone: var(--status-danger); }

.page-header-tags.is-collapsed {
  grid-template-rows: 0fr;
  margin-top: 0;
  opacity: 0;
  pointer-events: none;
}

@media (max-width: 760px) {
  .page-header-tags :deep(.el-tag) {
    height: 20px;
    padding-inline: 7px;
    font-size: 11px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .page-header-tags { transition: none; }
}

@media (forced-colors: active) {
  .page-header-tags :deep(.el-tag) {
    color: CanvasText;
    border-color: CanvasText;
    background: Canvas;
  }
}
</style>

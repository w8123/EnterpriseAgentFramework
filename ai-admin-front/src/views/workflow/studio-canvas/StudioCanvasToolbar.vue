<template>
  <div
    class="canvas-operator-panel glass-surface-shell liquid-glass-host"
    aria-label="画布工具"
    @mousedown.stop
    @click.stop
  >
    <LiquidGlassLayer :bezel="14" :depth="24" />
    <el-tooltip content="搜索节点 Ctrl+F" placement="top">
      <el-button :icon="Search" circle aria-label="搜索节点" @click="emit('search')" />
    </el-tooltip>
    <el-divider direction="vertical" />
    <el-tooltip content="撤销 Ctrl+Z" placement="top">
      <el-button :icon="RefreshLeft" circle aria-label="撤销" :disabled="readOnly || !canUndo" @click="emit('undo')" />
    </el-tooltip>
    <el-tooltip content="重做 Ctrl+Y" placement="top">
      <el-button :icon="RefreshRight" circle aria-label="重做" :disabled="readOnly || !canRedo" @click="emit('redo')" />
    </el-tooltip>
    <el-divider direction="vertical" />
    <el-tooltip content="复制 Ctrl+C" placement="top">
      <el-button :icon="CopyDocument" circle aria-label="复制节点" :disabled="!canCopy" @click="emit('copy')" />
    </el-tooltip>
    <el-tooltip content="粘贴 Ctrl+V" placement="top">
      <el-button :icon="Files" circle aria-label="粘贴节点" :disabled="readOnly || !canPaste" @click="emit('paste')" />
    </el-tooltip>
    <el-tooltip content="折叠/展开节点" placement="top">
      <el-button :icon="Operation" circle aria-label="折叠或展开节点" :disabled="!hasSelectedNode" @click="emit('toggle-collapse')" />
    </el-tooltip>
    <el-divider direction="vertical" />
    <el-tooltip content="自动整理 Ctrl+O" placement="top">
      <el-button :icon="Rank" circle aria-label="自动整理" :loading="layoutRunning" @click="emit('layout')" />
    </el-tooltip>
    <el-tooltip content="聚焦画布 Ctrl+1" placement="top">
      <el-button :icon="Aim" circle aria-label="聚焦画布" @click="emit('fit')" />
    </el-tooltip>
    <el-tooltip content="缩小" placement="top">
      <el-button :icon="ZoomOut" circle aria-label="缩小画布" @click="emit('zoom-out')" />
    </el-tooltip>
    <el-tooltip content="放大" placement="top">
      <el-button :icon="ZoomIn" circle aria-label="放大画布" @click="emit('zoom-in')" />
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">
import { Aim, CopyDocument, Files, Operation, Rank, RefreshLeft, RefreshRight, Search, ZoomIn, ZoomOut } from '@element-plus/icons-vue'
import LiquidGlassLayer from '@/components/common/LiquidGlassLayer.vue'

/** Floating canvas tools; each button mirrors a keyboard shortcut handled by the Studio page. */
defineProps<{
  readOnly: boolean
  canUndo: boolean
  canRedo: boolean
  canCopy: boolean
  canPaste: boolean
  hasSelectedNode: boolean
  layoutRunning: boolean
}>()

const emit = defineEmits<{
  search: []
  undo: []
  redo: []
  copy: []
  paste: []
  'toggle-collapse': []
  layout: []
  fit: []
  'zoom-out': []
  'zoom-in': []
}>()
</script>

<style scoped lang="scss">
.canvas-operator-panel {
  position: absolute;
  left: 18px;
  right: auto;
  top: 18px;
  bottom: auto;
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px;
  border-radius: var(--radius-xl);
  max-width: calc(100% - 36px);
  overflow-x: auto;
  overflow-y: hidden;
  transition: padding 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}

.canvas-operator-panel .el-button {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  margin-left: 0;
  border-color: transparent;
  border-radius: 7px;
  background: transparent;
  box-shadow: none;
  color: var(--text-secondary);
}

.canvas-operator-panel .el-button:not(.is-disabled):hover {
  background: var(--el-color-primary-light-9);
  color: var(--brand-primary);
}

.canvas-operator-panel .el-button.is-disabled {
  color: var(--text-disabled);
}

.canvas-operator-panel .el-button:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: -1px;
}

.canvas-operator-panel :deep(.el-divider) {
  margin: 0 3px;
  border-color: var(--border-divider);
}

@media (max-width: 760px) {
  .canvas-operator-panel {
    left: 86px;
    max-width: calc(100% - 100px);
  }
}


</style>

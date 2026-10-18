<template>
  <div
    class="canvas-operator-panel"
    aria-label="画布工具"
    @mousedown.stop
    @click.stop
  >
    <el-tooltip content="搜索节点 Ctrl+F" placement="top">
      <el-button :icon="Search" circle aria-label="搜索节点" @click="emit('search')" />
    </el-tooltip>
    <el-divider direction="vertical" />
    <el-tooltip content="撤销 Ctrl+Z" placement="top">
      <el-button :icon="RefreshLeft" circle :disabled="readOnly || !canUndo" @click="emit('undo')" />
    </el-tooltip>
    <el-tooltip content="重做 Ctrl+Y" placement="top">
      <el-button :icon="RefreshRight" circle :disabled="readOnly || !canRedo" @click="emit('redo')" />
    </el-tooltip>
    <el-divider direction="vertical" />
    <el-tooltip content="复制 Ctrl+C" placement="top">
      <el-button :icon="CopyDocument" circle :disabled="!canCopy" @click="emit('copy')" />
    </el-tooltip>
    <el-tooltip content="粘贴 Ctrl+V" placement="top">
      <el-button :icon="Files" circle :disabled="readOnly || !canPaste" @click="emit('paste')" />
    </el-tooltip>
    <el-tooltip content="折叠/展开节点" placement="top">
      <el-button :icon="Operation" circle :disabled="!hasSelectedNode" @click="emit('toggle-collapse')" />
    </el-tooltip>
    <el-divider direction="vertical" />
    <el-tooltip content="自动整理 Ctrl+O" placement="top">
      <el-button :icon="Rank" circle :loading="layoutRunning" @click="emit('layout')" />
    </el-tooltip>
    <el-tooltip content="聚焦画布 Ctrl+1" placement="top">
      <el-button :icon="Aim" circle @click="emit('fit')" />
    </el-tooltip>
    <el-tooltip content="缩小" placement="top">
      <el-button :icon="ZoomOut" circle @click="emit('zoom-out')" />
    </el-tooltip>
    <el-tooltip content="放大" placement="top">
      <el-button :icon="ZoomIn" circle @click="emit('zoom-in')" />
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">
import { Aim, CopyDocument, Files, Operation, Rank, RefreshLeft, RefreshRight, Search, ZoomIn, ZoomOut } from '@element-plus/icons-vue'

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
  bottom: 68px;
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(12px);
  max-width: calc(100% - 36px);
  overflow-x: auto;
  transition: padding 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;
}

.canvas-operator-panel .el-button {
  margin-left: 0;
}


</style>

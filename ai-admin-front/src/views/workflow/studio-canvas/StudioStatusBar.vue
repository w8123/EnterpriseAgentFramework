<template>
  <div class="canvas-statusbar">
    <div class="status-left">
      <el-tooltip :content="toolsOpen ? '收起画布工具' : '展开画布工具'" placement="top">
        <button
          class="status-pill canvas-tools-pill"
          :class="{ active: toolsOpen }"
          type="button"
          :aria-expanded="toolsOpen"
          @click="emit('toggle-tools')"
        >
          <el-icon><Operation /></el-icon>
          <strong>工具</strong>
          <el-icon class="operator-caret">
            <ArrowUp v-if="!toolsOpen" />
            <ArrowDown v-else />
          </el-icon>
        </button>
      </el-tooltip>
      <button
        class="status-pill"
        :class="{
          active: inspectorPanel === 'validation',
          danger: validationTagType === 'danger',
          warning: validationTagType === 'warning',
          success: validationTagType === 'success',
        }"
        type="button"
        :aria-expanded="inspectorPanel === 'validation'"
        @click="emit('toggle-inspector', 'validation')"
      >
        <span class="status-dot"></span>
        <strong>{{ validationLabel }}</strong>
        <em>草稿检查</em>
      </button>
      <button
        class="status-pill"
        :class="{ active: inspectorPanel === 'variables' }"
        type="button"
        :aria-expanded="inspectorPanel === 'variables'"
        @click="emit('toggle-inspector', 'variables')"
      >
        <strong>变量</strong>
        <em>{{ variableCount }}</em>
      </button>
      <button
        v-if="!hideAi"
        class="status-pill smart-edit-pill"
        :class="{ active: aiOpen }"
        type="button"
        :aria-expanded="aiOpen"
        @click="emit('toggle-ai')"
      >
        <el-icon><MagicStick /></el-icon>
        <strong>AI 编排</strong>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ArrowDown, ArrowUp, MagicStick, Operation } from '@element-plus/icons-vue'

/** Bottom bar of the canvas: toggles for the tools, draft checks, variables and AI editing. */
defineProps<{
  toolsOpen: boolean
  inspectorPanel: 'validation' | 'variables' | null
  validationLabel: string
  validationTagType: 'danger' | 'warning' | 'success' | 'info'
  variableCount: number
  aiOpen: boolean
  /** View-only roles cannot use AI editing. */
  hideAi?: boolean
}>()

const emit = defineEmits<{
  'toggle-tools': []
  'toggle-inspector': [panel: 'validation' | 'variables']
  'toggle-ai': []
}>()
</script>

<style scoped lang="scss">
.canvas-statusbar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 7;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  min-height: 52px;
  padding: 8px 18px;
  border-top: 1px solid var(--border-glass-edge);
  background: var(--surface-glass-shell);
  -webkit-backdrop-filter: blur(var(--glass-blur-shell)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-shell)) saturate(var(--glass-saturation));
}

.status-left {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--surface-solid-control);
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 12px;
  font-family: inherit;
  transition: border-color var(--transition-fast), background var(--transition-fast);
}

.status-pill:hover {
  border-color: var(--border-focus);
  color: var(--text-primary);
}

.status-pill:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.status-pill strong,
.status-pill em {
  font-style: normal;
  line-height: 1;
}

.status-pill strong {
  font-weight: 600;
}

.status-pill em {
  color: var(--text-muted);
}

.status-pill.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--brand-primary);
}

.canvas-tools-pill {
  color: var(--brand-primary);
}

.canvas-tools-pill.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}

.smart-edit-pill {
  color: var(--brand-primary);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--status-neutral);
}

.status-pill.danger {
  border-color: var(--el-color-danger-light-5);
  color: var(--status-danger);
}

.status-pill.warning {
  border-color: var(--el-color-warning-light-5);
  color: var(--status-warning);
}

.status-pill.danger.active {
  border-color: var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
}

.status-pill.warning.active {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
}

.status-pill.danger .status-dot {
  background: var(--status-danger);
}

.status-pill.warning .status-dot {
  background: var(--status-warning);
}

.status-pill.success .status-dot {
  background: var(--status-success);
}

@media (max-width: 760px) {
  .canvas-statusbar {
    left: 72px;
    padding: 8px 10px;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: thin;
  }

  .status-left {
    flex-wrap: nowrap;
    gap: 6px;
  }

  .status-pill {
    flex-shrink: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .status-pill {
    transition: none;
  }
}
</style>

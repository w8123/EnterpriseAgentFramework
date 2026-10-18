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
  gap: 10px;
  min-height: 56px;
  padding: 8px 18px;
  border-top: 1px solid rgba(148, 163, 184, 0.28);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(12px);
}

.status-left {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid #dbe5f2;
  border-radius: 8px;
  background: #fff;
  color: #334155;
  cursor: pointer;
  font-size: 12px;
}

.status-pill strong,
.status-pill em {
  font-style: normal;
  line-height: 1;
}

.status-pill strong {
  font-weight: 700;
}

.status-pill em {
  color: #64748b;
}

.status-pill.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 8px 20px rgba(79, 70, 229, 0.1);
}

.canvas-tools-pill {
  color: #4f46e5;
}

.canvas-tools-pill.active {
  border-color: rgba(99, 102, 241, 0.38);
  background: linear-gradient(135deg, rgba(238, 242, 255, 0.98), rgba(240, 253, 250, 0.9));
  box-shadow: 0 8px 20px rgba(79, 70, 229, 0.12);
}

.smart-edit-pill {
  border-color: rgba(99, 102, 241, 0.3);
  color: #4338ca;
  background: linear-gradient(135deg, rgba(238, 242, 255, 0.96), rgba(240, 253, 250, 0.86));
  box-shadow: 0 10px 24px rgba(79, 70, 229, 0.1);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #22c55e;
}

.status-pill.danger {
  border-color: #fecaca;
  color: #b91c1c;
}

.status-pill.warning {
  border-color: #fed7aa;
  color: #c2410c;
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
  background: #ef4444;
}

.status-pill.warning .status-dot {
  background: #f59e0b;
}
</style>

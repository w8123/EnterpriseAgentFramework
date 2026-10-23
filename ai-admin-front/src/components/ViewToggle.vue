<template>
  <div class="view-toggle">
    <el-tooltip content="表格视图" placement="top">
      <button
        :class="['toggle-btn', { active: modelValue === 'table' }]"
        @click="emit('update:modelValue', 'table')"
      >
        <el-icon><Grid /></el-icon>
      </button>
    </el-tooltip>
    <el-tooltip content="卡片视图" placement="top">
      <button
        :class="['toggle-btn', { active: modelValue === 'card' }]"
        @click="emit('update:modelValue', 'card')"
      >
        <el-icon><Menu /></el-icon>
      </button>
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">
import { Grid, Menu } from '@element-plus/icons-vue'

defineProps<{
  modelValue: 'table' | 'card'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: 'table' | 'card']
}>()
</script>

<style scoped lang="scss">
.view-toggle {
  display: inline-flex;
  background: var(--surface-hover);
  border: 1px solid var(--border-glass-edge);
  border-radius: 8px;
  padding: 3px;
  gap: 2px;
}

.toggle-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s ease;
  font-size: 14px;

  &:hover {
    color: var(--text-disabled);
    background: var(--surface-hover);
  }

  &.active {
    background: rgb(var(--brand-primary-rgb) / 0.15);
    color: var(--brand-on-surface);
    box-shadow: 0 0 8px rgb(var(--brand-primary-rgb) / 0.15);
  }
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .view-toggle {
    background: var(--surface-solid-opaque-raised);
    border: 1px solid var(--border-readable);
  }

  .toggle-btn {
    color: var(--text-disabled);

    &:hover {
      color: var(--text-muted);
      background: var(--surface-solid-panel);
    }

    &.active {
      background: rgb(var(--brand-primary-rgb) / 0.1);
      color: var(--brand-on-surface);
      box-shadow: 0 0 8px rgb(var(--brand-primary-rgb) / 0.1);
    }
  }
}
</style>

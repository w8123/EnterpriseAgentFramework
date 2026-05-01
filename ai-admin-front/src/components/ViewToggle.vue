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

const props = defineProps<{
  modelValue: 'table' | 'card'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: 'table' | 'card']
}>()
</script>

<style scoped lang="scss">
.view-toggle {
  display: inline-flex;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
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
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s ease;
  font-size: 14px;

  &:hover {
    color: #94a3b8;
    background: rgba(255, 255, 255, 0.04);
  }

  &.active {
    background: rgba(99, 102, 241, 0.15);
    color: #a5b4fc;
    box-shadow: 0 0 8px rgba(99, 102, 241, 0.15);
  }
}

// ── 日间模式覆盖 ──
:global([data-theme="light"]) {
  .view-toggle {
    background: #f5f7fa;
    border: 1px solid #e4e7ed;
  }

  .toggle-btn {
    color: #94a3b8;

    &:hover {
      color: #64748b;
      background: #ffffff;
    }

    &.active {
      background: rgba(99, 102, 241, 0.1);
      color: #6366f1;
      box-shadow: 0 0 8px rgba(99, 102, 241, 0.1);
    }
  }
}
</style>

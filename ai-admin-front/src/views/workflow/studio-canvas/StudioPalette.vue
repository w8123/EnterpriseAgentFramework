<template>
  <aside class="palette" aria-label="节点操作">
    <el-tooltip content="添加节点" placement="right">
      <button class="rail-primary" type="button" aria-label="添加节点" :disabled="readOnly" @click="emit('open-search')">
        <el-icon><Plus /></el-icon>
      </button>
    </el-tooltip>
  </aside>
</template>

<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'

defineProps<{ readOnly: boolean }>()

const emit = defineEmits<{
  'open-search': []
}>()
</script>

<style scoped lang="scss">
.palette {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  padding: 14px 10px;
  overflow: hidden;
  border-right: 1px solid var(--border-glass);
  background: var(--surface-glass-panel);
}

.rail-primary {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 46px;
  height: 46px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-hover));
  color: var(--el-color-white);
  box-shadow: 0 12px 24px rgb(var(--brand-primary-rgb) / 0.28);
  cursor: pointer;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}

.rail-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 14px 28px rgb(var(--brand-primary-rgb) / 0.34);
}

.rail-primary:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 3px;
}

.rail-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (prefers-reduced-motion: reduce) {
  .rail-primary {
    transition: none;
  }
}
</style>

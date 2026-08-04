<template>
  <el-result
    icon="error"
    :title="title"
    :sub-title="message"
    class="project-workbench-load-error-state"
    aria-live="polite"
  >
    <template #extra>
      <el-button type="primary" :loading="loading" @click="emit('retry')">
        重新加载
      </el-button>
    </template>
  </el-result>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  title?: string
  message: string
  loading?: boolean
}>(), {
  title: '工作台加载失败',
  loading: false,
})

const emit = defineEmits<{
  retry: []
}>()
</script>

<style scoped>
.project-workbench-load-error-state {
  box-sizing: border-box;
  min-height: 360px;
  flex: 1 1 auto;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-card);
  color: var(--text-primary);
  -webkit-backdrop-filter: blur(var(--glass-blur-panel)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-panel)) saturate(var(--glass-saturation));
}

.project-workbench-load-error-state :deep(.el-result__title p) {
  color: var(--text-primary);
}

.project-workbench-load-error-state :deep(.el-result__subtitle p) {
  color: var(--text-secondary);
}
</style>

<template>
  <el-result
    icon="warning"
    title="项目不存在"
    :sub-title="description"
    class="project-route-missing-state"
    aria-live="polite"
  >
    <template #extra>
      <el-button type="primary" @click="goProjectList">返回项目管理</el-button>
    </template>
  </el-result>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps<{
  projectCode?: string
}>()

const router = useRouter()
const description = computed(() =>
  props.projectCode
    ? `未找到项目“${props.projectCode}”。该项目可能已被删除，或当前地址中的项目编码已失效。`
    : '该项目可能已被删除，或当前地址中的项目编码已失效。',
)

function goProjectList() {
  router.push({ name: 'RegistryProjectList' })
}
</script>

<style scoped>
.project-route-missing-state {
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

.project-route-missing-state :deep(.el-result__title p) {
  color: var(--text-primary);
}

.project-route-missing-state :deep(.el-result__subtitle p) {
  color: var(--text-secondary);
}
</style>

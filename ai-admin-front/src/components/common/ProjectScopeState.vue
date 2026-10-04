<template>
  <div
    class="project-scope-state"
    :class="`is-${status}`"
    :role="status === 'blocked' ? 'alert' : 'status'"
    aria-live="polite"
  >
    <div class="project-scope-state__icon" aria-hidden="true"><Cpu /></div>
    <h3>{{ title }}</h3>
    <p>{{ message }}</p>
    <div class="project-scope-state__actions">
      <el-button v-if="showSelectProject" type="primary" @click="emit('select-project')">
        {{ selectLabel }}
      </el-button>
      <el-button v-if="canRetry" type="primary" :loading="retrying" @click="emit('retry')">
        {{ retryLabel }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Cpu } from '@element-plus/icons-vue'

export type ProjectScopeStateStatus = 'inactive' | 'pending' | 'resolved' | 'blocked'

withDefaults(defineProps<{
  title: string
  message: string
  status: ProjectScopeStateStatus
  showSelectProject?: boolean
  canRetry?: boolean
  retrying?: boolean
  selectLabel?: string
  retryLabel?: string
}>(), {
  showSelectProject: false,
  canRetry: false,
  retrying: false,
  selectLabel: '展开侧栏选择项目',
  retryLabel: '重试',
})

const emit = defineEmits<{
  (event: 'select-project'): void
  (event: 'retry'): void
}>()
</script>

<style scoped lang="scss">
.project-scope-state {
  display: grid;
  width: min(620px, 100%);
  min-height: 210px;
  place-items: center;
  align-content: center;
  gap: 8px;
  margin: 0 auto;
  padding: 26px;
  border: 1px dashed var(--border-readable);
  border-radius: var(--radius-lg);
  text-align: center;
  background: color-mix(in srgb, var(--surface-solid-panel) 84%, transparent);

  h3,
  p {
    margin: 0;
  }

  h3 {
    color: var(--text-primary);
    font-size: 17px;
  }

  p {
    max-width: 520px;
    color: var(--text-muted);
    font-size: 13px;
    line-height: 1.6;
  }
}

.project-scope-state__icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.2);
  border-radius: 12px;
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}

.project-scope-state__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 8px;
}
</style>

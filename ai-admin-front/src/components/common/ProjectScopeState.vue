<template>
  <LiquidGlassEmptyState
    class="project-scope-state"
    :class="`is-${status}`"
    :title="title"
    :description="message"
    :icon="Cpu"
    :source="source"
    :role="status === 'blocked' ? 'alert' : 'status'"
    aria-live="polite"
  >
      <LiquidGlassButton v-if="showSelectProject" type="primary" @click="emit('select-project')">
        {{ selectLabel }}
      </LiquidGlassButton>
      <LiquidGlassButton v-if="canRetry" type="primary" :loading="retrying" @click="emit('retry')">
        {{ retryLabel }}
      </LiquidGlassButton>
  </LiquidGlassEmptyState>
</template>

<script setup lang="ts">
import { Cpu } from '@element-plus/icons-vue'
import LiquidGlassEmptyState from './LiquidGlassEmptyState.vue'
import LiquidGlassButton from './LiquidGlassButton.vue'
import type { LiquidGlassSource } from './liquidGlassContext'

export type ProjectScopeStateStatus = 'inactive' | 'pending' | 'resolved' | 'blocked'

withDefaults(defineProps<{
  title: string
  message: string
  status: ProjectScopeStateStatus
  source?: LiquidGlassSource
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

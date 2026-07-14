<script setup lang="ts">
import { computed } from 'vue'
import type { StatusTone } from './glassWorkbench'

const props = withDefaults(
  defineProps<{
    label: string
    tone?: StatusTone
    size?: 'small' | 'default' | 'large'
    pulse?: boolean
  }>(),
  {
    tone: 'neutral',
    size: 'small',
    pulse: false,
  },
)

const tagType = computed(() => (props.tone === 'neutral' ? 'info' : props.tone))
</script>

<template>
  <el-tag
    class="status-tag"
    :class="`status-tag--${props.tone}`"
    :type="tagType"
    :size="props.size"
  >
    <span v-if="props.pulse" class="status-tag__pulse" aria-hidden="true" />
    {{ props.label }}
  </el-tag>
</template>

<style scoped lang="scss">
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.status-tag__pulse {
  width: 0.45rem;
  height: 0.45rem;
  border-radius: 50%;
  background: currentColor;
  animation: status-tag-pulse var(--motion-duration-slow) var(--motion-easing-standard) infinite alternate;
}

.status-tag--success {
  --el-tag-text-color: var(--status-success);
  --el-tag-bg-color: var(--status-success-soft);
  --el-tag-border-color: var(--status-success);
}

.status-tag--warning {
  --el-tag-text-color: var(--status-warning);
  --el-tag-bg-color: var(--status-warning-soft);
  --el-tag-border-color: var(--status-warning);
}

.status-tag--danger {
  --el-tag-text-color: var(--status-danger);
  --el-tag-bg-color: var(--status-danger-soft);
  --el-tag-border-color: var(--status-danger);
}

.status-tag--info {
  --el-tag-text-color: var(--status-info);
  --el-tag-bg-color: var(--status-info-soft);
  --el-tag-border-color: var(--status-info);
}

.status-tag--neutral {
  --el-tag-text-color: var(--status-neutral);
  --el-tag-bg-color: var(--status-neutral-soft);
  --el-tag-border-color: var(--status-neutral);
}

@keyframes status-tag-pulse {
  from {
    opacity: 0.45;
    transform: scale(0.82);
  }

  to {
    opacity: 1;
    transform: scale(1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .status-tag__pulse {
    animation: none;
  }
}
</style>

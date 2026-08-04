<script setup lang="ts">
import { computed } from 'vue'
import { Monitor } from '@element-plus/icons-vue'
import type { AiCodingHandoffPackage } from '@/types/aiCodingTask'
import { aiCodingProviderLabel } from '@/utils/aiCodingPresentation'

const props = defineProps<{
  handoff: AiCodingHandoffPackage | null
}>()

const promptCharacterSummary = computed(() => {
  const promptCharacters = props.handoff?.promptCharacters
    ?? props.handoff?.prompt.length
    ?? 0
  const promptCharacterLimit = props.handoff?.promptCharacterLimit
  return promptCharacterLimit
    ? `${promptCharacters} / ${promptCharacterLimit} 字符`
    : `${promptCharacters} 字符`
})

function formatDateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section class="ai-coding-handoff-panel">
    <div class="handoff-heading">
      <span><el-icon><Monitor /></el-icon></span>
      <div>
        <strong>任务已创建</strong>
        <p>
          将下面的一次性交接包复制到
          {{ aiCodingProviderLabel(handoff?.executorProvider) }}。
          激活码将在 {{ formatDateTime(handoff?.activationExpiresAt) }} 失效。
        </p>
      </div>
    </div>
    <div class="compact-handoff-meta" aria-live="polite">
      <span>紧凑交接包</span>
      <strong>{{ promptCharacterSummary }}</strong>
    </div>
    <el-input
      :model-value="handoff?.prompt || ''"
      type="textarea"
      :rows="16"
      readonly
      aria-label="AI 编程工具一次性交接包"
    />
  </section>
</template>

<script setup lang="ts">
import { Monitor } from '@element-plus/icons-vue'
import type { AiCodingHandoffPackage } from '@/types/aiCodingTask'

defineProps<{
  handoff: AiCodingHandoffPackage | null
}>()

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
        <strong>任务领取码已生成</strong>
        <p>
          点击「交给我的 AI」，使用平台 AI 连接领取任务。
          一次性领取码将在 {{ formatDateTime(handoff?.activationExpiresAt) }} 失效。
        </p>
      </div>
    </div>
  </section>
</template>

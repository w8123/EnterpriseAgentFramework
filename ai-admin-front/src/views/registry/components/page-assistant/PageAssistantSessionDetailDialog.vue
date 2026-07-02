<script setup lang="ts">
import type { PageAssistantSessionSummary } from '@/types/scanProject'
import { formatEvidence } from '@/views/registry/pageAssistantWizardViewModel'
import { pageAccessStateLabel, pageAccessStateTagType, stepStatusTagType } from '@/views/registry/pageAssistantWizardUtils'

defineProps<{
  visible: boolean
  session: PageAssistantSessionSummary | null
  pageTitle: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'update:session': [value: PageAssistantSessionSummary | null]
  createAssistant: [session: PageAssistantSessionSummary]
}>()

function close() {
  emit('update:session', null)
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="页面接入详情"
    width="780px"
    destroy-on-close
    @update:model-value="(value: boolean) => { if (!value) close() }"
  >
    <div v-if="session" class="page-access-detail">
      <div class="page-access-detail-head">
        <span>
          <strong>{{ pageTitle }}</strong>
          <small>{{ session.targetRoute || '等待 Cursor 绑定目标路由' }}</small>
        </span>
        <el-tag :type="pageAccessStateTagType(session.completionState)" effect="plain">
          {{ pageAccessStateLabel(session.completionState) }}
        </el-tag>
      </div>
      <div class="page-access-detail-meta">
        <span>Session：{{ session.sessionId }}</span>
        <span>工具：{{ session.toolName || '-' }}</span>
        <span>动作：{{ session.actionCount }}</span>
        <span>最近回传：{{ session.lastReportedAt || '-' }}</span>
      </div>
      <div class="page-access-step-detail-list">
        <section v-for="step in session.steps" :key="step.stepKey" class="page-access-step-detail">
          <div>
            <el-tag size="small" :type="stepStatusTagType(step.status)" effect="plain">{{ step.status }}</el-tag>
            <strong>{{ step.title }}</strong>
            <small>{{ step.stepKey }}</small>
          </div>
          <p>{{ step.message || '暂无回传说明' }}</p>
          <pre v-if="Object.keys(step.evidence || {}).length">{{ formatEvidence(step.evidence) }}</pre>
        </section>
      </div>
    </div>
    <template #footer>
      <el-button @click="close">关闭</el-button>
      <el-button
        v-if="session"
        type="primary"
        :disabled="!session.targetPageKey"
        @click="emit('createAssistant', session)"
      >
        基于此页面创建助手
      </el-button>
    </template>
  </el-dialog>
</template>

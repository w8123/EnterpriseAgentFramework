<script setup lang="ts">
import { computed } from 'vue'
import { Promotion, Setting } from '@element-plus/icons-vue'
import type { AiCodingTaskDetail } from '@/types/aiCodingTask'
import type { WorkflowEngineeringDraftResult } from '@/types/pageWorkbench'
import { pageWorkbenchHumanText } from '@/utils/pageWorkbenchPresentation'

const props = defineProps<{
  detail: AiCodingTaskDetail | null
  busy?: boolean
}>()

const emit = defineEmits<{
  openStudio: [result: WorkflowEngineeringDraftResult]
  deliver: [result: WorkflowEngineeringDraftResult]
}>()

const result = computed<WorkflowEngineeringDraftResult | null>(() => {
  if (props.detail?.task.taskKind !== 'WORKFLOW_ENGINEERING') return null
  const artifacts = [...(props.detail.artifacts || [])].reverse()
  for (const artifact of artifacts) {
    if (artifact.processingStatus !== 'APPLIED' || !artifact.applicationResult) continue
    const value = artifact.applicationResult as {
      workflowEngineering?: WorkflowEngineeringDraftResult
    }
    if (value.workflowEngineering?.workflow?.id) return value.workflowEngineering
  }
  return null
})

const accepted = computed(
  () => props.detail?.task.executionStatus === 'COMPLETED',
)
</script>

<template>
  <article v-if="result" class="workflow-delivery-card">
    <header>
      <div>
        <small>页面助手草稿</small>
        <h3>{{ pageWorkbenchHumanText(result.workflow.name, '页面助手工作流') }}</h3>
        <p>{{ pageWorkbenchHumanText(result.summary, '当前草稿尚未提供中文说明。') }}</p>
      </div>
      <el-tag :type="result.validation.valid ? 'success' : 'warning'" effect="light">
        {{ result.validation.valid ? '当前报告校验通过' : '需在编排工作台修正' }}
      </el-tag>
    </header>

    <dl>
      <div>
        <dt>页面</dt>
        <dd>{{ result.pageKey }}</dd>
      </div>
      <div>
        <dt>页面操作</dt>
        <dd>{{ result.selectedActionKeys.join('、') }}</dd>
      </div>
    </dl>

    <div v-if="result.validation.errors.length" class="workflow-delivery-card__issues">
      <strong>当前校验问题</strong>
      <p v-for="issue in result.validation.errors.slice(0, 3)" :key="issue.code">
        {{ pageWorkbenchHumanText(issue.message, `校验问题 · ${issue.code}`) }}
      </p>
    </div>

    <p class="workflow-delivery-card__boundary">
      {{
        accepted
          ? '任务已审阅通过。发布时服务端会重新校验当前草稿，并在同一事务内发布工作流与智能体配置。'
          : '先在任务详情中审阅并通过验收；AI 编程工具回传不会自动发布或接入智能体。'
      }}
    </p>

    <footer>
      <el-button :icon="Setting" @click="emit('openStudio', result)">
        打开工作流编排
      </el-button>
      <el-button
        type="primary"
        :icon="Promotion"
        :disabled="!accepted"
        :loading="busy"
        @click="emit('deliver', result)"
      >
        发布并接入页面副驾驶
      </el-button>
    </footer>
  </article>
</template>

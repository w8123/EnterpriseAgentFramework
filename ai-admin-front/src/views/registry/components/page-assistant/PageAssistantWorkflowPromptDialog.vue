<script setup lang="ts">
import { DocumentCopy } from '@element-plus/icons-vue'
import type { AiAccessStep } from '@/types/scanProject'
import type { WorkflowAiCodingDraftEvidence } from '@/views/registry/pageAssistantWizardViewModel'
import { stepStatusTagType } from '@/views/registry/pageAssistantWizardUtils'

type WorkflowAiCodingPromptTool = 'Cursor' | 'Codex' | 'Claude Code'

const visible = defineModel<boolean>('visible', { required: true })
const workflowAiCodingPromptTool = defineModel<WorkflowAiCodingPromptTool>('workflowAiCodingPromptTool', { required: true })

defineProps<{
  workflowAiCodingDraftStep: AiAccessStep | null
  workflowAiCodingDraftEvidence: WorkflowAiCodingDraftEvidence
  workflowAiCodingValidationSummary: string
  workflowAiCodingPageAssistantValidationSummary: string
  workflowAiCodingRuntimeVerificationSummary: string
  workflowAiCodingPrompt: string
  pageAssistantManifestLoading: boolean
  workflowAiCodingResetting: boolean
  workflowAiCodingPromptCopied: boolean
}>()

const emit = defineEmits<{
  refreshStatus: []
  openStudio: []
  resetDraft: []
  useDraft: []
  copyPrompt: []
}>()
</script>

<template>
  <el-dialog
    v-model="visible"
    title="使用 AI Coding 生成页面助手 Workflow"
    width="860px"
    destroy-on-close
  >
    <div class="ai-prompt-dialog">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="复制给 Cursor / Codex / Claude Code"
        description="让外部 AI 工具通过 Workflow AI Coding REST API 创建 PAGE_ASSISTANT 草稿；本阶段只复制提示词，不在向导内自动调用 API。生成完成后 AI 工具应回传 workflow-ai-coding-result，向导将展示结果。"
      />
      <div v-if="workflowAiCodingDraftStep" class="workflow-ai-coding-result-card dialog">
        <div class="workflow-ai-coding-result-head">
          <el-tag :type="stepStatusTagType(workflowAiCodingDraftStep.status)" effect="plain">
            已回传 {{ workflowAiCodingDraftStep.status }}
          </el-tag>
          <strong>{{ workflowAiCodingDraftStep.message || 'Workflow AI Coding 草稿已回传' }}</strong>
          <el-button size="small" link :loading="pageAssistantManifestLoading" @click="emit('refreshStatus')">
            刷新状态
          </el-button>
        </div>
        <div class="studio-ready-metrics compact">
          <div>
            <span>workflowId</span>
            <strong>{{ workflowAiCodingDraftEvidence.workflowId || '—' }}</strong>
          </div>
          <div>
            <span>keySlug</span>
            <strong>{{ workflowAiCodingDraftEvidence.keySlug || '—' }}</strong>
          </div>
          <div>
            <span>workflowName</span>
            <strong>{{ workflowAiCodingDraftEvidence.workflowName || '—' }}</strong>
          </div>
          <div>
            <span>validate</span>
            <strong>{{ workflowAiCodingValidationSummary || '—' }}</strong>
          </div>
          <div>
            <span>page-assistant validate</span>
            <strong>{{ workflowAiCodingPageAssistantValidationSummary || '—' }}</strong>
          </div>
          <div>
            <span>browser runtime</span>
            <strong>{{ workflowAiCodingRuntimeVerificationSummary || '—' }}</strong>
          </div>
        </div>
        <div class="workflow-ai-coding-result-actions">
          <el-button size="small" @click="emit('openStudio')">打开 Studio</el-button>
          <el-button
            size="small"
            type="danger"
            plain
            :loading="workflowAiCodingResetting"
            @click="emit('resetDraft')"
          >
            删除并重新生成
          </el-button>
          <el-button size="small" type="primary" @click="emit('useDraft')">使用该 Workflow 继续</el-button>
        </div>
      </div>
      <div v-else class="workflow-ai-coding-result-empty">
        <span>外部 AI 完成创建/validate 并回传后，这里会显示 workflowId 与校验摘要。</span>
        <el-button size="small" link :loading="pageAssistantManifestLoading" @click="emit('refreshStatus')">
          刷新回传状态
        </el-button>
      </div>
      <div class="ai-prompt-toolbar">
        <el-radio-group v-model="workflowAiCodingPromptTool" size="small">
          <el-radio-button label="Cursor" />
          <el-radio-button label="Codex" />
          <el-radio-button label="Claude Code" />
        </el-radio-group>
      </div>
      <el-input
        class="ai-prompt-editor"
        :model-value="workflowAiCodingPrompt"
        type="textarea"
        :rows="22"
        readonly
      />
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :icon="DocumentCopy" @click="emit('copyPrompt')">
        {{ workflowAiCodingPromptCopied ? '已复制' : '复制提示词' }}
      </el-button>
    </template>
  </el-dialog>
</template>

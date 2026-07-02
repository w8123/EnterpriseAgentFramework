<script setup lang="ts">
import { Connection } from '@element-plus/icons-vue'
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'
import type { AgentEntry } from '@/types/workflow'
import type { WizardStepKey } from '@/views/registry/pageAssistantWizardViewModel'

defineProps<{
  createdWorkflowId: string
  projectCode: string
  pageCopilotAgent: AgentEntry | null
  selectedPageKey: string
  selectedPage: PageRegistryView | null
  selectedActions: PageActionRegistryView[]
  isAiCodingWorkflowSelected: boolean
  bindingAgent: boolean
}>()

const emit = defineEmits<{
  focusStep: [key: WizardStepKey]
  bindToPageCopilot: []
}>()
</script>

<template>
  <div class="step-screen">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 6</span>
        <h2>挂载智能体</h2>
      </div>
    </div>

    <div v-if="createdWorkflowId" class="studio-ready">
      <p class="bind-intro">
        页面副驾驶 Agent 是业务系统统一 AI 按钮的入口。这里会把当前页面助手 Workflow 挂载到它，让嵌入式对话在该页面可调用这些动作。
      </p>

      <div class="studio-ready-metrics">
        <div>
          <span>Agent 名称</span>
          <strong>{{ pageCopilotAgent?.name || '页面副驾驶 Agent' }}</strong>
        </div>
        <div>
          <span>keySlug</span>
          <strong>{{ pageCopilotAgent?.keySlug || `${projectCode}-page-copilot` }}</strong>
        </div>
        <div>
          <span>agentKind</span>
          <strong>{{ pageCopilotAgent?.agentKind || 'PAGE_COPILOT' }}</strong>
        </div>
        <div>
          <span>状态</span>
          <strong>{{ pageCopilotAgent ? '已存在' : '将自动创建/复用' }}</strong>
        </div>
        <div>
          <span>bindingType</span>
          <strong>PAGE</strong>
        </div>
        <div>
          <span>pageKey</span>
          <strong>{{ selectedPageKey || '未选择' }}</strong>
        </div>
        <div>
          <span>routePattern</span>
          <strong>{{ selectedPage?.routePattern || '未设置' }}</strong>
        </div>
        <div>
          <span>actionKeys</span>
          <strong>{{ selectedActions.map((item) => item.actionKey).join('、') || '无' }}</strong>
        </div>
      </div>

      <div class="studio-ready-actions">
        <button
          type="button"
          class="secondary"
          @click="emit('focusStep', isAiCodingWorkflowSelected ? 'draft' : 'confirm')"
        >
          {{ isAiCodingWorkflowSelected ? '返回选择 Workflow' : '返回确认草稿' }}
        </button>
        <button type="button" class="primary" :disabled="bindingAgent" @click="emit('bindToPageCopilot')">
          <el-icon><Connection /></el-icon>
          {{ bindingAgent ? '挂载中...' : '挂载到页面副驾驶 Agent' }}
        </button>
      </div>
    </div>
    <div v-else class="studio-ready-empty">
      <strong>还没有创建 Workflow</strong>
      <span>请先在上一步确认并创建 PAGE_ASSISTANT Workflow。</span>
      <button type="button" @click="emit('focusStep', 'confirm')">去确认草稿</button>
    </div>
  </div>
</template>

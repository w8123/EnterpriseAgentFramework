<script setup lang="ts">
import { Connection } from '@element-plus/icons-vue'
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'
import type { Agent } from '@/types/workflow'
import type { WizardStepKey } from '@/views/registry/pageAssistantWizardViewModel'

defineProps<{
  createdWorkflowId: string
  projectCode: string
  pageCopilotAgent: Agent | null
  selectedPageKey: string
  selectedPage: PageRegistryView | null
  selectedActions: PageActionRegistryView[]
  isAiCodingWorkflowSelected: boolean
  attachingAgent: boolean
}>()

const emit = defineEmits<{
  focusStep: [key: WizardStepKey]
  attachToPageCopilot: []
}>()
</script>

<template>
  <div class="step-screen page-assistant-flow-panel">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 6</span>
        <h2>启用页面副驾驶</h2>
      </div>
    </div>

    <div v-if="createdWorkflowId" class="studio-ready">
      <p class="attach-intro">
        页面副驾驶 Agent 是业务系统统一 AI 按钮的入口。这里会把已发布 Workflow 加入 Supervisor 的 Workflow-as-Tool 白名单，并发布新版 Agent 配置。
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
          <span>状态</span>
          <strong>{{ pageCopilotAgent ? '已存在' : '将自动创建/复用' }}</strong>
        </div>
        <div>
          <span>工具目录</span>
          <strong>Workflow-as-Tool</strong>
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
        <button type="button" class="primary" :disabled="attachingAgent" @click="emit('attachToPageCopilot')">
          <el-icon><Connection /></el-icon>
          {{ attachingAgent ? '发布中...' : '加入工具目录并发布 Agent' }}
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

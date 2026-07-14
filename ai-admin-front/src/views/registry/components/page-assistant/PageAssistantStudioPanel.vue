<script setup lang="ts">
import { Connection, DataAnalysis, Finished, Setting } from '@element-plus/icons-vue'
import type { PageAssistantWorkflowAttachmentResult } from '@/types/workflow'
import type { WizardStepKey } from '@/views/registry/pageAssistantWizardViewModel'

defineProps<{
  attachmentResult: PageAssistantWorkflowAttachmentResult | null
}>()

const emit = defineEmits<{
  enterWorkflowStudio: []
  enterAgentWorkbench: []
  openRunOps: []
  focusStep: [key: WizardStepKey]
}>()
</script>

<template>
  <div class="step-screen page-assistant-flow-panel">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 7</span>
        <h2>进入 Workflow Studio</h2>
      </div>
    </div>

    <div v-if="attachmentResult" class="studio-ready">
      <div class="studio-ready-hero">
        <div class="studio-ready-icon">
          <el-icon><Finished /></el-icon>
        </div>
        <div class="studio-ready-copy">
          <span>Supervisor 已发布</span>
          <strong>页面助手 Workflow 已加入 Agent 工具目录</strong>
          <p>Workflow 与 Agent 配置均已发布，可进入 Workflow Studio 查看运行语义和版本。</p>
        </div>
        <div class="studio-ready-state">
          <em>Active</em>
        </div>
      </div>

      <div class="studio-ready-metrics">
        <div>
          <span>agentId</span>
          <strong>{{ attachmentResult.agentId }}</strong>
        </div>
        <div>
          <span>agentKeySlug</span>
          <strong>{{ attachmentResult.agentKeySlug }}</strong>
        </div>
        <div>
          <span>workflowId</span>
          <strong>{{ attachmentResult.workflowId }}</strong>
        </div>
        <div>
          <span>workflowKeySlug</span>
          <strong>{{ attachmentResult.workflowKeySlug }}</strong>
        </div>
        <div>
          <span>toolName</span>
          <strong>{{ attachmentResult.toolName }}</strong>
        </div>
        <div>
          <span>Agent 配置版本</span>
          <strong>v{{ attachmentResult.configVersionNo }} · {{ attachmentResult.configStatus }}</strong>
        </div>
      </div>

      <div class="studio-ready-actions">
        <button type="button" class="primary" @click="emit('enterWorkflowStudio')">
          <el-icon><Connection /></el-icon>
          进入 Workflow Studio
        </button>
        <button type="button" @click="emit('enterAgentWorkbench')">
          <el-icon><Setting /></el-icon>
          打开 Agent 工作台
        </button>
        <button type="button" @click="emit('openRunOps')">
          <el-icon><DataAnalysis /></el-icon>
          查看 RunOps
        </button>
      </div>
    </div>
    <div v-else class="studio-ready-empty">
      <strong>还没有发布 Supervisor 工具目录</strong>
      <span>请先在“启用页面副驾驶”步骤加入 Workflow 工具并发布 Agent 配置。</span>
      <button type="button" @click="emit('focusStep', 'attach')">去启用页面副驾驶</button>
    </div>
  </div>
</template>

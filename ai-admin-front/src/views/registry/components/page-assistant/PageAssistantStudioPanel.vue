<script setup lang="ts">
import { Connection, Finished } from '@element-plus/icons-vue'
import type { PageAssistantWorkflowBindingResult } from '@/types/workflow'
import type { WizardStepKey } from '@/views/registry/pageAssistantWizardViewModel'

defineProps<{
  bindingResult: PageAssistantWorkflowBindingResult | null
}>()

const emit = defineEmits<{
  enterWorkflowStudio: []
  focusStep: [key: WizardStepKey]
}>()
</script>

<template>
  <div class="step-screen">
    <div class="panel-head">
      <div>
        <span class="step-kicker">步骤 7</span>
        <h2>进入 Workflow Studio</h2>
      </div>
    </div>

    <div v-if="bindingResult" class="studio-ready">
      <div class="studio-ready-hero">
        <div class="studio-ready-icon">
          <el-icon><Finished /></el-icon>
        </div>
        <div class="studio-ready-copy">
          <span>挂载完成</span>
          <strong>页面助手 Workflow 已绑定到页面副驾驶 Agent</strong>
          <p>下一步进入 Workflow Studio，检查画布结构、参数映射和发布校验。</p>
        </div>
        <div class="studio-ready-state">
          <em>Bound</em>
        </div>
      </div>

      <div class="studio-ready-metrics">
        <div>
          <span>agentId</span>
          <strong>{{ bindingResult.agentId }}</strong>
        </div>
        <div>
          <span>agentKeySlug</span>
          <strong>{{ bindingResult.agentKeySlug }}</strong>
        </div>
        <div>
          <span>workflowId</span>
          <strong>{{ bindingResult.workflowId }}</strong>
        </div>
        <div>
          <span>workflowKeySlug</span>
          <strong>{{ bindingResult.workflowKeySlug }}</strong>
        </div>
        <div>
          <span>bindingId</span>
          <strong>{{ bindingResult.bindingId }}</strong>
        </div>
      </div>

      <div class="studio-ready-actions">
        <button type="button" class="primary" @click="emit('enterWorkflowStudio')">
          <el-icon><Connection /></el-icon>
          进入 Workflow Studio
        </button>
      </div>
    </div>
    <div v-else class="studio-ready-empty">
      <strong>还没有完成挂载</strong>
      <span>请先在“挂载智能体”步骤完成 Agent binding。</span>
      <button type="button" @click="emit('focusStep', 'bind')">去挂载智能体</button>
    </div>
  </div>
</template>

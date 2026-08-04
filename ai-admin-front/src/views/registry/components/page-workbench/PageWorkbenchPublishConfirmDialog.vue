<script setup lang="ts">
import { Promotion, Setting } from '@element-plus/icons-vue'
import { ref, watch } from 'vue'
import AppDialog from '@/components/common/AppDialog.vue'
import type {
  ProjectPage,
  WorkflowEngineeringDraftResult,
} from '@/types/pageWorkbench'
import {
  pageWorkbenchActionTitle,
  pageWorkbenchPageName,
  pageWorkbenchRiskLabel,
} from '@/utils/pageWorkbenchPresentation'

const props = defineProps<{
  modelValue: boolean
  page?: ProjectPage | null
  result?: WorkflowEngineeringDraftResult | null
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  studio: [result: WorkflowEngineeringDraftResult]
  confirm: [result: WorkflowEngineeringDraftResult]
}>()

const approved = ref(false)

watch(() => props.modelValue, (visible) => {
  if (visible) approved.value = false
})
</script>

<template>
  <AppDialog
    :model-value="modelValue"
    title="确认发布页面能力"
    width="min(840px, calc(100vw - 32px))"
    class="page-workbench-dialog publish-confirmation-dialog"
    append-to-body
    :close-on-click-modal="!busy"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <header class="workbench-modal-heading">
        <span>上线确认</span>
        <h2>确认 AI 生成的页面操作</h2>
        <p>确认操作范围、安全规则和精确版本后，才会正式发布并接入页面副驾驶。</p>
      </header>
    </template>

    <section v-if="page" class="publish-confirmation-body">
      <article class="publish-assistant-card">
        <div>
          <small>{{ pageWorkbenchPageName(page) }} · PAGE_ASSISTANT</small>
          <h3>{{ result?.workflow.name || '页面助手草稿' }}</h3>
          <p>{{ result?.summary || '正在读取当前工作流建设活动的已应用结果。' }}</p>
        </div>
        <el-tag :type="result?.validation.valid ? 'success' : 'warning'" effect="light">
          {{ result?.validation.valid ? '确定性校验通过' : '尚不能发布' }}
        </el-tag>
      </article>

      <div class="publish-action-review">
        <article v-for="action in page.actions" :key="action.id">
          <span><strong>{{ pageWorkbenchActionTitle(action) }}</strong><small>{{ action.actionKey }}</small></span>
          <span><el-tag effect="plain">{{ pageWorkbenchRiskLabel(action.riskLevel) }}</el-tag><small>{{ action.confirmRequired ? '执行前必须确认' : '无需额外确认' }}</small></span>
        </article>
        <el-empty v-if="!page.actions.length" :image-size="52" description="当前页面尚无有效页面操作" />
      </div>

      <el-checkbox v-model="approved" class="publish-approval-check">
        我已确认页面操作范围、权限与风险规则；发布目标来自当前已校验草稿
      </el-checkbox>

      <el-alert
        v-if="result && !result.validation.valid"
        title="当前草稿仍有确定性校验错误"
        description="请先在 Workflow Studio 修正 GraphSpec，再重新回到当前活动。"
        type="error"
        :closable="false"
        show-icon
      />
    </section>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">稍后确认</el-button>
      <el-button
        v-if="result"
        :icon="Setting"
        @click="emit('studio', result)"
      >
        在 Workflow Studio 核对
      </el-button>
      <el-button
        v-if="result"
        type="primary"
        :icon="Promotion"
        :loading="busy"
        :disabled="!approved || !result.validation.valid || !page?.actions.length"
        @click="emit('confirm', result)"
      >
        确认发布并接入
      </el-button>
    </template>
  </AppDialog>
</template>

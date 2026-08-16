<script setup lang="ts">
import { computed } from 'vue'
import { CopyDocument } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import type {
  PageIntegrationReadinessItem,
  ProjectPage,
  PublishedPageWorkflow,
} from '@/types/pageWorkbench'
import { pageWorkbenchPageName } from '@/utils/pageWorkbenchPresentation'

const props = defineProps<{
  modelValue: boolean
  page?: ProjectPage | null
  workflow?: PublishedPageWorkflow | null
  modelReadiness?: PageIntegrationReadinessItem | null
  preflightLoading?: boolean
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [page: ProjectPage, workflow: PublishedPageWorkflow]
  modelCenter: []
}>()

const modelPreflightBlocked = computed(() => (
  !props.preflightLoading
  && props.modelReadiness?.status !== 'PASS'
))
</script>

<template>
  <AppDialog
    :model-value="modelValue"
    title="验证一次真实业务结果"
    width="min(760px, calc(100vw - 32px))"
    class="page-workbench-dialog acceptance-preparation-dialog"
    append-to-body
    :close-on-click-modal="!busy"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <header class="workbench-modal-heading">
        <span>真实验收</span>
        <h2>验证一次真实业务结果</h2>
        <p>让 AI 在业务页面完成一次目标操作，最后由你判断可见结果是否正确。</p>
      </header>
    </template>

    <section v-if="page && workflow" class="acceptance-preparation-body">
      <dl class="acceptance-target-grid">
        <div><dt>验收页面</dt><dd>{{ pageWorkbenchPageName(page) }}</dd><small>{{ page.routePattern || page.pageKey }}</small></div>
        <div><dt>锁定工作流</dt><dd>{{ workflow.workflowName }}</dd><small>{{ workflow.workflowVersion }} · 版本 ID {{ workflow.workflowVersionId }}</small></div>
      </dl>

      <article class="acceptance-expectation">
        <h3>完成后需要核对</h3>
        <p><i>✓</i><span><strong>页面实际返回业务结果</strong><small>不是静态截图、模拟数组或仅有 AI 文本描述</small></span></p>
        <p><i>✓</i><span><strong>运行记录与版本一致</strong><small>验收任务会同时锁定页面、Workflow ID 和精确版本 ID</small></span></p>
      </article>

      <el-alert
        v-if="preflightLoading"
        title="正在检查 Agent 模型"
        description="正在确认当前发布版本绑定的模型是否启用并在最近 24 小时内测试通过。"
        type="info"
        :closable="false"
        show-icon
      />

      <el-alert
        v-else-if="modelPreflightBlocked"
        title="暂不能开始真实验收"
        :description="modelReadiness?.message || '尚未取得当前 Agent 模型的可用性检查结果。请先完成页面接入诊断。'"
        type="error"
        :closable="false"
        show-icon
      />

      <el-alert
        v-else
        title="Agent 模型预检通过"
        :description="modelReadiness?.message || '当前 Agent 模型已具备真实验收条件。'"
        type="success"
        :closable="false"
        show-icon
      />

      <el-alert
        title="AI 自检不能替代人工业务判断"
        description="任务回传并通过平台验证后，仍需要在任务详情中填写验收意见并明确通过或退回。"
        type="warning"
        :closable="false"
        show-icon
      />
    </section>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">稍后验收</el-button>
      <el-button
        v-if="modelPreflightBlocked"
        type="warning"
        @click="emit('modelCenter')"
      >
        去模型中心处理
      </el-button>
      <el-button
        v-if="page && workflow"
        type="primary"
        :icon="CopyDocument"
        :loading="busy"
        :disabled="preflightLoading || modelPreflightBlocked"
        @click="emit('confirm', page, workflow)"
      >
        创建并生成验收交接包
      </el-button>
    </template>
  </AppDialog>
</template>

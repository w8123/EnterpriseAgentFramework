<script setup lang="ts">
import { CopyDocument } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import type { ProjectPage, PublishedPageWorkflow } from '@/types/pageWorkbench'
import { pageWorkbenchPageName } from '@/utils/pageWorkbenchPresentation'

defineProps<{
  modelValue: boolean
  page?: ProjectPage | null
  workflow?: PublishedPageWorkflow | null
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [page: ProjectPage, workflow: PublishedPageWorkflow]
}>()
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
        v-if="page && workflow"
        type="primary"
        :icon="CopyDocument"
        :loading="busy"
        @click="emit('confirm', page, workflow)"
      >
        创建并生成验收交接包
      </el-button>
    </template>
  </AppDialog>
</template>

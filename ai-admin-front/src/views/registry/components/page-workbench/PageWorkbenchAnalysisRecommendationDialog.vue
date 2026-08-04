<script setup lang="ts">
import { CopyDocument, MagicStick } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import type { ProjectPage } from '@/types/pageWorkbench'
import { pageWorkbenchPageName } from '@/utils/pageWorkbenchPresentation'

defineProps<{
  modelValue: boolean
  page?: ProjectPage | null
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  create: [page: ProjectPage]
}>()
</script>

<template>
  <AppDialog
    :model-value="modelValue"
    title="让 AI Coding 补充推荐目标"
    width="min(860px, calc(100vw - 32px))"
    class="page-workbench-dialog analysis-recommendation-dialog"
    append-to-body
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <header class="workbench-modal-heading">
        <span>AI CODING 目标推荐</span>
        <h2>让 AI Coding 补充推荐目标</h2>
        <p>只读查看当前页面代码和调用关系；结果回到目标工作台，不会自动加入本次目标。</p>
      </header>
    </template>

    <section v-if="page" class="analysis-recommendation-body">
      <article class="analysis-recommendation-page">
        <div class="analysis-recommendation-page__preview" aria-hidden="true">
          <i /><i /><i />
          <span><i /><i /></span>
        </div>
        <div>
          <small>当前页面</small>
          <strong>{{ pageWorkbenchPageName(page) }}</strong>
          <code>{{ page.routePattern || page.pageKey }}</code>
        </div>
        <el-icon><MagicStick /></el-icon>
      </article>

      <div class="analysis-recommendation-rules">
        <article><b>1</b><span><strong>最多补充 3 个可选目标</strong><small>只返回当前页面能够实现的目标</small></span></article>
        <article><b>2</b><span><strong>基于真实代码关系</strong><small>结合 API、交互和权限判断</small></span></article>
        <article><b>3</b><span><strong>由你决定是否加入</strong><small>分析结果不会自动进入实施范围</small></span></article>
      </div>
    </section>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">暂不补充</el-button>
      <el-button
        v-if="page"
        type="primary"
        :icon="CopyDocument"
        :loading="busy"
        @click="emit('create', page)"
      >
        创建并生成分析交接包
      </el-button>
    </template>
  </AppDialog>
</template>

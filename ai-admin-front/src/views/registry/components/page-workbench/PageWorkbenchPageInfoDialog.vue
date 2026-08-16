<script setup lang="ts">
import { EditPen } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import type { ProjectPage } from '@/types/pageWorkbench'
import {
  pageWorkbenchModuleName,
  pageWorkbenchPageDescription,
  pageWorkbenchPageName,
  pageWorkbenchSourceLabel,
} from '@/utils/pageWorkbenchPresentation'

defineProps<{
  modelValue: boolean
  page?: ProjectPage | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  edit: [page: ProjectPage]
}>()

function formatDateTime(value?: string) {
  if (!value) return '尚未记录'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <AppDialog
    :model-value="modelValue"
    title="页面信息"
    width="min(900px, calc(100vw - 32px))"
    class="page-workbench-dialog page-information-dialog"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <header class="workbench-modal-heading">
        <span>PAGE INFORMATION</span>
        <h2>页面信息</h2>
        <p>统一核对页面定位与基本信息；页面操作契约在“页面操作”中维护。</p>
      </header>
    </template>

    <section v-if="page" class="page-information-body">
      <article class="page-information-identity">
        <header>
          <div>
            <small>{{ pageWorkbenchModuleName(page.moduleName, page.moduleKey) }}</small>
            <h3>{{ pageWorkbenchPageName(page) }}</h3>
            <p>{{ pageWorkbenchPageDescription(page) }}</p>
          </div>
          <el-tag effect="plain">{{ pageWorkbenchSourceLabel(page.sourceType) }}</el-tag>
        </header>
        <dl>
          <div><dt>页面键</dt><dd>{{ page.pageKey }}</dd></div>
          <div><dt>业务模块</dt><dd>{{ pageWorkbenchModuleName(page.moduleName, page.moduleKey) }}</dd></div>
          <div><dt>页面路由</dt><dd>{{ page.routePattern || '尚未发现' }}</dd></div>
          <div class="is-wide"><dt>业务页面地址</dt><dd>{{ page.businessPageUrl || '尚未配置' }}</dd></div>
          <div><dt>最近发现</dt><dd>{{ formatDateTime(page.lastDiscoveredAt) }}</dd></div>
          <div class="is-wide"><dt>入口组件</dt><dd>{{ page.componentPath || '尚未发现' }}</dd></div>
        </dl>
      </article>

    </section>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button
        v-if="page"
        type="primary"
        :icon="EditPen"
        @click="emit('edit', page)"
      >
        编辑页面信息
      </el-button>
    </template>
  </AppDialog>
</template>

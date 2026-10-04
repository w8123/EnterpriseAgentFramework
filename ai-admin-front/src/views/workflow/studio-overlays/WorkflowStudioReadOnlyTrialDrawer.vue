<template>
  <AppDrawer v-model="open" :title="`${isMethod ? '业务方法' : 'API'} 草稿只读真实试运行`" size="min(620px, 100vw)"
    :description="`将用本项目测试身份真实调用一个已声明只读的${isMethod ? '业务方法（SDK/Starter）' : ' API'}，并运行下游变量节点。每次点击发起一次请求；结果未确认时请先核对运行记录。`"
    :close-on-click-modal="!running" :close-on-press-escape="!running" :show-close="!running">
    <div class="trial-panel" :aria-busy="previewLoading || running">
      <dl class="trial-scope">
        <div><dt>{{ isMethod ? '所属项目' : '项目 / 环境' }}</dt><dd>{{ studio?.projectCode || '未绑定' }}{{ isMethod ? '' : ` / ${detail?.summary.environment || '读取中'}` }}</dd></div>
        <div><dt>{{ isMethod ? '将触达的方法' : '将触达的 API' }}</dt><dd>{{ isMethod ? methodDetail?.qualifiedName || target?.qualifiedName || '未确认' : detail ? `${detail.summary.httpMethod} ${detail.summary.routeTemplate}` : target?.qualifiedName || '未确认' }}</dd></div>
        <div v-if="isMethod"><dt>已接纳来源</dt><dd>{{ methodDetail?.sourceQualifiedName || '未确认' }} · {{ methodDetail?.sourceAvailability || (previewLoading ? '读取中' : '未确认') }}</dd></div>
        <div><dt>执行身份</dt><dd>项目测试身份 STUDIO_PROJECT_TEST；不携带业务用户或角色</dd></div>
        <div><dt>已保存修订</dt><dd>{{ studio?.revision || '未保存' }}</dd></div>
        <div><dt>只读声明</dt><dd>{{ (isMethod ? methodDetail?.sideEffect : detail?.acceptedContract?.sideEffect) || '未确认' }}</dd></div>
      </dl>
      <p v-if="reason" class="trial-notice" role="status">{{ reason }}</p>
      <el-form label-position="top" novalidate @submit.prevent="run" @keydown.enter="guardComposition">
          <el-form-item v-for="field in fields" :key="field.inputName" :label="field.name"
            :required="field.required" :for="`trial-input-${field.inputName}`"
            :error="invalidField === field.inputName ? error : ''">
            <el-input :id="`trial-input-${field.inputName}`" v-model="inputParams[field.inputName]"
              :disabled="running" :aria-invalid="invalidField === field.inputName"
              :aria-describedby="invalidField === field.inputName ? 'trial-error' : 'trial-input-help'"
              :placeholder="field.type === 'boolean' ? 'true 或 false' : `测试 ${field.name}`" clearable />
          </el-form-item>
        <p id="trial-input-help" class="trial-help">输入只用于这次试运行，调用目标和凭据由项目配置确定。</p>
        <p v-if="error" id="trial-error" class="trial-error" role="alert">{{ error }}</p>
        <p v-if="running" class="trial-notice" role="status">真实调用进行中，请等待结果。系统不会自动重发请求。</p>
      </el-form>
      <section v-if="result" class="trial-result" aria-label="只读试运行结果" aria-live="polite">
        <div class="trial-result-head">
          <strong>{{ result.success ? '只读试运行完成' : '试运行未完成' }}</strong>
          <span>{{ result.elapsedMs }} ms · {{ result.status }}</span>
        </div>
        <p class="trial-help">{{ result.assetType === 'BUSINESS_METHOD' ? `业务方法 ${result.qualifiedName}` : `API #${result.apiId}` }} · 草稿 {{ result.revision }}</p>
        <h3>{{ isMethod ? '业务方法真实返回' : 'API 返回字段' }}</h3>
        <pre>{{ JSON.stringify(isMethod ? result.methodOutput : result.apiOutput, null, 2) }}</pre>
        <h3>下游变量</h3>
        <pre>{{ JSON.stringify(result.variables, null, 2) }}</pre>
        <el-button v-if="result.traceId" @click="$emit('open-runops', result.traceId)">查看 Run / Trace</el-button>
        <p class="trial-trace-id">{{ result.traceId }}</p>
      </section>
    </div>
    <template #footer>
      <div class="trial-actions">
        <el-button :disabled="running" :loading="previewLoading" @click="refresh">刷新试运行条件</el-button>
        <el-button type="primary" :disabled="Boolean(reason)" :loading="running" @click="run">只读真实试运行</el-button>
      </div>
    </template>
  </AppDrawer>
</template>

<script setup lang="ts">
import { nextTick, toRef, watch } from 'vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import type { WorkflowWorkingCopyState } from '@/types/workflow'
import { useWorkflowStudioReadOnlyTrial } from '@/views/workflow/composables/useWorkflowStudioReadOnlyTrial'

const props = defineProps<{
  studio: WorkflowWorkingCopyState | null
  dirty: boolean
  authorized: boolean
  sessionScope: string
}>()
defineEmits<{ (event: 'open-runops', traceId: string): void }>()
const open = defineModel<boolean>('open', { required: true })
const { target, detail, methodDetail, isMethod, fields, reason, previewLoading, running, error,
  invalidField, result, inputParams, refresh, run } = useWorkflowStudioReadOnlyTrial({
  studio: toRef(props, 'studio'), open, dirty: toRef(props, 'dirty'),
  authorized: toRef(props, 'authorized'), sessionScope: toRef(props, 'sessionScope'),
})
watch(invalidField, async (name) => {
  if (!name) return
  await nextTick()
  document.getElementById(`trial-input-${name}`)?.focus()
})
function guardComposition(event: KeyboardEvent) {
  if (event.isComposing) event.preventDefault()
}
</script>

<style scoped lang="scss">
.trial-panel { display: grid; gap: var(--section-gap, 16px); min-width: 0; }
.trial-scope { display: grid; gap: 12px; margin: 0; padding: 16px; border: 1px solid var(--border-readable); border-radius: var(--radius-md); background: var(--surface-solid-panel); }
.trial-scope div { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 12px; }
.trial-scope dt, .trial-help, .trial-trace-id { color: var(--text-secondary); }
.trial-scope dd { margin: 0; overflow-wrap: anywhere; }
.trial-notice { margin: 0; padding: 12px; border-radius: var(--radius-sm); background: var(--status-info-soft); color: var(--text-primary); }
.trial-error { color: var(--status-danger); overflow-wrap: anywhere; }
.trial-help, .trial-trace-id { font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.trial-result { border-block-start: 1px solid var(--border-divider); padding-block-start: 16px; }
.trial-result-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; }
.trial-result-head span { color: var(--text-secondary); font-size: 12px; }
.trial-result h3 { font-size: 14px; margin: 16px 0 8px; }
.trial-result pre { margin: 0; padding: 12px; max-height: 240px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; border-radius: var(--radius-sm); background: var(--surface-solid-control); color: var(--text-primary); }
.trial-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.trial-actions :deep(.el-button + .el-button) { margin-left: 0; }
@media (max-width: 420px) { .trial-scope div { grid-template-columns: 1fr; gap: 4px; } }
</style>

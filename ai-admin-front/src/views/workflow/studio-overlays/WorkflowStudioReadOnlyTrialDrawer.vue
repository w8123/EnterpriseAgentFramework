<template>
  <AppDrawer v-model="open" title="只读试运行整个草稿" size="min(680px, 100vw)"
    description="用本项目测试身份真实运行已保存的整个草稿。只有全部业务方法 / API 都已声明只读、且没有写入或页面动作等副作用节点时才会执行；任一节点不满足即整体拒绝，不会发出任何调用。"
    :close-on-click-modal="!running" :close-on-press-escape="!running" :show-close="!running">
    <div class="trial-panel" :aria-busy="running">
      <dl class="trial-scope">
        <div><dt>所属项目</dt><dd>{{ studio?.projectCode || '未绑定' }}</dd></div>
        <div><dt>已保存修订</dt><dd>{{ studio?.revision || '未保存' }}</dd></div>
        <div><dt>执行身份</dt><dd>项目测试身份 STUDIO_PROJECT_TEST；不携带业务用户或角色</dd></div>
        <div>
          <dt>将真实调用</dt>
          <dd>
            <ul v-if="targets.length" class="trial-targets">
              <li v-for="item in targets" :key="item.nodeId">
                <span class="trial-tag">{{ item.assetType === 'BUSINESS_METHOD' ? '业务方法' : 'API' }}</span>
                {{ item.nodeName }} <code>{{ item.qualifiedName || '未选择' }}</code>
              </li>
            </ul>
            <span v-else>无业务方法或 API 节点</span>
          </dd>
        </div>
      </dl>
      <p v-if="reason" class="trial-notice" role="status">{{ reason }}</p>
      <ul v-if="blockedNodes.length && !reason" class="trial-blocked" aria-label="不能只读试运行的节点">
        <li v-for="node in blockedNodes" :key="node.nodeId + node.reason">
          <button type="button" class="trial-node-link" :disabled="!node.nodeId" @click="$emit('focus-node', node.nodeId)">
            「{{ node.nodeName || node.nodeId || '草稿' }}」</button>（{{ nodeTypeLabel(node.nodeType) }}）{{ node.reason }}
        </li>
      </ul>
      <el-form label-position="top" novalidate @submit.prevent="run" @keydown.enter="guardComposition">
        <el-form-item v-for="field in fields" :key="field.name" :label="field.label"
          :required="field.required" :for="`trial-input-${field.name}`"
          :error="invalidField === field.name ? error : ''">
          <el-input :id="`trial-input-${field.name}`" v-model="inputParams[field.name]"
            :disabled="running || field.type === 'unsupported'" :aria-invalid="invalidField === field.name"
            :aria-describedby="invalidField === field.name ? 'trial-error' : 'trial-input-help'"
            :placeholder="field.type === 'unsupported' ? '非标量输入，试运行中不可填写'
              : field.type === 'boolean' ? 'true 或 false' : field.description || `测试 ${field.label}`" clearable />
        </el-form-item>
        <el-form-item label="用户消息" for="trial-input-message">
          <el-input id="trial-input-message" v-model="message" type="textarea" :rows="2" :maxlength="4000"
            :disabled="running" placeholder="可选：模拟用户的一句话，供 LLM、意图识别和参数提取节点使用" />
        </el-form-item>
        <p id="trial-input-help" class="trial-help">输入只用于这次试运行；调用目标、连接和凭据由项目配置确定，结果写入 Run / Trace。</p>
        <p v-if="error" id="trial-error" class="trial-error" role="alert">{{ error }}</p>
        <p v-if="running" class="trial-notice" role="status">真实运行进行中（最长 120 秒），请等待结果。系统不会自动重发请求。</p>
      </el-form>
      <section v-if="result" class="trial-result" aria-label="只读试运行结果" aria-live="polite">
        <div class="trial-result-head">
          <strong>{{ result.success ? '整稿只读试运行完成' : '试运行未完成' }}</strong>
          <span>{{ result.elapsedMs }} ms · {{ statusLabel(result.status) }}</span>
        </div>
        <template v-if="result.success || result.answer">
          <h3>最终回答</h3>
          <pre class="trial-answer">{{ result.answer || '（无回答输出）' }}</pre>
        </template>
        <h3>节点执行结果</h3>
        <ol class="trial-steps">
          <li v-for="step in result.steps" :key="step.index + step.nodeId"
            :class="['trial-step', `is-${stepTone(step.status)}`, { 'is-failed': step.nodeId === failedNodeId }]">
            <div class="trial-step-head">
              <button type="button" class="trial-node-link" @click="$emit('focus-node', step.nodeId)">{{ step.nodeName || step.nodeId }}</button>
              <span class="trial-step-type">{{ nodeTypeLabel(step.nodeType) }}</span>
              <span class="trial-step-status">{{ statusLabel(step.status) }} · {{ step.elapsedMs }} ms</span>
            </div>
            <p v-if="step.errorMessage || step.errorCode" class="trial-error">{{ step.errorMessage || trialFailureHint(step.errorCode || '') }}<span v-if="step.errorCode">（{{ step.errorCode }}）</span></p>
            <details v-if="step.output != null">
              <summary>节点输出</summary>
              <pre>{{ formatOutput(step.output) }}</pre>
            </details>
          </li>
          <li v-if="!result.steps.length" class="trial-help">没有节点执行记录。</li>
        </ol>
        <el-button v-if="result.traceId" @click="$emit('open-runops', result.traceId)">查看 Run / Trace</el-button>
        <p v-if="result.traceId" class="trial-trace-id">{{ result.traceId }}</p>
      </section>
    </div>
    <template #footer>
      <div class="trial-actions">
        <el-button v-if="dirty" :disabled="running || saving" :loading="saving" @click="$emit('save')">保存草稿</el-button>
        <el-button type="primary" :disabled="Boolean(reason)" :loading="running" @click="run">只读试运行</el-button>
      </div>
    </template>
  </AppDrawer>
</template>

<script setup lang="ts">
import { nextTick, toRef, watch } from 'vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import type { WorkflowWorkingCopyState } from '@/types/workflow'
import {
  nodeTypeLabel,
  trialFailureHint,
  useWorkflowStudioReadOnlyTrial,
} from '@/views/workflow/composables/useWorkflowStudioReadOnlyTrial'

const props = defineProps<{
  studio: WorkflowWorkingCopyState | null
  dirty: boolean
  saving?: boolean
  authorized: boolean
  sessionScope: string
}>()
defineEmits<{
  (event: 'open-runops', traceId: string): void
  (event: 'focus-node', nodeId: string): void
  (event: 'save'): void
}>()
const open = defineModel<boolean>('open', { required: true })
const { targets, fields, reason, running, error, invalidField, result, inputParams, message,
  blockedNodes, failedNodeId, run } = useWorkflowStudioReadOnlyTrial({
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
const STATUS_LABELS: Record<string, string> = {
  SUCCESS: '成功', SUCCEEDED: '成功', COMPLETED: '成功', ERROR: '失败', FAILED: '失败', SKIPPED: '跳过',
  RESULT_STALE_AFTER_DISPATCH: '结果已过期', TIMEOUT: '超时',
}
function statusLabel(status: string) {
  return STATUS_LABELS[(status || '').toUpperCase()] || status || '未知'
}
function stepTone(status: string) {
  const value = (status || '').toUpperCase()
  if (['ERROR', 'FAILED', 'TIMEOUT'].includes(value)) return 'danger'
  if (value === 'SKIPPED') return 'muted'
  return 'success'
}
function formatOutput(output: unknown) {
  return typeof output === 'string' ? output : JSON.stringify(output, null, 2)
}
</script>

<style scoped lang="scss">
.trial-panel { display: grid; gap: var(--section-gap, 16px); min-width: 0; }
.trial-scope { display: grid; gap: 12px; margin: 0; padding: 16px; border: 1px solid var(--border-readable); border-radius: var(--radius-md); background: var(--surface-solid-panel); }
.trial-scope > div { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 12px; }
.trial-scope dt, .trial-help, .trial-trace-id, .trial-step-type, .trial-step-status { color: var(--text-secondary); }
.trial-scope dd { margin: 0; overflow-wrap: anywhere; }
.trial-targets, .trial-blocked, .trial-steps { display: grid; gap: 6px; margin: 0; padding: 0; list-style: none; }
.trial-targets code { color: var(--text-secondary); font-size: 12px; }
.trial-tag { display: inline-block; margin-inline-end: 4px; padding: 0 6px; border-radius: var(--radius-sm); background: var(--status-info-soft); color: var(--text-primary); font-size: 12px; }
.trial-blocked { padding: 12px; border-radius: var(--radius-sm); background: var(--status-danger-soft); color: var(--text-primary); }
.trial-notice { margin: 0; padding: 12px; border-radius: var(--radius-sm); background: var(--status-info-soft); color: var(--text-primary); overflow-wrap: anywhere; }
.trial-error { margin: 4px 0 0; color: var(--status-danger); overflow-wrap: anywhere; }
.trial-help, .trial-trace-id { font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.trial-node-link { padding: 0; border: 0; background: none; color: var(--text-primary); font: inherit; font-weight: 600; cursor: pointer; }
.trial-node-link:hover:not(:disabled), .trial-node-link:focus-visible { text-decoration: underline; }
.trial-node-link:disabled { cursor: default; }
.trial-result { border-block-start: 1px solid var(--border-divider); padding-block-start: 16px; }
.trial-result-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; }
.trial-result-head span { color: var(--text-secondary); font-size: 12px; }
.trial-result h3 { font-size: 14px; margin: 16px 0 8px; }
.trial-result pre { margin: 0; padding: 12px; max-height: 240px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; border-radius: var(--radius-sm); background: var(--surface-solid-control); color: var(--text-primary); }
.trial-steps { margin-block-end: 12px; }
.trial-step { padding: 8px 12px; border: 1px solid var(--border-readable); border-inline-start-width: 3px; border-radius: var(--radius-sm); }
.trial-step.is-success { border-inline-start-color: var(--status-success); }
.trial-step.is-danger, .trial-step.is-failed { border-inline-start-color: var(--status-danger); }
.trial-step.is-muted { border-inline-start-color: var(--border-divider); }
.trial-step-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; }
.trial-step-type, .trial-step-status { font-size: 12px; }
.trial-step-status { margin-inline-start: auto; }
.trial-step details { margin-top: 6px; }
.trial-step summary { cursor: pointer; color: var(--text-secondary); font-size: 12px; }
.trial-step pre { margin-top: 6px; }
.trial-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.trial-actions :deep(.el-button + .el-button) { margin-left: 0; }
@media (max-width: 420px) { .trial-scope > div { grid-template-columns: 1fr; gap: 4px; } }
</style>

<template>
  <section class="business-method-invocation" aria-labelledby="business-method-invocation-title" @keydown="onKeydown">
    <header class="invocation-heading">
      <div>
        <div class="invocation-eyebrow">业务方法 · 项目内试调用</div>
        <h3 id="business-method-invocation-title">试调用</h3>
        <p>使用项目接入凭据调用；不携带业务用户身份，业务系统仍会校验</p>
      </div>
      <el-button text size="small" :icon="Refresh" :loading="contextLoading" :disabled="!canInvoke || !active || isSubmitting || isQuerying" @click="loadContext">刷新调用条件</el-button>
    </header>

    <el-alert
      v-if="!canInvoke"
      title="当前账号不能试调用该业务方法"
      :description="permissionExplanation"
      type="info"
      :closable="false"
      show-icon
    />

    <div v-else class="invocation-authorized">
      <div v-if="contextLoading && !context" class="invocation-state" role="status">
        <el-icon class="is-loading"><Loading /></el-icon><span>正在读取项目内调用条件…</span>
      </div>

      <el-alert
        v-else-if="contextError"
        title="调用条件未准备好"
        :description="contextError"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #default>
          <p>{{ contextError }}</p>
          <el-button size="small" plain :loading="contextLoading" :disabled="isSubmitting || isQuerying" @click="loadContext">重新读取调用条件</el-button>
        </template>
      </el-alert>

      <template v-else-if="context">
      <section class="invocation-context">
        <div class="context-facts">
          <div><span>调用目标</span><strong>{{ context.targetDescription || context.projectCode || '项目内已确认目标' }}</strong></div>
          <div><span>项目</span><strong>{{ context.projectCode || '-' }}</strong></div>
          <div><span>超时上限</span><strong>{{ timeoutLabel }}</strong></div>
          <div><span>副作用</span><StatusTag :label="sideEffectLabel(context.sideEffect)" :tone="sideEffectTone(context.sideEffect)" /></div>
        </div>
        <p class="context-identity">使用项目接入凭据调用；不携带业务用户身份，业务系统仍会校验</p>
      </section>

      <el-alert
        v-if="!contextUsable"
        :title="context.businessIdentityRequired ? '该业务方法需要业务用户身份' : '该业务方法当前不可试调用'"
        :description="context.businessIdentityRequired
          ? '控制台不会伪造或代入业务用户身份，因此不会发起调用。'
          : (context.blockingMessage || '请先处理来源状态、接入凭据或已接受契约，再重新读取上下文。')"
        type="warning"
        :closable="false"
        show-icon
      />

      <form v-else class="invocation-form" novalidate @submit.prevent="requestInvocation">
        <BusinessMethodInvocationInputEditor
          :parameters="editorShape.parameters"
          :source-parameters="context.parameters"
          :single-dto-root-name="editorShape.singleDtoRootName"
          ref="inputEditor"
          :model-value="editorValue"
          :disabled="isSubmitting || isQuerying || requiresExplicitNewAttempt"
          @update:model-value="updateInput"
          @dirty-change="setDirty"
          @draft-change="onDraftChange"
        />

        <section v-if="confirmationVisible" class="invocation-confirmation" role="alertdialog" aria-labelledby="invocation-confirmation-title" aria-modal="false">
          <div>
            <h4 id="invocation-confirmation-title">确认写入试调用</h4>
            <p>将使用项目接入凭据调用 <code>{{ methodName }}</code>（{{ context.targetDescription || context.projectCode }}）。本次调用可能修改业务数据；不会携带业务用户身份。</p>
          </div>
          <div class="confirmation-actions">
            <el-button ref="confirmationCancel" native-type="button" @click="cancelConfirmation">取消</el-button>
            <el-button native-type="button" type="danger" :loading="isSubmitting" @click="confirmInvocation">确认并调用</el-button>
          </div>
        </section>

        <div class="invocation-actions">
          <el-button type="primary" native-type="submit" :loading="isSubmitting" :disabled="!canStartInvocation">
            开始试调用
          </el-button>
          <span class="invocation-action-hint">{{ requiresConfirmation ? '写入操作会在提交前再次确认。' : '按 Enter 或 Ctrl / Command + Enter 可提交。' }}</span>
        </div>
      </form>
      </template>
    </div>

    <el-alert
      v-if="newAttemptNotice"
      class="invocation-notice"
      :title="newAttemptNotice"
      type="warning"
      :closable="true"
      @close="newAttemptNotice = ''"
    />

    <section v-if="canInvoke && hasCurrentHandle" class="invocation-outcome" aria-live="polite">
      <header class="outcome-heading">
        <div>
          <template v-if="outcome">
            <StatusTag :label="statusPresentation.label" :tone="statusPresentation.tone" :pulse="statusPresentation.queryRecommended" />
            <h4>{{ statusPresentation.title }}</h4>
            <p>{{ statusPresentation.description }}</p>
          </template>
          <template v-else-if="clientState === 'QUERY_NOT_FOUND'">
            <StatusTag label="记录未找到" tone="warning" />
            <h4>尚未找到该调用记录</h4>
            <p>不会使用当前输入自动重新提交。请核对调用 ID，或稍后进行只读查询。</p>
          </template>
          <template v-else>
            <StatusTag label="结果尚未确认" tone="warning" pulse />
            <h4>本次调用结果尚未确认</h4>
            <p>本次可能已经执行。该调用 ID 已固定用于查询；请只读查询，不要重复提交。</p>
          </template>
        </div>
        <div class="outcome-actions">
          <el-button text size="small" :icon="CopyDocument" @click="copyValue(currentInvocationId, '调用 ID')">复制 ID</el-button>
          <el-button v-if="outcome?.runId !== null && outcome?.runId !== undefined" text size="small" :icon="CopyDocument" @click="copyValue(String(outcome.runId), '运行 ID')">复制 Run ID</el-button>
          <el-button v-if="outcome?.traceId" text size="small" :icon="CopyDocument" @click="copyValue(outcome.traceId, '追踪 ID')">复制 Trace ID</el-button>
          <el-button v-if="canQueryCurrent" size="small" :loading="isQuerying" @click="queryCurrent">查询当前调用</el-button>
          <el-button v-if="canStartNew" size="small" plain :disabled="isQuerying" @click="startNewAttempt">新建一次试调用</el-button>
        </div>
      </header>

      <el-alert v-if="actionError" class="outcome-error" :title="actionError" type="warning" :closable="false" show-icon />

      <dl class="outcome-facts">
        <div><dt>调用 ID</dt><dd><code>{{ currentInvocationId }}</code></dd></div>
        <div v-if="outcome?.runId !== null && outcome?.runId !== undefined"><dt>运行 ID</dt><dd><code>{{ outcome.runId }}</code></dd></div>
        <div v-if="outcome?.traceId"><dt>追踪 ID</dt><dd><code>{{ outcome.traceId }}</code></dd></div>
        <div v-if="outcome"><dt>分派状态</dt><dd>{{ outcome.dispatchStage }}</dd></div>
        <div v-if="outcome?.code"><dt>结果代码</dt><dd><code>{{ outcome.code }}</code></dd></div>
        <div v-if="outcome?.latencyMs !== null && outcome?.latencyMs !== undefined"><dt>耗时</dt><dd>{{ outcome.latencyMs }} ms</dd></div>
        <div v-if="outcome?.resultExpiresAtEpochMs !== null && outcome?.resultExpiresAtEpochMs !== undefined"><dt>结果有效期</dt><dd>{{ formatTime(outcome.resultExpiresAtEpochMs) }}</dd></div>
      </dl>

      <div v-if="outcome && canViewRunOps && outcome.traceId" class="outcome-runops">
        <el-button text type="primary" :icon="ArrowRight" @click="openTrace">查看 RunOps 追踪</el-button>
        <code>{{ outcome.traceId }}</code>
      </div>

      <el-alert v-if="outcome?.resultTruncated" title="返回结果已截断" description="页面仅显示平台返回的安全截断结果；请结合调用追踪判断完整执行情况。" type="warning" :closable="false" show-icon />
      <el-alert v-if="outcome && isExpiredResult(outcome)" title="返回结果已过期" description="该调用记录仍可用于审计，但结果正文已按保留策略移除。" type="info" :closable="false" show-icon />

      <section v-if="diagnostics.length" class="input-diagnostics" aria-labelledby="input-diagnostics-title">
        <h5 id="input-diagnostics-title">输入校验提示</h5>
        <ul><li v-for="item in diagnostics" :key="`${item.path}:${item.reason}`"><code>{{ item.path }}</code><span>{{ diagnosticReasonLabel(item.reason) }}</span></li></ul>
      </section>

      <section v-if="outcome && outcome.result !== undefined && outcome.result !== null && !isExpiredResult(outcome)" class="outcome-result" aria-labelledby="outcome-result-title">
        <header><h5 id="outcome-result-title">安全返回结果</h5><el-button text size="small" :icon="CopyDocument" @click="copyValue(safeJson(outcome.result), '安全返回结果')">复制结果</el-button></header>
        <pre>{{ safeJson(outcome.result) }}</pre>
      </section>
    </section>

    <section v-if="canInvoke && active && recentReferences.length" class="recent-invocations" aria-labelledby="recent-invocations-title">
      <header><h4 id="recent-invocations-title">最近试调用</h4><p>仅保存当前账号、项目与业务方法范围内的调用 ID 和时间；不保存输入、结果或凭据。</p></header>
      <ul>
        <li v-for="reference in recentReferences" :key="reference.invocationId">
          <div><code>{{ shortId(reference.invocationId) }}</code><span>{{ formatTime(reference.createdAt) }}</span></div>
          <el-button text size="small" :loading="isQuerying && currentInvocationId === reference.invocationId" :disabled="isSubmitting || isQuerying" @click="queryHistoricReference(reference.invocationId)">查询</el-button>
        </li>
      </ul>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, CopyDocument, Loading, Refresh } from '@element-plus/icons-vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { platformSessionUser } from '@/auth/platformSession'
import {
  PLATFORM_PERMISSION_CAPABILITY_INVOKE,
  PLATFORM_PERMISSION_READ,
  PLATFORM_PERMISSION_RUNOPS_READ,
  hasPlatformResourcePermission,
} from '@/auth/platformAccess'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'
import {
  contextIsExecutable,
  diagnosticReasonLabel,
  invocationEditorShape,
  invocationStatusPresentation,
  isExpiredResult,
  requiresSideEffectConfirmation,
  resultInputDiagnostics,
  safeJson,
  sideEffectLabel,
  sideEffectTone,
} from '../businessMethodInvocation'
import { useBusinessMethodInvocation } from '../composables/useBusinessMethodInvocation'
import BusinessMethodInvocationInputEditor from './BusinessMethodInvocationInputEditor.vue'

type InvocationDraftState = { valid: boolean; revision: number; mode: 'form' | 'json' }
type InvocationEditorExposed = { validate: () => InvocationDraftState }

const props = withDefaults(defineProps<{
  methodName: string
  projectCode?: string | null
  contextKey: string
  active?: boolean
}>(), {
  projectCode: null,
  active: false,
})
const emit = defineEmits<{ 'dirty-change': [value: boolean] }>()
const router = useRouter()
const dirty = ref(false)
const confirmationVisible = ref(false)
const confirmationCancel = ref<{ $el?: HTMLElement } | null>(null)
const newAttemptNotice = ref('')
const inputEditor = ref<InvocationEditorExposed | null>(null)
const draftState = ref<InvocationDraftState>({ valid: false, revision: 0, mode: 'form' })
const confirmationRevision = ref(-1)

const canReadProject = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_READ,
  'PROJECT',
  null,
  props.projectCode,
))
const canInvoke = computed(() => Boolean(props.projectCode) && canReadProject.value && hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_CAPABILITY_INVOKE,
  'PROJECT',
  null,
  props.projectCode,
))
const canViewRunOps = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_RUNOPS_READ,
  'PROJECT',
  null,
  props.projectCode,
))
const permissionExplanation = computed(() => {
  if (!props.projectCode) return '来源项目尚未确认，页面不会读取调用条件或发起试调用。'
  if (!canReadProject.value) return '当前账号缺少该项目的业务方法读取权限，页面不会读取调用条件或发起试调用。'
  return '当前账号缺少该项目的业务方法试调用权限，页面不会读取调用条件或发起试调用。'
})

const {
  context,
  contextLoading,
  contextError,
  actionError,
  input,
  outcome,
  currentInvocationId,
  clientState,
  recentReferences,
  requiresExplicitNewAttempt,
  isSubmitting,
  isQuerying,
  loadContext,
  beginNewAttempt,
  submit,
  query,
  queryReference,
} = useBusinessMethodInvocation({
  methodName: () => props.methodName,
  contextKey: () => props.contextKey,
  referenceContextKey: () => props.projectCode ? `project:${props.projectCode}` : '',
  actorId: () => platformSessionUser.value?.userId,
  active: () => Boolean(props.active),
  authorized: () => canInvoke.value,
})

const editorShape = computed(() => invocationEditorShape(context.value?.parameters || []))
const editorValue = computed(() => input.value)
const contextUsable = computed(() => contextIsExecutable(context.value))
const requiresConfirmation = computed(() => requiresSideEffectConfirmation(context.value?.sideEffect))
const statusPresentation = computed(() => outcome.value
  ? invocationStatusPresentation(outcome.value)
  : { label: '结果尚未确认', tone: 'warning' as const, title: '本次调用结果尚未确认', description: '请查询同一调用 ID。', queryRecommended: true })
const diagnostics = computed(() => resultInputDiagnostics(outcome.value?.result))
const hasCurrentHandle = computed(() => Boolean(currentInvocationId.value))
const canQueryCurrent = computed(() => Boolean(currentInvocationId.value) && canInvoke.value && Boolean(props.active) && !isSubmitting.value && !isQuerying.value)
const canStartNew = computed(() => requiresExplicitNewAttempt.value && canInvoke.value && Boolean(props.active) && !isSubmitting.value && !isQuerying.value)
const canStartInvocation = computed(() => canInvoke.value
  && Boolean(props.active)
  && contextUsable.value
  && draftState.value.valid
  && !requiresExplicitNewAttempt.value
  && !isSubmitting.value
  && !isQuerying.value
  && !confirmationVisible.value)
const timeoutLabel = computed(() => context.value?.timeoutMs && context.value.timeoutMs > 0 ? `${Math.ceil(context.value.timeoutMs / 1000)} 秒` : '30 秒')

function setDirty(value: boolean) {
  dirty.value = value
  emit('dirty-change', value)
}

function updateInput(value: Record<string, unknown>) {
  input.value = value
}

function onDraftChange(state: InvocationDraftState) {
  draftState.value = state
  // Every visible edit, including an invalid one, invalidates a write confirmation.
  if (confirmationVisible.value) {
    confirmationVisible.value = false
    confirmationRevision.value = -1
  }
}

function validatedSnapshot() {
  const state = inputEditor.value?.validate()
  if (!state?.valid) {
    draftState.value = state || { valid: false, revision: draftState.value.revision, mode: draftState.value.mode }
    return null
  }
  draftState.value = state
  return { input: input.value, revision: state.revision }
}

function requestInvocation() {
  if (!canInvoke.value || !props.active || !contextUsable.value || isSubmitting.value || isQuerying.value || requiresExplicitNewAttempt.value) return
  const snapshot = validatedSnapshot()
  if (!snapshot) return
  if (requiresConfirmation.value) {
    confirmationRevision.value = snapshot.revision
    confirmationVisible.value = true
    nextTick(() => {
      confirmationCancel.value?.$el?.focus?.()
    })
    return
  }
  void submitInvocation(false, snapshot.input)
}

function cancelConfirmation() {
  confirmationVisible.value = false
  confirmationRevision.value = -1
}

function confirmInvocation() {
  const snapshot = validatedSnapshot()
  if (!snapshot || snapshot.revision !== confirmationRevision.value) {
    confirmationVisible.value = false
    confirmationRevision.value = -1
    return
  }
  confirmationVisible.value = false
  confirmationRevision.value = -1
  void submitInvocation(true, snapshot.input)
}

/** Once an invocation ID exists, the input has been explicitly submitted. */
async function submitInvocation(confirmedSideEffect: boolean, inputSnapshot: Record<string, unknown>) {
  await submit(confirmedSideEffect, inputSnapshot)
  if (currentInvocationId.value) setDirty(false)
}

function onKeydown(event: KeyboardEvent) {
  if (event.isComposing || event.key !== 'Enter' || isSubmitting.value || isQuerying.value || confirmationVisible.value || requiresExplicitNewAttempt.value) return
  const target = event.target as HTMLElement | null
  if (target?.tagName === 'BUTTON') return
  const inMultilineEditor = target?.tagName === 'TEXTAREA'
  if (inMultilineEditor && !(event.ctrlKey || event.metaKey)) return
  event.preventDefault()
  requestInvocation()
}

async function queryCurrent() {
  await query(currentInvocationId.value)
}

async function queryHistoricReference(invocationId: string) {
  await queryReference(invocationId)
}

function startNewAttempt() {
  const wasUnknown = clientState.value === 'OUTCOME_UNCONFIRMED'
    || clientState.value === 'QUERY_NOT_FOUND'
    || outcome.value?.status === 'UNKNOWN'
    || outcome.value?.status === 'ACCEPTED'
    || outcome.value?.status === 'DISPATCHING'
  if (!beginNewAttempt()) return
  setDirty(Object.keys(input.value).length > 0)
  newAttemptNotice.value = wasUnknown
    ? '先前调用的结果尚未确认，业务系统可能已经执行；新的写入试调用仍需单独确认。'
    : '已新建一次试调用；只有明确提交后才会生成新的调用 ID。'
}

async function copyValue(value: string, label: string) {
  const previousFocus = document.activeElement as HTMLElement | null
  const copied = await copyAiCodingText(value)
  if (previousFocus?.isConnected) previousFocus.focus()
  if (copied.copied) ElMessage.success(`${label}已复制`)
  else ElMessage.warning('复制未成功，请手动选择内容。')
}

function openTrace() {
  if (!canViewRunOps.value || !outcome.value?.traceId) return
  void router.push({ name: 'RunOpsDetail', params: { traceId: outcome.value.traceId } })
}

function shortId(value: string) {
  return value.length > 18 ? `${value.slice(0, 12)}…${value.slice(-5)}` : value
}

function formatTime(value: number) {
  try {
    return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(value)
  } catch {
    return new Date(value).toLocaleString()
  }
}

watch(
  [() => props.methodName, () => props.contextKey, () => platformSessionUser.value?.userId],
  () => {
    confirmationVisible.value = false
    confirmationRevision.value = -1
    newAttemptNotice.value = ''
    setDirty(false)
  },
  { flush: 'sync' },
)

watch(context, () => {
  confirmationVisible.value = false
  confirmationRevision.value = -1
})

watch(canInvoke, (allowed) => {
  if (allowed) return
  confirmationVisible.value = false
  confirmationRevision.value = -1
  newAttemptNotice.value = ''
  setDirty(false)
}, { flush: 'sync' })
</script>

<style scoped lang="scss">
.business-method-invocation { padding: 20px 0 4px; }
.invocation-heading, .outcome-heading, .recent-invocations > header, .outcome-result > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.invocation-eyebrow { color: var(--text-secondary); font-size: 12px; }
.invocation-heading h3 { margin: 7px 0 0; color: var(--text-primary); font-size: 15px; }
.invocation-heading p, .context-identity, .outcome-heading p, .recent-invocations header p { margin: 6px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.75; }
.invocation-state { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 140px; color: var(--text-secondary); font-size: 13px; }
.invocation-state .el-icon { color: var(--brand-primary); font-size: 20px; }
.invocation-context { margin-top: 16px; padding: 14px 16px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-solid-control); }
.context-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.context-facts > div { display: grid; gap: 5px; min-width: 0; }
.context-facts span { color: var(--text-secondary); font-size: 11px; }
.context-facts strong { color: var(--text-primary); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.context-identity { margin-top: 12px; }
.invocation-context + .el-alert { margin-top: 14px; }
.invocation-confirmation { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin: 2px 0 16px; padding: 15px 16px; border: 1px solid var(--status-danger); border-radius: var(--radius-md); background: var(--status-danger-soft); }
.invocation-confirmation > div { min-width: 0; max-width: 100%; }
.invocation-confirmation h4 { margin: 0; color: var(--text-primary); font-size: 13px; }
.invocation-confirmation p { margin: 6px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.75; overflow-wrap: anywhere; }
.confirmation-actions, .invocation-actions, .outcome-actions, .outcome-runops { display: flex; align-items: center; gap: 8px; }
.confirmation-actions { flex-shrink: 0; }
.invocation-actions { flex-wrap: wrap; margin-top: 16px; }
.invocation-action-hint { color: var(--text-secondary); font-size: 12px; }
.invocation-notice { margin-top: 16px; }
.invocation-outcome { margin-top: 18px; padding: 16px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-solid-page); }
.outcome-heading h4, .recent-invocations h4, .outcome-result h5, .input-diagnostics h5 { margin: 8px 0 0; color: var(--text-primary); font-size: 13px; }
.outcome-actions { flex-wrap: wrap; justify-content: flex-end; }
.outcome-error { margin-top: 14px; }
.outcome-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 18px; margin: 16px 0 0; }
.outcome-facts > div { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 8px; font-size: 12px; }
.outcome-facts dt { color: var(--text-secondary); }
.outcome-facts dd { margin: 0; min-width: 0; color: var(--text-primary); overflow-wrap: anywhere; }
.outcome-facts code, .outcome-runops code, .recent-invocations code, .input-diagnostics code { padding: 0; background: transparent; font: 12px/1.6 ui-monospace, Consolas, monospace; overflow-wrap: anywhere; }
.outcome-runops { margin-top: 14px; }
.outcome-runops code { color: var(--text-secondary); }
.invocation-outcome > .el-alert { margin-top: 14px; }
.input-diagnostics { margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--border-divider); }
.input-diagnostics h5 { margin-top: 0; }
.input-diagnostics ul { display: grid; gap: 7px; padding: 0; margin: 10px 0 0; list-style: none; }
.input-diagnostics li { display: flex; gap: 10px; align-items: baseline; color: var(--text-secondary); font-size: 12px; }
.input-diagnostics code { color: var(--text-primary); }
.outcome-result { margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--border-divider); }
.outcome-result h5 { margin: 0; }
.outcome-result pre { max-height: 280px; margin: 10px 0 0; padding: 12px; overflow: auto; border: 1px solid var(--border-divider); border-radius: var(--radius-sm); background: var(--surface-solid-control); color: var(--text-primary); font: 12px/1.7 ui-monospace, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.recent-invocations { margin-top: 20px; padding-top: 18px; border-top: 1px solid var(--border-divider); }
.recent-invocations h4 { margin: 0; }
.recent-invocations ul { margin: 12px 0 0; padding: 0; list-style: none; }
.recent-invocations li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border-divider); }
.recent-invocations li > div { display: grid; gap: 3px; min-width: 0; }
.recent-invocations li span { color: var(--text-secondary); font-size: 11px; }
@media (max-width: 720px) {
  .context-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .invocation-heading, .outcome-heading, .recent-invocations > header, .invocation-confirmation { flex-direction: column; }
  .outcome-actions { justify-content: flex-start; }
  .confirmation-actions { align-self: stretch; }
  .confirmation-actions > .el-button { flex: 1; }
}
@media (max-width: 440px) {
  .context-facts, .outcome-facts { grid-template-columns: 1fr; }
}
</style>

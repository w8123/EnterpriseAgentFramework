<template>
  <AppDialog
    :model-value="modelValue"
    :title="`${assetLabel}详情`"
    width="1120px"
    class="capability-inspector"
    align-center
    append-to-body
    destroy-on-close
    :before-close="handleDetailBeforeClose"
    @update:model-value="requestDialogModelValue"
  >
    <template #header="{ titleId }">
      <div class="inspector-heading">
        <div class="inspector-eyebrow">{{ catalogLabel }} <span>/</span> {{ tool?.sourceProjectName || tool?.projectCode || '平台级能力' }}</div>
        <h2 :id="titleId">{{ capabilityDisplayName(tool) }}</h2>
        <div v-if="tool" class="inspector-identifier">
          <code>{{ capabilityStableName(tool) }}</code>
          <el-tooltip :content="`复制${assetLabel}标识`" placement="top"><el-button text :icon="CopyDocument" :aria-label="`复制${assetLabel}标识`" @click="copyText(capabilityStableName(tool), `${assetLabel}标识`)" /></el-tooltip>
        </div>
      </div>
    </template>

    <div v-if="loading" class="inspector-state" role="status"><el-icon class="is-loading"><Loading /></el-icon><p>正在读取最新定义…</p></div>
    <div v-else-if="loadError" class="inspector-state" role="alert"><el-icon><Warning /></el-icon><h3>{{ assetLabel }}详情未能加载</h3><p>{{ loadError }}</p><el-button :icon="Refresh" @click="loadDetail">重新加载</el-button></div>
    <div v-else-if="tool" class="inspector-workspace">
      <main class="inspector-main">
        <section class="inspector-description">
          <div class="description-label">{{ assetLabel }}说明</div>
          <p>{{ capabilityDescription(tool) }}</p>
          <el-alert v-if="capabilityDescriptionHasEncodingIssue(tool)" title="来源说明存在编码异常，请在来源项目修复后重新同步。" type="warning" :closable="false" show-icon />
        </section>

        <el-tabs v-model="tab" class="inspector-tabs">
          <el-tab-pane label="输入与返回" name="contract">
            <section class="inspector-section">
              <header class="section-heading"><h3>输入参数 <span>{{ capabilityParameterCount(parameters.inputs) }}</span></h3><el-button v-if="parameters.inputs.length" text :icon="CopyDocument" @click="copyText(capabilityInputContract(tool), '输入契约')">复制输入契约</el-button></header>
              <CapabilityParameterTable v-if="parameters.inputs.length" :parameters="parameters.inputs" />
              <div v-else class="inspector-empty-inline"><el-icon><CircleCheck /></el-icon>无需输入参数</div>
            </section>
            <section class="inspector-section">
              <header class="section-heading"><h3>返回结果 <span v-if="parameters.outputs.length">{{ capabilityParameterCount(parameters.outputs) }}</span></h3></header>
              <CapabilityParameterTable v-if="parameters.outputs.length" :parameters="parameters.outputs" output />
              <div v-else class="response-definition"><code>{{ tool.responseType || '未声明返回类型' }}</code><p>来源尚未提供返回字段说明。</p></div>
            </section>
          </el-tab-pane>

          <el-tab-pane v-if="catalogKind === 'business-method'" label="试调用" name="invocation">
            <BusinessMethodInvocationPanel
              v-if="tool"
              :method-name="tool.name"
              :project-code="tool.projectCode"
              :context-key="contextKey"
              :active="tab === 'invocation'"
              @dirty-change="invocationDirty = $event"
            />
          </el-tab-pane>

          <el-tab-pane name="references">
            <template #label>使用位置 <span v-if="references?.references.length" class="tab-count">{{ references.references.length }}</span></template>
            <section class="inspector-section">
              <header class="section-heading"><h3>谁在使用这项能力</h3><el-button v-if="tool.projectCode" text :icon="Refresh" :loading="referencesLoading" @click="loadReferences">刷新</el-button></header>
              <p class="section-hint">包含流程草稿、发布版本及开放发布中的引用。</p>
              <div v-if="referencesLoading" class="reference-state" role="status">正在查询使用位置…</div>
              <div v-else-if="referencesError" class="reference-state" role="alert"><p>{{ referencesError }}</p><el-button v-if="tool.projectCode" @click="loadReferences">重试查询</el-button></div>
              <template v-else-if="references">
                <el-alert v-if="!referencesComplete" title="部分使用位置尚未确认" description="下方仅展示已查到的引用，不能据此判断没有其他使用方。" type="warning" :closable="false" show-icon />
                <ul v-if="references.references.length" class="usage-list">
                  <li v-for="(usage, index) in references.references" :key="index">
                    <div class="usage-icon" aria-hidden="true"><el-icon><component :is="usage.kind === 'AGENT' ? User : usage.kind === 'WORKFLOW' ? Connection : Share" /></el-icon></div>
                    <div class="usage-copy"><strong>{{ usage.name || usage.id }}</strong><span>{{ referenceKindLabel(usage.kind) }} · {{ referenceStage(usage.stage) }}{{ usage.version ? ` · ${usage.kind === 'AGENT' ? '引用流程 ' : ''}${usage.version}` : '' }}</span><small v-if="usage.nodeId">节点 {{ usage.nodeId }}</small></div>
                    <el-button v-if="usageRoute(usage)" text :icon="ArrowRight" :aria-label="`查看 ${usage.name || usage.id}`" @click="navigate(usageRoute(usage)!)">查看</el-button>
                  </li>
                </ul>
                <div v-else class="reference-state"><el-icon><Connection /></el-icon><h3>{{ referencesComplete ? '还没有被引用' : '暂未查到已确认的引用' }}</h3><p>{{ referencesComplete ? '后续添加到流程或开放发布后，会在这里显示具体使用位置。' : '稍后刷新，确认其余服务的查询结果。' }}</p></div>
              </template>
            </section>
          </el-tab-pane>

          <el-tab-pane label="技术信息" name="technical">
            <section class="inspector-section">
              <header class="section-heading"><h3>{{ technicalEndpointLabel }}</h3><el-button v-if="capabilityEndpoint(tool) !== '-'" text :icon="CopyDocument" @click="copyText(capabilityEndpoint(tool), technicalEndpointLabel)">复制地址</el-button></header>
              <div class="endpoint-definition"><span v-if="tool.httpMethod">{{ tool.httpMethod.toUpperCase() }}</span><code>{{ capabilityEndpoint(tool) }}</code></div>
              <p class="section-hint">业务服务的执行端点；调用所需凭据由运行时管理。</p>
            </section>
            <section class="inspector-section"><header class="section-heading"><h3>声明信息</h3></header><dl class="technical-facts"><div v-for="fact in technicalFacts" :key="fact.label"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd></div></dl></section>
          </el-tab-pane>
        </el-tabs>
      </main>

      <aside class="inspector-aside" aria-label="调用条件与来源">
        <section class="readiness-panel" :class="`is-${readiness.tone}`">
          <div class="aside-label">调用状态 <el-button text :icon="Refresh" :aria-label="`重新读取${assetLabel}状态`" @click="loadDetail" /></div>
          <div class="readiness-title"><el-icon><component :is="readiness.tone === 'success' ? CircleCheck : Warning" /></el-icon><strong>{{ readiness.title }}</strong></div>
          <p>{{ readiness.description }}</p>
          <el-button v-if="tool.projectCode && readiness.tone !== 'success' && tool.enabled" text type="primary" :icon="ArrowRight" @click="openChanges">检查当前{{ assetLabel }}变化</el-button>
        </section>
        <section class="aside-section">
          <h3>调用须知</h3>
          <StatusTag :label="capabilitySideEffectLabel(tool.sideEffect)" :tone="capabilitySideEffectTone(tool.sideEffect)" />
          <p>{{ effectDescription }}</p>
          <dl class="execution-facts"><div v-for="fact in executionFacts" :key="fact.label"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd></div></dl>
        </section>
        <section class="aside-section source-section">
          <h3>来源项目</h3><strong>{{ tool.sourceProjectName || tool.projectCode || '平台级能力' }}</strong>
          <p>{{ capabilitySourceLabel(tool.source, tool.sourceLocation) }}<template v-if="metadata.domain"> · {{ metadata.domain }}</template></p>
          <el-button v-if="tool.projectCode" :icon="FolderOpened" @click="navigate({ name: 'RegistryProjectDetail', params: { projectCode: tool.projectCode } })">查看来源项目</el-button>
        </section>
      </aside>
    </div>
  </AppDialog>

  <AppDialog
    :model-value="discardConfirmation"
    title="放弃未提交的输入？"
    width="420px"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="closeDiscardConfirmation"
  >
    <p class="discard-copy">当前试调用输入尚未提交。放弃后会从页面内存中清除；最近调用记录只保留调用 ID 和时间。</p>
    <template #footer>
      <el-button ref="discardCancel" native-type="button" @click="cancelDiscard">继续编辑</el-button>
      <el-button native-type="button" type="danger" @click="confirmDiscard">放弃输入</el-button>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, CircleCheck, Connection, CopyDocument, FolderOpened, Loading, Refresh, Share, User, Warning } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { getBusinessMethod, getTool, getCapabilityReferences, type CapabilityReferences } from '@/api/tool'
import type { ToolInfo } from '@/types/tool'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'
import { capabilityDescription, capabilityDescriptionHasEncodingIssue, capabilityDisplayName, capabilityEndpoint, capabilityParameterCount, capabilitySideEffectLabel, capabilitySideEffectTone, capabilitySourceLabel, capabilityStableName, parseCapabilityMetadata } from '../capabilityGovernance'
import { capabilityExecutionFacts, capabilityInputContract, capabilityReadiness, referenceStage, splitCapabilityParameters } from '../capabilityDetail'
import { referenceKindLabel, usageRoute } from '../referenceUsage'
import CapabilityParameterTable from './CapabilityParameterTable.vue'
import BusinessMethodInvocationPanel from './BusinessMethodInvocationPanel.vue'

const props = withDefaults(defineProps<{
  modelValue: boolean
  capability: ToolInfo | null
  catalogKind?: 'capability' | 'business-method'
  contextKey?: string
}>(), {
  catalogKind: 'capability',
  contextKey: '',
})
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; refreshed: [tool: ToolInfo] }>()
const router = useRouter()
const tool = ref<ToolInfo | null>(null)
const loading = ref(false)
const loadError = ref('')
const tab = ref('contract')
const references = ref<CapabilityReferences | null>(null)
const referencesLoading = ref(false)
const referencesError = ref('')
const invocationDirty = ref(false)
const discardConfirmation = ref(false)
const discardCancel = ref<{ $el?: HTMLElement } | null>(null)
const pendingNavigation = ref<RouteLocationRaw | null>(null)
let sequence = 0
let referenceSequence = 0
const assetLabel = computed(() => props.catalogKind === 'business-method' ? '业务方法' : '能力')
const catalogLabel = computed(() => props.catalogKind === 'business-method' ? '业务方法目录' : '能力目录')
const technicalEndpointLabel = computed(() => props.catalogKind === 'business-method' ? 'HTTP 传输入口' : '调用地址')
const parameters = computed(() => splitCapabilityParameters(tool.value?.parameters))
const readiness = computed(() => capabilityReadiness(tool.value!))
const metadata = computed(() => parseCapabilityMetadata(tool.value?.capabilityMetadataJson) || {})
const referencesComplete = computed(() => references.value?.runtimeEvidence === 'COMPLETE' && references.value?.publicationEvidence === 'COMPLETE')
const technicalFacts = computed(() => [
  { label: '请求类型', value: tool.value?.requestBodyType || '未声明' },
  { label: '返回类型', value: tool.value?.responseType || '未声明' },
  { label: '所属模块', value: metadata.value.module },
  { label: '声明类', value: metadata.value.className },
  { label: '声明方法', value: metadata.value.methodName },
  { label: '调用协议', value: metadata.value.invokeProtocol },
].filter(fact => fact.value !== undefined && fact.value !== null && fact.value !== ''))
const executionFacts = computed(() => capabilityExecutionFacts(metadata.value))
const effectDescription = computed(() => {
  switch (tool.value?.sideEffect?.toUpperCase()) {
    case 'NONE': case 'READ': case 'READ_ONLY': return '声明为只读，不应修改业务数据。'
    case 'IDEMPOTENT_WRITE': return '会修改业务数据，来源声明支持幂等写入。'
    case 'WRITE': return '会修改业务数据。调用前请确认操作对象与业务条件。'
    case 'IRREVERSIBLE': return '操作无法撤销，调用前必须核对对象与影响范围。'
    default: return '来源未声明副作用，请在使用前向业务系统确认。'
  }
})

function errorText(error: unknown) {
  const status = (error as { response?: { status?: number } })?.response?.status
  return status === 404 ? `这项${assetLabel.value}已不存在或不在当前项目中，请刷新${catalogLabel.value}。` : status === 403 ? `当前账号无权读取这项${assetLabel.value}，请联系项目管理员。` : '服务暂时未能返回详情，请稍后重试。'
}
async function loadDetail() {
  const name = props.capability?.name
  if (!props.modelValue || !name) return
  const current = ++sequence
  ++referenceSequence
  loading.value = true; loadError.value = ''; references.value = null; referencesError.value = ''; referencesLoading.value = false
  try {
    const { data } = await (props.catalogKind === 'business-method' ? getBusinessMethod(name) : getTool(name))
    if (current !== sequence) return
    if (!data || data.name !== name) throw new Error('Invalid capability response')
    tool.value = data; emit('refreshed', data)
  } catch (error) { if (current === sequence) loadError.value = errorText(error) }
  finally { if (current === sequence) { loading.value = false; if (!loadError.value && tab.value === 'references') void loadReferences() } }
}
async function loadReferences() {
  if (loading.value || loadError.value || !tool.value || !props.modelValue) return
  const current = ++referenceSequence
  references.value = null; referencesError.value = ''
  if (!tool.value.projectCode) { referencesError.value = '平台级能力暂不支持使用位置查询。'; return }
  referencesLoading.value = true
  try {
    const { data } = await getCapabilityReferences(tool.value.projectCode, tool.value.name)
    if (current !== referenceSequence) return
    if (!data || !Array.isArray(data.references)) throw new Error('Invalid references')
    references.value = data
  } catch { if (current === referenceSequence) referencesError.value = '使用位置查询失败，当前无法判断引用情况。请重试。' }
  finally { if (current === referenceSequence) referencesLoading.value = false }
}
async function copyText(value: string, label: string) {
  const previousFocus = document.activeElement as HTMLElement | null
  const result = await copyAiCodingText(value)
  if (previousFocus?.isConnected) previousFocus.focus()
  if (result.copied) ElMessage.success(`${label}已复制`)
  else ElMessage.warning('复制未成功，请选择内容后手动复制。')
}
function shouldConfirmDiscard() {
  return props.catalogKind === 'business-method' && invocationDirty.value
}
function openDiscardConfirmation() {
  discardConfirmation.value = true
  nextTick(() => discardCancel.value?.$el?.focus?.())
}
function requestDialogModelValue(value: boolean) {
  if (!value && shouldConfirmDiscard()) {
    openDiscardConfirmation()
    return
  }
  emit('update:modelValue', value)
}
function handleDetailBeforeClose(done: () => void) {
  if (shouldConfirmDiscard()) {
    openDiscardConfirmation()
    return
  }
  done()
}
function closeDiscardConfirmation(value: boolean) {
  if (!value) discardConfirmation.value = false
}
function cancelDiscard() { discardConfirmation.value = false; pendingNavigation.value = null }
function confirmDiscard() {
  discardConfirmation.value = false
  invocationDirty.value = false
  const target = pendingNavigation.value
  pendingNavigation.value = null
  emit('update:modelValue', false)
  if (target) void router.push(target)
}
function navigate(target: RouteLocationRaw) {
  if (shouldConfirmDiscard()) {
    pendingNavigation.value = target
    openDiscardConfirmation()
    return
  }
  emit('update:modelValue', false)
  void router.push(target)
}
function openChanges() { if (tool.value?.projectCode) navigate({ name: 'CapabilityReview', query: { projectCode: tool.value.projectCode, changeSearch: capabilityStableName(tool.value) } }) }
watch([() => props.modelValue, () => props.capability?.name, () => props.catalogKind, () => props.contextKey], () => {
  ++sequence; ++referenceSequence
  invocationDirty.value = false
  discardConfirmation.value = false
  pendingNavigation.value = null
  if (!props.modelValue) return
  tool.value = props.capability; tab.value = 'contract'; void loadDetail()
}, { immediate: true })
watch(tab, value => { if (value === 'references' && !references.value && !referencesLoading.value) void loadReferences() })
onBeforeUnmount(() => { ++sequence; ++referenceSequence })
</script>

<style scoped lang="scss">
:global(.app-dialog.capability-inspector.el-dialog) { height: min(820px, calc(100dvh - 56px)); padding: 0; background: var(--surface-fallback-overlay); border-radius: var(--radius-xl); }
:global(.app-dialog.capability-inspector .el-dialog__header) { padding: 24px 28px 20px; background: var(--surface-solid-panel); }
:global(.app-dialog.capability-inspector .el-dialog__body) { display: flex; padding: 0; overflow: hidden; max-block-size: none; }
.inspector-heading { padding-right: 28px; }
.inspector-eyebrow { display: flex; gap: 8px; color: var(--text-secondary); font-size: 12px; }
.inspector-eyebrow span { color: var(--text-muted); }
.inspector-heading h2 { margin: 10px 0 4px; color: var(--text-primary); font-size: 22px; line-height: 1.4; overflow-wrap: anywhere; }
.inspector-identifier { display: flex; align-items: center; gap: 8px; }
.inspector-identifier code { min-width: 0; overflow-wrap: anywhere; padding: 0; color: var(--text-secondary); background: transparent; font: 12px/1.5 ui-monospace, Consolas, monospace; }
.inspector-identifier .el-button { height: 26px; flex-shrink: 0; padding: 4px; }
.inspector-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 280px; width: 100%; min-height: 0; }
.inspector-main { min-width: 0; overflow: auto; overscroll-behavior: contain; padding: 24px 28px; scrollbar-gutter: stable; }
.inspector-description { margin-bottom: 22px; }
.description-label { font-size: 12px; color: var(--text-secondary); }
.inspector-description > p { margin: 8px 0 0; font-size: 14px; line-height: 1.85; color: var(--text-primary); overflow-wrap: anywhere; }
.inspector-description .el-alert { margin-top: 12px; }
.inspector-tabs :deep(.el-tabs__header) { margin: 0; }
.inspector-tabs :deep(.el-tabs__item) { height: 44px; font-size: 13px; }
.tab-count { margin-left: 6px; font-size: 11px; }
.inspector-section { padding: 20px 0 4px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 32px; margin-bottom: 12px; }
.section-heading h3, .aside-section h3 { margin: 0; font-size: 13px; font-weight: 650; color: var(--text-primary); }
.section-heading h3 span { margin-left: 6px; color: var(--text-muted); font-weight: 400; }
.section-heading > .el-button { padding: 4px 0 4px 8px; height: 28px; font-size: 12px; }
.section-hint { margin: 8px 0 16px; font-size: 12px; line-height: 1.7; color: var(--text-secondary); }
.response-definition, .endpoint-definition { border: 1px solid var(--border-divider); border-radius: var(--radius-md); padding: 14px 16px; background: var(--surface-solid-control); }
.response-definition code, .endpoint-definition code { padding: 0; background: transparent; font: 12px/1.8 ui-monospace, Consolas, monospace; overflow-wrap: anywhere; color: var(--text-primary); }
.response-definition p { margin: 6px 0 0; font-size: 12px; color: var(--text-secondary); }
.endpoint-definition { display: flex; align-items: flex-start; gap: 12px; }
.endpoint-definition > span { font: 600 11px/2 ui-monospace, Consolas, monospace; color: var(--brand-primary); }
.inspector-aside { padding: 20px 24px; border-left: 1px solid var(--border-divider); background: var(--surface-solid-page); min-height: 0; overflow: auto; overscroll-behavior: contain; }
.aside-label { display: flex; align-items: center; justify-content: space-between; color: var(--text-secondary); font-size: 12px; margin-bottom: 10px; }
.aside-label .el-button { height: 24px; padding: 4px; }
.readiness-title { display: flex; gap: 8px; align-items: flex-start; font-size: 14px; line-height: 1.6; color: var(--text-primary); }
.readiness-title .el-icon { flex-shrink: 0; margin-top: 3px; color: var(--status-warning); }
.is-success .readiness-title .el-icon { color: var(--status-success); }
.is-danger .readiness-title .el-icon { color: var(--status-danger); }
.is-neutral .readiness-title .el-icon { color: var(--status-neutral); }
.readiness-panel p, .aside-section p { color: var(--text-secondary); font-size: 12px; line-height: 1.8; margin: 8px 0 0; }
.readiness-panel > .el-button { font-size: 12px; padding: 4px 0; margin-top: 10px; }
.aside-section { margin-top: 18px; padding-top: 18px; border-top: 1px solid var(--border-divider); }
.aside-section h3 { margin-bottom: 12px; }
.execution-facts { margin: 14px 0 0; display: grid; gap: 10px; }
.execution-facts > div { display: flex; gap: 12px; justify-content: space-between; font-size: 12px; }
.execution-facts dt { color: var(--text-secondary); }
.execution-facts dd { margin: 0; color: var(--text-primary); overflow-wrap: anywhere; text-align: right; }
.source-section > strong { display: block; font-size: 13px; color: var(--text-primary); overflow-wrap: anywhere; }
.source-section > .el-button { margin-top: 14px; font-size: 12px; }
.technical-facts { margin: 0; }
.technical-facts > div { display: grid; grid-template-columns: 88px minmax(0, 1fr); gap: 16px; padding: 14px 0; border-bottom: 1px solid var(--border-divider); font-size: 12px; }
.technical-facts dt { color: var(--text-secondary); }
.technical-facts dd { margin: 0; overflow-wrap: anywhere; color: var(--text-primary); }
.inspector-empty-inline { display: flex; align-items: center; gap: 8px; padding: 16px; border: 1px dashed var(--border-readable); border-radius: var(--radius-md); color: var(--text-secondary); font-size: 13px; }
.inspector-empty-inline .el-icon { color: var(--status-success); }
.inspector-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; width: 100%; min-height: 220px; color: var(--text-secondary); }
.inspector-state > .el-icon, .reference-state > .el-icon { font-size: 24px; color: var(--text-muted); }
.inspector-state p, .inspector-state h3 { margin: 6px 0; font-size: 14px; }
.reference-state { text-align: center; padding: 40px 20px; color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
.reference-state h3 { color: var(--text-primary); font-size: 14px; font-weight: 600; }
.usage-list { margin: 12px 0 0; padding: 0; list-style: none; }
.usage-list li { display: flex; align-items: center; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--border-divider); }
.usage-icon { flex-shrink: 0; display: grid; place-items: center; width: 36px; height: 36px; border-radius: var(--radius-md); background: var(--surface-solid-page); color: var(--brand-primary); }
.usage-copy { flex: 1; min-width: 0; display: grid; gap: 5px; overflow-wrap: anywhere; }
.usage-copy strong { font-size: 13px; color: var(--text-primary); }
.usage-copy span, .usage-copy small { font-size: 11px; color: var(--text-secondary); }
.discard-copy { margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.8; }
@media (max-width: 800px) {
  :global(.app-dialog.capability-inspector .el-dialog__body) { overflow: auto; }
  .inspector-workspace { display: block; flex: none; }
  .inspector-main, .inspector-aside { overflow: visible; }
  .inspector-aside { border-left: 0; border-top: 1px solid var(--border-divider); }
  .inspector-main { padding: 20px; }
  .inspector-heading h2 { font-size: 19px; }
}
@media (max-width: 480px) {
  :global(.app-dialog.capability-inspector.el-dialog) { height: calc(100dvh - 24px); max-inline-size: calc(100vw - 24px); max-block-size: calc(100dvh - 24px); }
  :global(.app-dialog.capability-inspector .el-dialog__header) { padding: 18px 20px 14px; }
  .section-heading { flex-wrap: wrap; gap: 4px; }
  .inspector-aside { padding: 20px; }
}
</style>

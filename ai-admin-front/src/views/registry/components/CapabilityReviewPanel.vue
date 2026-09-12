<template>
  <section class="capability-review-panel" aria-label="能力变化">
    <div class="review-toolbar">
      <el-radio-group v-model="state" class="review-tabs" aria-label="变化范围" @change="changeFilter">
        <el-radio-button value="PENDING">待处理 <span v-if="pending" class="tab-count">{{ pending }}</span></el-radio-button>
        <el-radio-button value="PROCESSED">处理记录</el-radio-button>
      </el-radio-group>
      <div class="review-filters">
        <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索能力名称或标识"
          aria-label="搜索能力" class="review-search" @compositionstart="composing = true" @compositionend="compositionEnd" />
        <el-button :icon="Refresh" :loading="loading" :disabled="!projectCode" @click="loadChanges">刷新</el-button>
      </div>
    </div>
    <p class="review-summary">
      <el-icon aria-hidden="true"><InfoFilled /></el-icon>
      <span>{{ state === 'PENDING' ? '系统自动处理可确定的变化，只在需要业务判断时留待确认。' : '自动处理、人工决定与已被替代的变化，都保存在这里。' }}</span>
    </p>

    <div v-loading="loading" element-loading-text="正在加载变化…" class="review-content" :aria-busy="loading">
      <el-empty v-if="error" class="review-empty" :image-size="48" description="暂时无法加载能力变化">
        <template #image><el-icon class="empty-icon is-warning"><Warning /></el-icon></template>
        <p class="empty-hint">{{ error }}</p>
        <el-button @click="loadChanges">重新加载</el-button>
      </el-empty>
      <template v-else-if="projectCode && items.length">
        <div class="review-cards" aria-label="能力变化列表">
          <article v-for="item in items" :key="item.id">
            <div class="card-heading">
              <strong>{{ displayName(item) }}</strong>
              <StatusTag :label="state === 'PROCESSED' ? statusLabel(item) : changeLabel(item)" :tone="state === 'PROCESSED' ? statusTone(item) : changeTone(item)" />
            </div>
            <code class="capability-id">{{ item.qualifiedName }}</code>
            <p class="card-reason">{{ impactOf(item).reason || '查看变化详情' }}</p>
            <div class="card-footer">
              <span class="evidence-caption">{{ evidenceLabel(item) }}</span>
              <el-button link type="primary" @click="openDetail(item)">查看详情<el-icon class="detail-arrow" aria-hidden="true"><ArrowRight /></el-icon></el-button>
            </div>
          </article>
        </div>
        <el-table class="review-table" :data="items" row-key="id" aria-label="能力变化列表">
          <el-table-column label="能力" min-width="230">
            <template #default="{ row }"><div class="ability-cell"><strong>{{ displayName(row) }}</strong><code class="capability-id">{{ row.qualifiedName }}</code></div></template>
          </el-table-column>
          <el-table-column label="变化" width="120">
            <template #default="{ row }"><StatusTag :label="changeLabel(row)" :tone="changeTone(row)" /></template>
          </el-table-column>
          <el-table-column :label="state === 'PENDING' ? '需要确认的原因' : '处理说明'" min-width="300">
            <template #default="{ row }"><div class="reason-cell"><span>{{ impactOf(row).reason || '查看变化详情' }}</span><small class="evidence-caption">{{ evidenceLabel(row) }}</small></div></template>
          </el-table-column>
          <el-table-column v-if="state === 'PROCESSED'" label="结果" width="122">
            <template #default="{ row }"><StatusTag :label="statusLabel(row)" :tone="statusTone(row)" /></template>
          </el-table-column>
          <el-table-column label="操作" width="124" align="right" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">查看详情<el-icon class="detail-arrow" aria-hidden="true"><ArrowRight /></el-icon></el-button></template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else-if="!loading" class="review-empty" :description="projectCode ? emptyDescription : '选择项目，查看需要确认的变化'" :image-size="48">
        <template #image><el-icon class="empty-icon" :class="{ 'is-clear': projectCode && !keyword && state === 'PENDING' }"><component :is="!projectCode ? FolderOpened : keyword ? Search : state === 'PENDING' ? CircleCheck : Clock" /></el-icon></template>
        <p class="empty-hint">{{ emptyHint }}</p>
        <el-button v-if="keyword && projectCode" @click="keyword = ''">清除搜索</el-button>
      </el-empty>
    </div>
    <div v-if="projectCode && total > 0 && !error" class="review-pagination">
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changeFilter" />
    </div>

    <AppDrawer v-model="detailVisible" class="capability-change-drawer" title="变化详情" size="min(720px, 96vw)" append-to-body destroy-on-close>
      <div v-if="selected" class="change-detail">
        <section class="detail-identity">
          <div class="detail-heading"><h3>{{ displayName(selected) }}</h3><StatusTag :label="statusLabel(selected)" :tone="statusTone(selected)" /></div>
          <code class="capability-id">{{ selected.qualifiedName }}</code>
          <p class="detail-reason">{{ impact.reason || '核对本次能力定义变化。' }}</p>
          <el-alert v-if="sourceWarning" :title="sourceWarning" type="warning" :closable="false" show-icon />
        </section>

        <section class="detail-section">
          <header class="detail-section-heading"><h4>使用影响</h4><span v-if="references.length">{{ references.length }} 处已确认引用</span></header>
          <p v-if="!evidenceComplete" class="evidence-notice"><el-icon aria-hidden="true"><Warning /></el-icon><span>部分引用证据暂未确认，不能据此判断没有影响。</span></p>
          <ul v-if="references.length" class="references">
            <li v-for="(reference, index) in references" :key="index">
              <span class="reference-icon" aria-hidden="true"><el-icon><component :is="reference.kind === 'AGENT' ? User : reference.kind === 'WORKFLOW' ? Connection : Share" /></el-icon></span>
              <div class="reference-name"><strong>{{ reference.name || reference.id }}</strong><span>{{ referenceKindLabel(reference.kind) }}</span></div>
              <span class="reference-stage">{{ stageLabel(reference.stage) }}{{ reference.version ? ` · ${reference.version}` : '' }}</span>
            </li>
          </ul>
          <p v-else class="detail-muted">{{ evidenceComplete ? '当前检查未发现 Workflow、Agent 或开放发布引用。' : '暂无已确认的引用。' }}</p>
          <p v-if="impact.checkedAt" class="checked-at"><el-icon aria-hidden="true"><Clock /></el-icon>检查时间：{{ new Date(impact.checkedAt).toLocaleString('zh-CN') }}</p>
        </section>

        <section class="detail-section">
          <header class="detail-section-heading"><h4>定义对比</h4><span v-if="fields.length">{{ fields.length }} 项字段变化</span></header>
          <div v-for="field in fields" :key="field.field" class="field-diff">
            <strong class="field-name">{{ capabilityFieldLabel(field.field) }}</strong>
            <div class="field-comparison">
              <section class="field-before"><small>当前目录</small><pre class="field-value">{{ prettyCapabilityValue(field.oldValue) }}</pre></section>
              <section class="field-after"><small><el-icon aria-hidden="true"><ArrowRight /></el-icon>本次上报</small><pre class="field-value">{{ prettyCapabilityValue(field.newValue) }}</pre></section>
            </div>
          </div>
          <p v-if="!fields.length" class="detail-muted">{{ selected.changeType === 'DELETED' ? '本次上报中已没有这项能力。' : selected.changeType === 'ADDED' ? '本次上报新增了这项能力。' : '没有字段差异。' }}</p>
          <dl v-if="selected.changeType === 'ADDED' && impact.candidate" class="candidate-summary">
            <div><dt>业务用途</dt><dd>{{ impact.candidate.description || '尚未说明' }}</dd></div>
            <div><dt>读写行为</dt><dd>{{ impact.candidate.sideEffect === 'READ_ONLY' ? '只读查询' : '会修改业务数据，请核对操作范围' }}</dd></div>
            <div><dt>调用入口</dt><dd>{{ impact.candidate.httpMethod }} {{ impact.candidate.endpointPath }}</dd></div>
          </dl>
        </section>

        <section v-if="selected.reviewNote" class="detail-section"><header class="detail-section-heading"><h4>处理说明</h4></header><p class="review-note">{{ selected.reviewNote }}</p></section>
        <el-collapse class="technical-record">
          <el-collapse-item title="来源与处理记录" name="source">
            <dl class="source-facts"><div><dt>同步标识</dt><dd>{{ selected.syncId }}</dd></div><div><dt>策略版本</dt><dd>{{ impact.policyVersion || '历史记录' }}</dd></div></dl>
            <pre v-if="impact.candidate" class="source-json">{{ JSON.stringify(impact.candidate, null, 2) }}</pre>
          </el-collapse-item>
        </el-collapse>
        <p v-if="!canReview" class="permission-note"><el-icon aria-hidden="true"><Lock /></el-icon><span>当前账号可以查看记录，处理变化需要项目管理权限。</span></p>
      </div>
      <template #footer>
        <div class="detail-actions">
          <el-button @click="detailVisible = false">关闭</el-button>
          <div class="decision-actions">
            <template v-if="selected && canReview && selected.reviewStatus === 'PENDING' && impact.currentCandidate !== false">
              <el-button @click="prepareDecision('IGNORE')">忽略这次变化</el-button>
              <el-button type="primary" @click="prepareDecision('APPLY')">{{ selected.changeType === 'DELETED' ? '确认下线' : '接受变化' }}</el-button>
            </template>
            <el-button v-if="selected?.rollbackAvailable && ['APPLIED', 'AUTO_APPLIED'].includes(selected.reviewStatus) && canReview" type="warning" @click="prepareDecision('ROLLBACK')">恢复上次目录定义</el-button>
          </div>
        </div>
      </template>
    </AppDrawer>
    <AppDialog v-model="decisionVisible" class="capability-change-dialog" :title="decisionTitle" width="min(540px, 94vw)" append-to-body :show-close="!acting" :close-on-click-modal="!acting" :close-on-press-escape="!acting">
      <div class="decision-context"><strong>{{ selected ? displayName(selected) : '' }}</strong><p>{{ decisionDescription }}</p></div>
      <el-alert v-if="decisionError" type="error" :title="decisionError" :closable="false" show-icon class="decision-error" />
      <el-form label-position="top"><el-form-item label="处理说明（可选）"><el-input v-model="note" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" resize="none" maxlength="500" show-word-limit :disabled="acting" /></el-form-item></el-form>
      <template #footer><el-button :disabled="acting" @click="decisionVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitDecision">{{ decisionTitle }}</el-button></template>
    </AppDialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, CircleCheck, Clock, Connection, FolderOpened, InfoFilled, Lock, Refresh, Search, Share, User, Warning } from '@element-plus/icons-vue'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_WRITE } from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'
import AppDrawer from '@/components/common/AppDrawer.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { StatusTone } from '@/components/common/glassWorkbench'
import { listCapabilityChanges, reviewCapabilityDiffItem, rollbackCapabilityDiffItem } from '@/api/registry'
import type { CapabilityDiffReviewItem } from '@/types/registry'
import { capabilityFieldLabel, parseCapabilityFieldDiffs, prettyCapabilityValue } from '@/views/capability/capabilityGovernance'

type Action = 'APPLY' | 'IGNORE' | 'ROLLBACK'
type Reference = { kind: string; id: string; name?: string; stage?: string; version?: string }
interface Impact { reason?: string; policyVersion?: string; sourceAvailability?: string; currentCandidate?: boolean; runtimeEvidence?: string; publicationEvidence?: string; references?: Reference[]; checkedAt?: string; candidate?: { title?: string; description?: string; sideEffect?: string; httpMethod?: string; endpointPath?: string } }
const props = defineProps<{ projectCode: string }>()
const emit = defineEmits<{ summaryChange: [summary: { pending: number; attention: number; state: 'unselected' | 'loading' | 'ready' | 'error' }]; catalogChange: [] }>()
const route = useRoute(); const router = useRouter()
const state = ref<'PENDING' | 'PROCESSED'>(route.query.changes === 'history' ? 'PROCESSED' : 'PENDING')
const keyword = ref(String(route.query.changeSearch || '')); const page = ref(Math.max(1, Number(route.query.changePage) || 1)); const size = ref(20)
const items = ref<CapabilityDiffReviewItem[]>([]); const total = ref(0); const pending = ref(0); const loading = ref(false); const error = ref(''); const composing = ref(false)
const selected = ref<CapabilityDiffReviewItem | null>(null); const detailVisible = ref(false); const decisionVisible = ref(false); const action = ref<Action>('APPLY'); const acting = ref(false); const note = ref(''); const decisionError = ref('')
let sequence = 0; let restoringRoute = false; let timer: ReturnType<typeof setTimeout> | undefined
const canReview = computed(() => hasPlatformResourcePermission(platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_WRITE, 'PROJECT', null, props.projectCode))
const emptyDescription = computed(() => keyword.value.trim() ? '没有匹配的变化，试试其他关键词' : state.value === 'PENDING' ? '目前没有需要你处理的变化' : '暂无处理记录')

const emptyHint = computed(() => !props.projectCode ? '项目内的变化和处理记录会显示在这里。' : keyword.value.trim() ? '可以调整关键词，或清除搜索查看所有变化。' : state.value === 'PENDING' ? '后续需要你确认的变化会显示在这里。' : '系统处理与人工决定会保存在这里。')
function changeTone(item: CapabilityDiffReviewItem): StatusTone { return item.changeType === 'DELETED' ? 'warning' : item.changeType === 'ADDED' ? 'success' : item.changeType === 'CHANGED' ? 'info' : 'neutral' }
function statusTone(item: CapabilityDiffReviewItem): StatusTone { return ['AUTO_APPLIED', 'APPLIED'].includes(item.reviewStatus) ? 'success' : item.reviewStatus === 'PENDING' ? 'warning' : 'neutral' }
function referenceKindLabel(kind: string) { return ({ WORKFLOW: 'Workflow', AGENT: 'Agent', MCP: 'MCP 开放', A2A: 'A2A 开放' } as Record<string, string>)[kind] || kind }

function impactOf(item: CapabilityDiffReviewItem): Impact { try { return JSON.parse(item.impactJson || '{}') || {} } catch { return {} } }
function displayName(item: CapabilityDiffReviewItem) { return impactOf(item).candidate?.title || item.name }
const impact = computed(() => selected.value ? impactOf(selected.value) : {})
const references = computed(() => Array.isArray(impact.value.references) ? impact.value.references : [])
const fields = computed(() => selected.value ? parseCapabilityFieldDiffs(selected.value) : [])
const evidenceComplete = computed(() => impact.value.runtimeEvidence === 'COMPLETE' && impact.value.publicationEvidence === 'COMPLETE')
const sourceWarning = computed(() => ({ SOURCE_MISSING: '来源已停止提供，相关调用已暂停。恢复目录不能恢复业务实现。', CONTRACT_DRIFT: '来源契约已变化，相关调用已暂停。接受变化前请确认调用方已适配。', SOURCE_UNKNOWN: '来源尚未确认，请从业务系统重新同步。' } as Record<string, string>)[impact.value.sourceAvailability || ''])
function changeLabel(item: CapabilityDiffReviewItem) { return { ADDED: '新增能力', CHANGED: '定义变化', DELETED: '停止提供', UNCHANGED: '无变化' }[item.changeType] }
function statusLabel(item: CapabilityDiffReviewItem) { return { PENDING: '待确认', AUTO_APPLIED: '自动完成', APPLIED: '已接受', UNCHANGED: '无需处理', IGNORED: '已忽略', SUPERSEDED: '已被替代', ROLLED_BACK: '已恢复目录', DIAGNOSTIC: '诊断记录' }[item.reviewStatus] }
function stageLabel(stage?: string) { return ({ DRAFT: '草稿', PUBLISHED: '已发布', HISTORICAL: '历史版本', EXTERNAL_PIN: '开放发布固定版本' } as Record<string, string>)[stage || ''] || stage || '' }
function evidenceLabel(item: CapabilityDiffReviewItem) { const value = impactOf(item); const count = value.references?.length || 0; return `${count ? `${count} 处已确认引用` : '暂无已确认引用'}${value.runtimeEvidence !== 'COMPLETE' || value.publicationEvidence !== 'COMPLETE' ? ' · 证据待补全' : ''}` }
function openDetail(item: CapabilityDiffReviewItem) { selected.value = item; detailVisible.value = true }
function errorText(reason: unknown) { const value = reason as { response?: { data?: { message?: string } }; message?: string }; return value?.response?.data?.message || value?.message || '请求未完成，请稍后重试' }
function persistFilters() { void router.replace({ query: { ...route.query, changes: state.value === 'PROCESSED' ? 'history' : undefined, changeSearch: keyword.value || undefined, changePage: page.value > 1 ? String(page.value) : undefined } }) }
async function loadChanges(): Promise<void> {
  const requestId = ++sequence; const project = props.projectCode
  if (!project) { items.value = []; total.value = 0; loading.value = false; emit('summaryChange', { pending: 0, attention: 0, state: 'unselected' }); return }
  loading.value = true; error.value = ''; emit('summaryChange', { pending: pending.value, attention: 0, state: 'loading' })
  try {
    const response = await listCapabilityChanges(project, { state: state.value, keyword: keyword.value.trim(), current: page.value, size: size.value })
    if (requestId !== sequence || project !== props.projectCode) return
    const data = response.data; items.value = data.records; total.value = data.total; pending.value = data.pending
    if (page.value > 1 && !items.value.length && total.value < (page.value - 1) * size.value + 1) { page.value = Math.max(1, Math.ceil(total.value / size.value)); return loadChanges() }
    emit('summaryChange', { pending: pending.value, attention: pending.value, state: 'ready' })
  } catch (reason) { if (requestId === sequence) { error.value = errorText(reason); items.value = []; total.value = 0; emit('summaryChange', { pending: 0, attention: 0, state: 'error' }) } }
  finally { if (requestId === sequence) loading.value = false }
}
function changePage() { persistFilters(); void loadChanges() }
function changeFilter() { clearTimeout(timer); page.value = 1; changePage() }
function scheduleSearch() { clearTimeout(timer); if (!composing.value) timer = setTimeout(changeFilter, 300) }
function compositionEnd() { composing.value = false; scheduleSearch() }
watch(keyword, () => { if (!restoringRoute) scheduleSearch() }, { flush: 'sync' })
watch(() => props.projectCode, (_project, previous) => { clearTimeout(timer); detailVisible.value = false; decisionVisible.value = false; selected.value = null; error.value = ''; pending.value = 0; if (previous) page.value = 1; void loadChanges() }, { immediate: true })
watch(() => [route.query.changes, route.query.changeSearch, route.query.changePage], () => {
  const nextState = route.query.changes === 'history' ? 'PROCESSED' : 'PENDING'; const nextKeyword = String(route.query.changeSearch || ''); const nextPage = Math.max(1, Number(route.query.changePage) || 1)
  if (nextState === state.value && nextKeyword === keyword.value && nextPage === page.value) return
  restoringRoute = true; state.value = nextState; keyword.value = nextKeyword; page.value = nextPage; restoringRoute = false
  clearTimeout(timer); void loadChanges()
})
const decisionTitle = computed(() => action.value === 'IGNORE' ? '忽略这次变化' : action.value === 'ROLLBACK' ? '恢复上次目录定义' : selected.value?.changeType === 'DELETED' ? '确认下线' : '接受变化')
const decisionDescription = computed(() => action.value === 'IGNORE' ? '移入处理记录，相同变化不会重复提醒。忽略不会恢复业务接口，也不会解除来源不一致时的调用暂停。' : action.value === 'ROLLBACK' ? '只恢复上次接受前的目录定义。业务系统和已发布版本保持各自状态；来源仍不一致时，调用继续暂停。' : selected.value?.changeType === 'DELETED' ? '将能力从可用目录下线。请先处理仍依赖它的调用方。' : '使用本次上报更新目录。已发布的 Workflow 和 MCP 保留原契约，契约变化后需校验并重新发布才能继续调用。')
function prepareDecision(next: Action) { if (!selected.value || !canReview.value || selected.value.projectCode !== props.projectCode) return; action.value = next; note.value = ''; decisionError.value = ''; decisionVisible.value = true }
async function submitDecision() {
  const item = selected.value; const project = props.projectCode
  if (!item || acting.value || !canReview.value || item.projectCode !== project) return
  acting.value = true; decisionError.value = ''
  try {
    if (action.value === 'ROLLBACK') await rollbackCapabilityDiffItem(project, item.id, { note: note.value.trim() || undefined })
    else await reviewCapabilityDiffItem(project, item.id, { action: action.value, note: note.value.trim() || undefined })
    if (project !== props.projectCode) return
    decisionVisible.value = false; detailVisible.value = false; selected.value = null; ElMessage.success('处理结果已保存'); emit('catalogChange'); await loadChanges()
  } catch (reason) { if (project === props.projectCode) decisionError.value = errorText(reason) }
  finally { acting.value = false }
}
onBeforeUnmount(() => { sequence++; clearTimeout(timer) })
defineExpose({ refresh: loadChanges })
</script>

<style scoped lang="scss">
.capability-review-panel { min-width: 0; padding: 20px 24px 0; container-type: inline-size; }
.review-toolbar, .review-filters { display: flex; align-items: center; gap: 12px; }
.review-toolbar { justify-content: space-between; flex-wrap: wrap; }
.review-filters { margin-left: auto; }
.review-search { width: 260px; }
.review-tabs { padding: 4px; border: 1px solid var(--border-subtle); border-radius: var(--radius-md); background: var(--surface-solid-control); flex-shrink: 0; }
.review-tabs :deep(.el-radio-button__inner) { display: inline-flex; min-height: 32px; align-items: center; gap: 8px; padding: 6px 14px; border: 0; border-radius: var(--radius-sm); background: transparent; color: var(--text-secondary); box-shadow: none; font-size: 13px; }
.review-tabs :deep(.el-radio-button:first-child .el-radio-button__inner), .review-tabs :deep(.el-radio-button:last-child .el-radio-button__inner) { border: 0; border-radius: var(--radius-sm); box-shadow: none; }
.review-tabs :deep(.el-radio-button.is-active .el-radio-button__inner) { background: var(--brand-primary); color: var(--text-inverse); box-shadow: none; }
.review-tabs :deep(.el-radio-button:not(.is-active) .el-radio-button__inner:hover) { color: var(--brand-primary); background: rgb(var(--brand-primary-rgb) / 0.05); }
.tab-count { min-width: 19px; padding: 1px 5px; border-radius: var(--radius-sm); background: color-mix(in srgb, currentColor 18%, transparent); font-size: 11px; line-height: 17px; font-variant-numeric: tabular-nums; }
.review-summary { display: flex; align-items: flex-start; gap: 7px; margin: 16px 0 20px; color: var(--text-secondary); font-size: 12px; line-height: 1.7; }
.review-summary .el-icon { margin-top: 3px; color: var(--text-muted); flex-shrink: 0; }
.review-content { min-height: 220px; margin-bottom: 20px; }
.review-content :deep(.el-loading-mask) { border-radius: var(--radius-md); }
.review-table { border: 1px solid var(--border-subtle); border-radius: var(--radius-md); overflow: hidden; --el-table-text-color: var(--text-primary); --el-table-header-bg-color: var(--surface-solid-control); }
.review-table :deep(.el-table__cell) { padding: 15px 0; }
.review-table :deep(th.el-table__cell) { padding: 10px 0; color: var(--text-muted); font-size: 12px; font-weight: 500; }
.review-table :deep(.cell) { padding: 0 16px; }
.review-table :deep(.el-table__inner-wrapper::before) { display: none; }
.review-table :deep(.el-table__row:last-child td.el-table__cell) { border-bottom: 0; }
.ability-cell, .reason-cell { display: flex; min-width: 0; flex-direction: column; gap: 7px; line-height: 1.6; }
.ability-cell strong { color: var(--text-primary); font-size: 14px; font-weight: 650; }
.capability-id { display: block; padding: 0; border: 0; background: none; color: var(--text-muted); font: 11px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace; overflow-wrap: anywhere; }
.reason-cell > span { font-size: 13px; }
.evidence-caption { color: var(--text-secondary); font-size: 11px; line-height: 1.6; }
.detail-arrow { margin-left: 5px; font-size: 11px; }
.review-cards { display: none; gap: 12px; }
.review-cards article { min-width: 0; padding: 18px; border: 1px solid var(--border-subtle); border-radius: var(--radius-md); background: var(--surface-solid-control); }
.card-heading, .card-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-heading { align-items: flex-start; margin-bottom: 7px; }
.card-heading strong { min-width: 0; color: var(--text-primary); font-size: 15px; line-height: 1.55; overflow-wrap: anywhere; }
.card-heading :deep(.status-tag) { flex-shrink: 0; margin-top: 2px; }
.card-reason { margin: 15px 0 16px; color: var(--text-secondary); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.card-footer { border-top: 1px solid var(--border-divider); padding-top: 12px; }
.card-footer > span { min-width: 0; }
.card-footer .el-button { flex-shrink: 0; }
.review-pagination { display: flex; justify-content: flex-end; padding: 18px 0; border-top: 1px solid var(--border-divider); }
.review-pagination :deep(.el-pagination) { justify-content: flex-end; flex-wrap: wrap; row-gap: 10px; }
.review-empty { min-height: 300px; padding: 38px 16px 48px; }
.review-empty :deep(.el-empty__image) { opacity: 1; }
.empty-icon { display: grid; width: 48px; height: 48px; place-items: center; border-radius: var(--radius-lg); background: var(--status-neutral-soft); color: var(--text-muted); font-size: 27px; }
.empty-icon :deep(svg) { color: inherit; }
.empty-icon.is-clear { color: var(--status-success); background: var(--status-success-soft); }
.empty-icon.is-warning { color: var(--status-warning); background: var(--status-warning-soft); }
.review-empty :deep(.el-empty__description p) { color: var(--text-primary); font-size: 15px; font-weight: 600; }
.review-empty :deep(.el-empty__bottom) { margin-top: 8px; }
.empty-hint { max-width: 400px; margin: 0 0 18px; color: var(--text-secondary); font-size: 12px; line-height: 1.7; }

.change-detail { display: flex; flex-direction: column; gap: 20px; }
.detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 7px; }
.detail-heading h3 { margin: 0; color: var(--text-primary); font-size: 21px; font-weight: 650; line-height: 1.45; overflow-wrap: anywhere; }
.detail-heading :deep(.status-tag) { margin-top: 4px; flex-shrink: 0; }
.detail-identity .capability-id { font-size: 12px; }
.detail-reason { margin: 16px 0; color: var(--text-secondary); font-size: 14px; line-height: 1.8; }
.detail-identity :deep(.el-alert) { padding: 12px 14px; border-radius: var(--radius-md); }
.detail-identity :deep(.el-alert__title) { font-size: 12px; line-height: 1.75; font-weight: 400; }
.detail-section { padding-top: 20px; border-top: 1px solid var(--border-divider); }
.detail-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.detail-section-heading h4 { margin: 0; color: var(--text-primary); font-size: 14px; font-weight: 650; }
.detail-section-heading > span { color: var(--text-muted); font-size: 11px; }
.evidence-notice { display: flex; align-items: flex-start; gap: 8px; margin: 0 0 12px; color: var(--el-color-warning-dark-2); font-size: 12px; line-height: 1.7; }
.evidence-notice .el-icon { margin-top: 3px; flex-shrink: 0; }
.references { margin: 0; padding: 0; list-style: none; }
.references li { display: flex; align-items: center; gap: 12px; padding: 12px 0; }
.references li + li { border-top: 1px solid var(--border-divider); }
.reference-icon { display: grid; width: 34px; height: 34px; place-items: center; flex-shrink: 0; border: 1px solid var(--border-subtle); border-radius: var(--radius-md); background: var(--surface-solid-control); color: var(--text-secondary); font-size: 16px; }
.reference-name { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 4px; }
.reference-name strong { color: var(--text-primary); font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
.reference-name > span, .reference-stage { color: var(--text-secondary); font-size: 11px; }
.reference-stage { max-width: 42%; text-align: right; line-height: 1.6; overflow-wrap: anywhere; }
.checked-at { display: flex; align-items: center; gap: 5px; margin: 12px 0 0; color: var(--text-muted); font-size: 11px; }
.field-diff + .field-diff { margin-top: 20px; }
.field-name { display: block; margin-bottom: 10px; color: var(--text-secondary); font-size: 12px; font-weight: 600; }
.field-comparison { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
.field-comparison section { min-width: 0; padding: 14px; border: 1px solid var(--border-subtle); border-radius: var(--radius-md); background: var(--surface-solid-control); }
.field-comparison .field-after { border-color: rgb(var(--brand-primary-rgb) / 0.2); background: rgb(var(--brand-primary-rgb) / 0.035); }
.field-comparison small { display: flex; align-items: center; gap: 6px; margin-bottom: 12px; color: var(--text-muted); font-size: 11px; }
.field-after small { color: var(--brand-active); }
.field-value { margin: 0; padding: 0; border: 0; border-radius: 0; background: none; box-shadow: none; color: var(--text-primary); font: 12px/1.7 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.candidate-summary { margin: 16px 0 0; padding: 16px; border: 1px solid var(--border-subtle); border-radius: var(--radius-md); background: var(--surface-solid-control); }
.candidate-summary > div { display: grid; grid-template-columns: 78px minmax(0, 1fr); gap: 16px; font-size: 12px; line-height: 1.75; }
.candidate-summary > div + div { margin-top: 12px; }
.candidate-summary dt, .source-facts dt { color: var(--text-muted); }
.candidate-summary dd { margin: 0; color: var(--text-primary); overflow-wrap: anywhere; }
.detail-muted, .review-note { margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }
.technical-record { --el-collapse-border-color: var(--border-divider); --el-collapse-header-bg-color: transparent; --el-collapse-content-bg-color: transparent; --el-collapse-header-text-color: var(--text-secondary); }
.technical-record :deep(.el-collapse-item__header) { background: transparent; font-size: 12px; font-weight: 500; }
.technical-record :deep(.el-collapse-item__wrap) { background: transparent; }
.technical-record :deep(.el-collapse-item__content) { padding-bottom: 16px; }
.source-facts { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; margin: 12px 0 0; font-size: 11px; }
.source-facts dd { margin: 6px 0 0; color: var(--text-secondary); overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.source-json { max-height: 320px; margin: 16px 0 0; padding: 14px; border: 1px solid var(--border-subtle); border-radius: var(--radius-md); background: var(--surface-solid-control); font: 11px/1.7 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; overflow: auto; overflow-wrap: anywhere; }
.permission-note { display: flex; align-items: flex-start; gap: 7px; margin: -6px 0 0; color: var(--text-muted); font-size: 12px; line-height: 1.7; }
.permission-note .el-icon { margin-top: 3px; flex-shrink: 0; }
.detail-actions { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 16px; }
.decision-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.decision-actions .el-button + .el-button { margin-left: 0; }
.decision-context strong { color: var(--text-primary); font-size: 16px; font-weight: 650; }
.decision-context p { margin: 12px 0 24px; color: var(--text-secondary); font-size: 13px; line-height: 1.85; }
.decision-error { margin-bottom: 20px; }
:global(.capability-change-drawer.el-drawer), :global(.capability-change-dialog.el-dialog) { background: var(--surface-solid-overlay); }

@container (max-width: 830px) { .review-table { display: none; } .review-cards { display: grid; } }
@container (max-width: 630px) { .review-filters { width: 100%; margin-left: 0; } .review-search { width: auto; flex: 1; } }
@container (max-width: 330px) { .card-heading { display: grid; } .card-heading :deep(.status-tag) { justify-self: start; } }
@media (max-width: 900px) { .capability-review-panel { padding: 16px 16px 0; } }
@media (max-width: 600px) {
  .review-cards article { padding: 15px; }
  .card-footer { align-items: flex-start; }
  .review-pagination :deep(.el-pagination__sizes) { display: none; }
  .field-comparison { grid-template-columns: 1fr; }
  .detail-heading h3 { font-size: 19px; }
  .detail-actions { flex-wrap: wrap; gap: 10px; }
  .decision-actions { margin-left: auto; }
  .source-facts { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) { .review-tabs :deep(.el-radio-button__inner) { transition: none; } }
</style>

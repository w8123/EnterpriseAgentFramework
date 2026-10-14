<template>
  <section class="http-api-catalog" aria-label="HTTP API 目录">
    <ProjectScopeState v-if="!scopeReady || !projectId" title="请选择一个项目查看 API"
      :message="scope?.feedbackMessage.value || '请从左侧当前范围选择具体项目。'"
      :status="scope?.status.value === 'blocked' ? 'blocked' : 'pending'"
      :can-retry="scope?.recoveryAction.value === 'retry-catalog' || scope?.recoveryAction.value === 'retry-normalization'"
      :retrying="scope?.isCatalogLoading.value" @retry="scope?.retryScope()" />

    <el-alert v-else-if="error" type="error" :closable="false" show-icon :title="error">
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <BusinessCapabilityCatalogList v-if="scopeReady && projectId" :current-page="page" :page-size="size" :total="total"
      :loading="loading" :empty="!loading && !error && rows.length === 0"
      :composing="composing" :has-filters="hasFilters"
      :empty-description="hasFilters ? '没有符合当前条件的 API' : '当前项目还没有 API'"
      empty-hint="请先在当前项目扫描或同步 HTTP API；发现的接口会自动出现在这里。"
      @query="applyFilters"
      @page-change="changePage" @size-change="changeSize">
      <template #filters>
        <el-input v-model="draft.keyword" class="filter-control is-keyword" :prefix-icon="Search" clearable
          placeholder="搜索 HTTP API 名称、路径或稳定标识" aria-label="搜索 API"
          @compositionstart="composing = true" @compositionend="composing = false" @clear="applyFilters" />
        <el-select ref="classificationFilterRef" :model-value="query.filters.value.groupId" class="filter-control is-classification"
          placeholder="业务分类" aria-label="业务分类筛选，数量为项目全量"
          :loading="classification.loading.value" :disabled="classification.loading.value || !classification.index.value"
          clearable @change="selectClassification($event || '')">
          <template v-if="classification.index.value">
            <el-option value="" :label="`全部能力（${classification.index.value.memberships.length}）`" />
            <el-option v-for="group in classificationFilterGroups" :key="group.id" :value="String(group.id)"
              :label="`${group.displayName || group.name}（${group.memberCount}）`" />
            <el-option value="unclassified" :label="`未分类（${classification.index.value.ungroupedCount}）`" />
          </template>
          <el-option v-if="classificationSelectionUnavailable" :value="query.filters.value.groupId"
            :label="query.filters.value.groupId === 'unclassified' ? '未分类（待更新）' : classification.index.value ? '所选分类已不可用' : '所选分类待更新'" disabled />
          <template v-if="selectedClassificationGroup" #footer>
            <div class="classification-filter-actions">
              <el-button text size="small" :aria-label="`查看${selectedClassificationGroup.displayName || selectedClassificationGroup.name}分类说明`"
                @click="openSelectedClassification('description')">查看分类说明</el-button>
              <el-button v-if="adjustingClassification" text size="small" :aria-label="`重命名${selectedClassificationGroup.displayName || selectedClassificationGroup.name}分类`"
                @click="openSelectedClassification('rename')">重命名分类</el-button>
            </div>
          </template>
        </el-select>
        <el-select v-model="draft.environment" clearable placeholder="环境" aria-label="环境筛选"
          class="filter-control" @change="query.applyField('environment')">
          <el-option v-for="item in environments" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="draft.method" clearable placeholder="HTTP 方法" aria-label="HTTP 方法筛选"
          class="filter-control" @change="query.applyField('method')">
          <el-option v-for="item in methods" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="draft.sourceStatus" clearable placeholder="来源状态" aria-label="来源状态筛选"
          class="filter-control" @change="query.applyField('sourceStatus')">
          <el-option v-for="item in statuses" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </template>

      <CatalogClassificationSections :rows="rows" :identity="apiIdentity" :index="classification.index.value"
        :loading="classification.loading.value" :error="classification.error.value" :selected="query.filters.value.groupId"
        :can-write="canWriteClassification" :adjusting="adjustingClassification" :search-key="classificationSearchKey"
        layout="flat" :show-navigation="false" @select="selectClassification" @retry="classification.refresh" @edit="openClassificationEditor" @assign="assignManyClassifications" @create="createClassification">
      <template #default="{ rows: pageRows, isSelected, toggleRow }">
      <el-table v-if="rows.length" :data="pageRows" row-key="id" class="api-table">
        <el-table-column v-if="adjustingClassification" width="56" align="center">
          <template #default="{ row }"><span @click.stop><el-checkbox :model-value="isSelected(row)"
            :aria-label="`选择${row.routeTemplate}`" @change="toggleRow(row, !!$event)" /></span></template>
        </el-table-column>
        <el-table-column label="API / 完整路径" min-width="350">
          <template #default="{ row }">
            <span v-if="adjustingClassification" class="api-identity api-identity--static">
              <span class="api-method">{{ row.httpMethod }}</span>
              <span class="api-route">{{ row.routeTemplate }}</span>
            </span>
            <router-link v-else class="api-identity" :to="detailLocation(row.id)">
              <span class="api-method">{{ row.httpMethod }}</span>
              <span class="api-route">{{ row.routeTemplate }}</span>
            </router-link>
            <small v-if="!adjustingClassification" class="api-subline api-qualified-name" :title="row.qualifiedName">{{ row.qualifiedName }}</small>
            <dl v-if="!adjustingClassification" class="api-compact-summary" aria-label="API 关键状态">
              <div><dt>来源</dt><dd>{{ row.sourceKinds.map(sourceKindLabel).join('、') || '暂无来源' }} · {{ statusLabel(row.sourceStatus) }}</dd></div>
              <div><dt>连接</dt><dd>{{ connectionLabel(row.connectionStatus) }}</dd></div>
              <div><dt>最近试调用</dt><dd>{{ latestTrialLabel(row) }}</dd></div>
              <div><dt>下一步</dt><dd>{{ nextAction(row) }}</dd></div>
            </dl>
          </template>
        </el-table-column>
        <el-table-column :label="adjustingClassification ? '当前归属' : '业务分类'" width="180">
          <template #default="{ row }">
            <div v-if="rowClassifications(row)?.length" class="catalog-category-cell">
              <el-button v-for="group in rowClassifications(row)" :key="group.id" class="catalog-category-link"
                :aria-label="`查看${group.displayName || group.name}分类详情`"
                @click.stop="openClassificationEditor(group, 'description')">{{ group.displayName || group.name }}</el-button>
            </div>
            <span v-else class="catalog-category-empty">{{ rowClassifications(row) ? '未分类' : '分类待更新' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!adjustingClassification" label="环境" width="80" prop="environment" />
        <el-table-column v-if="!adjustingClassification" label="来源与接纳" min-width="175">
          <template #default="{ row }">
            <div class="api-source-cell">
              <span>{{ row.sourceKinds.map(sourceKindLabel).join('、') || '暂无来源' }}</span>
              <small class="api-subline">{{ row.activeSourceCount }} 条活动来源</small>
              <el-tag :type="statusTone(row.sourceStatus)" effect="light" size="small">
                {{ statusLabel(row.sourceStatus) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!adjustingClassification" label="连接 / 试调用" min-width="180">
          <template #default="{ row }">
            <span>{{ connectionLabel(row.connectionStatus) }}</span>
            <small class="api-subline">最近试调用：{{ latestTrialLabel(row) }}</small>
            <small class="api-subline">{{ row.latestInvocationAt ? '历史记录' : '仅显示当前账号' }}</small>
          </template>
        </el-table-column>
        <el-table-column v-if="!adjustingClassification" label="下一步" min-width="190">
          <template #default="{ row }">{{ nextAction(row) }}</template>
        </el-table-column>
        <el-table-column :label="adjustingClassification ? '归类' : '操作'" width="102" fixed="right" align="center">
          <template #default="{ row }">
            <span class="catalog-row-actions">
              <router-link v-if="!adjustingClassification" class="api-view-link catalog-row-action"
                :aria-label="`查看${row.httpMethod} ${row.routeTemplate}`" :to="detailLocation(row.id)">查看<el-icon aria-hidden="true"><ArrowRight /></el-icon></router-link>
              <el-button v-if="adjustingClassification" class="catalog-row-action" :aria-label="`归类${row.routeTemplate}`" @click="assignClassification(row)">归类</el-button>
            </span>
          </template>
        </el-table-column>
      </el-table>
      </template>
      </CatalogClassificationSections>
      <template #empty-actions>
        <el-button type="primary" @click="router.push('/registry/projects')">接入业务项目</el-button>
        <el-button v-if="currentProject" @click="openProject">查看项目接入与同步</el-button>
      </template>
    </BusinessCapabilityCatalogList>
    <CatalogClassificationEditor ref="classificationEditorRef" v-model:visible="classificationEditorVisible" :project-id="projectId || 0"
      :project-code="currentProject?.projectCode || undefined" :groups="classification.index.value?.groups || []"
      :group="classificationEditorGroup" :mode="classificationEditorMode" :subject="classificationEditorSubject || undefined" :subjects="classificationEditorSubjects"
      @updated="refreshClassifiedCatalog" @close-auto-focus="restoreClassificationFocus" />
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ElSelect } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Search } from '@element-plus/icons-vue'
import BusinessCapabilityCatalogList from '@/views/capability/components/BusinessCapabilityCatalogList.vue'
import CatalogClassificationSections from '@/views/capability/components/CatalogClassificationSections.vue'
import CatalogClassificationEditor from '@/views/capability/components/CatalogClassificationEditor.vue'
import { useCatalogClassification } from '@/views/capability/components/useCatalogClassification'
import { useCatalogClassificationMode } from '@/views/capability/composables/useCatalogClassificationMode'
import { classificationMemberships, classificationNavigationGroups } from '@/views/capability/components/catalogClassification'
import type { CatalogClassificationGroup, CatalogClassificationSubjectType } from '@/types/semanticDoc'
import ProjectScopeState from '@/components/common/ProjectScopeState.vue'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { useCatalogQuery } from '@/composables/useCatalogQuery'
import { BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS, HTTP_API_CATALOG_PATH, catalogQueryMatchesProject, httpApiDetailLocation, projectCapabilityWorkspaceLocation } from '@/views/capability/businessCapabilityRoutes'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_WRITE } from '@/auth/platformAccess'
import { listHttpApis } from '@/api/httpApi'
import type { HttpApiSourceStatus, HttpApiSummary } from '@/types/httpApi'

const route = useRoute()
const router = useRouter()
const isCatalogRoute = computed(() => route.matched.some(record => record.path === HTTP_API_CATALOG_PATH))
const query = useCatalogQuery('api', ['keyword', 'environment', 'method', 'sourceStatus', 'groupId'])
const scope = usePageProjectScope()
const projectId = computed(() => {
  const resolution = scope?.resolution?.value
  return resolution?.kind === 'project' ? resolution.project.id : scope?.requestParams.value?.projectId ?? null
})
const scopeReady = computed(() => !!scope?.canLoadData.value)
const classification = useCatalogClassification(projectId, scopeReady, 'HTTP_API')
const rows = ref<HttpApiSummary[]>([])
const total = ref(0)
const loading = ref(false)
const searchSubmissionRevision = ref(0)
const classificationSearchKey = computed(() => JSON.stringify([query.filters.value, searchSubmissionRevision.value]))
const error = ref('')
const composing = ref(false)
let sequence = 0
const methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
const environments = computed(() => Array.from(new Set((scope?.readableProjects.value || [])
  .filter((project) => project.id === projectId.value)
  .map((project) => project.environment).filter((value): value is string => !!value))))
const statuses: Array<{ value: HttpApiSourceStatus; label: string }> = [
  { value: 'DISCOVERED', label: '契约待接纳' }, { value: 'ACCEPTED', label: '契约已接纳' },
  { value: 'CONTRACT_DRIFT', label: '契约已变化' }, { value: 'CONFLICT', label: '来源冲突' },
  { value: 'SOURCE_UNCONFIRMED', label: '来源待确认' }, { value: 'SOURCE_MISSING', label: '来源已缺失' },
]
const page = query.page
const size = query.size
const hasFilters = query.hasFilters
const draft = query.draft
const currentProject = computed(() => scope?.readableProjects.value.find(project => project.id === projectId.value))
const canWriteClassification = computed(() => !!projectId.value && !!currentProject.value?.projectCode
  && hasPlatformResourcePermission(platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_WRITE, 'PROJECT', null, currentProject.value.projectCode))
const classificationMode = useCatalogClassificationMode()
const adjustingClassification = computed(() => !!classificationMode?.active.value && canWriteClassification.value)
const classificationFilterGroups = computed(() => classificationNavigationGroups(
  classification.index.value, query.filters.value.groupId, adjustingClassification.value,
))
const classificationSelectionUnavailable = computed(() => !!query.filters.value.groupId
  && (!classification.index.value || (query.filters.value.groupId !== 'unclassified'
    && !classificationFilterGroups.value.some(group => String(group.id) === query.filters.value.groupId))))
const selectedClassificationGroup = computed(() => classification.index.value?.groups
  .find(group => String(group.id) === query.filters.value.groupId))
const classificationFilterRef = ref<InstanceType<typeof ElSelect> | null>(null)
let classificationFocusContext: string | null = null
const classificationEditorRef = ref<InstanceType<typeof CatalogClassificationEditor> | null>(null)
const unregisterClassificationMode = classificationMode?.register(canWriteClassification, async () => {
  if (!classificationEditorVisible.value) return true
  return await classificationEditorRef.value?.requestClose() ?? false
})
const classificationEditorSubjects = ref<{ subjectType: CatalogClassificationSubjectType; subjectId: number; title: string }[]>([])
const classificationEditorVisible = ref(false)
const classificationEditorMode = ref<'description' | 'rename' | 'assign' | 'create'>('description')
const classificationEditorGroup = ref<CatalogClassificationGroup | null>(null)
const classificationEditorSubject = ref<{ subjectType: CatalogClassificationSubjectType; subjectId: number; title: string } | null>(null)
let confirmedProject = projectId.value
let confirmedSession = platformSessionId.value
async function load() {
  if (!isCatalogRoute.value) return
  const id = projectId.value
  const current = ++sequence
  rows.value = []; total.value = 0; error.value = ''
  if (!id || !scopeReady.value) { loading.value = false; return }
  loading.value = true
  try {
    const ungrouped = query.filters.value.groupId === 'unclassified' ? true : undefined
    const groupId = query.filters.value.groupId && !ungrouped ? Number(query.filters.value.groupId) : undefined
    if (groupId !== undefined && (!Number.isSafeInteger(groupId) || groupId <= 0)) throw new Error('Invalid group scope')
    const { data } = await listHttpApis({ projectId: id, environment: query.filters.value.environment || undefined,
      keyword: query.filters.value.keyword || undefined, method: query.filters.value.method || undefined,
      sourceStatus: query.filters.value.sourceStatus || undefined, groupId, ungrouped, current: page.value, size: size.value })
    if (current !== sequence) return
    if (!Array.isArray(data.records) || data.records.some(row => row.projectId !== id || !Number.isSafeInteger(row.id) || row.id <= 0)
      || !Number.isSafeInteger(data.total) || data.total < data.records.length) {
      throw new Error('Invalid HTTP API owner page')
    }
    rows.value = data.records
    total.value = data.total
    return true
  } catch {
    if (current !== sequence) return
    error.value = 'API 目录加载失败；请检查项目访问权限或重试'
    return false
  } finally { if (current === sequence) loading.value = false }
}
function applyFilters() {
  if (composing.value) return
  searchSubmissionRevision.value += 1
  void query.apply()
}
function changePage(next: number) { void query.changePage(next) }
function changeSize(next: number) { void query.changeSize(next) }
function detailLocation(id: number) { return httpApiDetailLocation(id, route.query) }
function apiIdentity(row: HttpApiSummary) { return row.id }
function rowClassifications(row: HttpApiSummary): CatalogClassificationGroup[] | null {
  const index = classification.index.value
  if (!index || index.projectId !== row.projectId || !index.memberships.some(member => member.subjectId === row.id)) return null
  return classificationMemberships(index, row.id)
}
function selectClassification(value: string) {
  void query.applyField('groupId', value)
}
function openSelectedClassification(mode: 'description' | 'rename') {
  const group = selectedClassificationGroup.value
  if (!group) return
  classificationFilterRef.value?.blur()
  openClassificationEditor(group, mode)
  if (classificationEditorVisible.value) classificationFocusContext = classificationContextKey()
}
function classificationContextKey() {
  return JSON.stringify([projectId.value, platformSessionId.value, route.path])
}
async function restoreClassificationFocus() {
  const context = classificationFocusContext
  classificationFocusContext = null
  await nextTick()
  if (!context || context !== classificationContextKey() || !scopeReady.value
    || classification.loading.value || !classification.index.value || !classificationFilterRef.value) return
  classificationFilterRef.value.focus()
}
function openClassificationEditor(group: CatalogClassificationGroup, mode: 'description' | 'rename') {
  if (!projectId.value || group.projectId !== projectId.value || (mode === 'rename' && !adjustingClassification.value)) return
  classificationFocusContext = null
  classificationEditorSubjects.value = []; classificationEditorGroup.value = group; classificationEditorSubject.value = null; classificationEditorMode.value = mode; classificationEditorVisible.value = true
}
function createClassification() {
  if (!adjustingClassification.value || !projectId.value || !classification.index.value) return
  classificationEditorSubjects.value = []; classificationEditorGroup.value = null; classificationEditorSubject.value = null
  classificationEditorMode.value = 'create'; classificationEditorVisible.value = true
}
function assignClassification(row: HttpApiSummary) {
  if (!adjustingClassification.value || row.projectId !== projectId.value || !classification.index.value) return
  classificationEditorSubjects.value = []; classificationEditorGroup.value = null; classificationEditorMode.value = 'assign'
  classificationEditorSubject.value = { subjectType: 'HTTP_API', subjectId: row.id, title: `${row.httpMethod} ${row.routeTemplate}` }
  classificationEditorVisible.value = true
}
function assignManyClassifications(selected: HttpApiSummary[]) {
  if (!adjustingClassification.value || !classification.index.value || !selected.length || selected.some(row => row.projectId !== projectId.value)) return
  classificationEditorSubject.value = null; classificationEditorGroup.value = null; classificationEditorMode.value = 'assign'
  classificationEditorSubjects.value = selected.map(row => ({ subjectType: 'HTTP_API', subjectId: row.id, title: `${row.httpMethod} ${row.routeTemplate}` }))
  classificationEditorVisible.value = true
}
async function refreshClassifiedCatalog() {
  await classification.refresh()
  const selected = query.filters.value.groupId
  if (selected && selected !== 'unclassified' && classification.index.value
    && !classification.index.value.groups.some(group => String(group.id) === selected)) {
    selectClassification(''); return
  }
  await load()
}
watch([() => route.query.classificationAction, () => route.query.classificationId, classification.index], () => {
  if (!scopeReady.value || !projectId.value || !classification.index.value) return
  const hasAction = route.query.classificationAction !== undefined
  const group = classification.index.value.groups.find(item => String(item.id) === route.query.classificationId)
  if (!hasAction && group) openClassificationEditor(group, 'description')
  else if (!hasAction && route.query.classificationId === undefined) return
  const next = { ...route.query }; delete next.classificationAction; delete next.classificationId
  void router.replace({ path: route.path, query: next, hash: route.hash })
}, { immediate: true })
function openProject() {
  const project = currentProject.value
  void router.push(project ? projectCapabilityWorkspaceLocation(project, 'sources', 'http-apis') : '/registry/projects')
}
function statusLabel(status: HttpApiSourceStatus) { return statuses.find((item) => item.value === status)?.label || status }
function statusTone(status: HttpApiSourceStatus) {
  return status === 'ACCEPTED' ? 'success' : status === 'DISCOVERED' ? 'warning' : 'danger'
}
function sourceKindLabel(kind: string) {
  return ({ OPENAPI_SCAN: 'OpenAPI', CONTROLLER_SCAN: 'Controller', STARTER_MVC: 'Starter MVC',
    API_MARKET_OPERATION: 'API 市场' } as Record<string, string>)[kind] || kind
}
function nextAction(row: HttpApiSummary) {
  if (!row.sourceConfirmed) return row.sourceReason || '重新扫描或同步来源'
  if (row.sourceStatus === 'DISCOVERED' || row.sourceStatus === 'CONTRACT_DRIFT') return '查看差异并接纳契约'
  if (row.sourceStatus === 'ACCEPTED') return row.connectionStatus === 'UNCONFIGURED'
    ? '配置接入' : '查看连接与试调用条件'
  return '检查来源状态'
}
function connectionLabel(status: HttpApiSummary['connectionStatus']) {
  const labels: Record<string, string> = { SAVED: '连接已保存', UNCONFIGURED: '连接未配置',
    UNAVAILABLE: '暂不可查询' }
  return labels[status || 'UNAVAILABLE']
}
function latestTrialLabel(row: HttpApiSummary) {
  if (row.connectionStatus === 'UNAVAILABLE') return '暂不可查询'
  if (!row.latestInvocationStatus) return '暂无本账号记录'
  if (row.latestInvocationStatus === 'SUCCEEDED') return `HTTP ${row.latestHttpStatus || '2xx'} 成功`
  if (row.latestInvocationStatus === 'HTTP_FAILED') return `HTTP ${row.latestHttpStatus || '非 2xx'} 失败`
  return ({ UNKNOWN: '结果未知', NOT_DISPATCHED: '未派发', ACCEPTED: '已建立',
    DISPATCHING: '正在调用' } as Record<string, string>)[row.latestInvocationStatus] || '调用已结束'
}
watch([query.requestKey, () => scope?.requestKey.value, projectId, platformSessionId], () => {
  if (!isCatalogRoute.value) return
  sequence += 1; rows.value = []; total.value = 0; error.value = ''; loading.value = false
  const accountChanged = confirmedSession !== platformSessionId.value
  if (accountChanged) query.resetDraft()
  if (!scope?.canLoadData.value || !projectId.value) return
  const changed = confirmedProject !== null && (confirmedProject !== projectId.value || confirmedSession !== platformSessionId.value)
  const preserveAddressedFilters = confirmedSession === platformSessionId.value
    && catalogQueryMatchesProject(route.query, projectId.value, scope?.requestParams.value?.projectCode)
  if (changed) query.resetDraft()
  if (projectId.value) { confirmedProject = projectId.value; confirmedSession = platformSessionId.value }
  if (changed && !preserveAddressedFilters && BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS.some(key => route.query[key] !== undefined)) {
    classificationEditorVisible.value = false; classificationEditorGroup.value = null; classificationEditorSubject.value = null
    void query.clear(['groupId'], BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS); return
  }
  if (changed) {
    if (!preserveAddressedFilters) query.draft.groupId = ''
    classificationEditorVisible.value = false; classificationEditorGroup.value = null; classificationEditorSubject.value = null
  }
  void load()
}, { flush: 'sync' })
onMounted(() => { void load() })
onBeforeUnmount(() => { unregisterClassificationMode?.(); sequence += 1 })
</script>

<style scoped lang="scss">
.http-api-catalog { display: flex; flex: 1; min-width: 0; min-height: 0; flex-direction: column; gap: var(--layout-page-gap); }
.api-identity { display: flex; align-items: flex-start; gap: 10px; min-width: 0; color: var(--text-primary); text-decoration: none; }
.api-identity:not(.api-identity--static):hover .api-route { color: var(--brand-active); text-decoration: underline; }
.api-identity:focus-visible, .api-view-link:focus-visible { outline: 2px solid var(--brand-active); outline-offset: 3px; }
.api-method { flex: 0 0 auto; border: 1px solid color-mix(in srgb, var(--brand-primary) 18%, var(--border-divider)); border-radius: var(--radius-sm);
  padding: 5px 8px; color: var(--brand-active); background: var(--brand-soft-bg); font: 700 11px/1.3 var(--font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace); }
.api-route { min-width: 0; overflow-wrap: anywhere; font: 600 13px/1.8 var(--font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace); }
.api-subline { display: block; margin: 3px 0 0; color: var(--text-muted); font-size: 12px; overflow-wrap: anywhere; }
.api-qualified-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.api-source-cell { display: grid; justify-items: start; gap: 5px; }
.api-source-cell .api-subline { margin: 0; }
.api-compact-summary { display: none; }
.classification-filter-actions { display: grid; gap: 4px; }
.classification-filter-actions :deep(.el-button) { justify-content: flex-start; margin: 0; }
@media (max-width: 960px) {
  .api-compact-summary { display: grid; gap: 4px; margin: 8px 0 0; font-size: 12px; line-height: 1.45; }
  .api-compact-summary div { display: flex; gap: 7px; min-width: 0; }
  .api-compact-summary dt { flex: 0 0 72px; color: var(--text-muted); }
  .api-compact-summary dd { min-width: 0; margin: 0; color: var(--text-primary); overflow-wrap: anywhere; }
  .api-table .api-subline { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
  .api-qualified-name { white-space: normal; }
}
</style>

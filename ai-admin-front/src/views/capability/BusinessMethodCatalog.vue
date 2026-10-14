<template>
  <section class="capability-catalog-page" aria-label="Java 业务方法目录">
    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      :title="listError"
      class="catalog-load-alert"
    >
      <template #default>
        <p v-if="listErrorDiagnostic" class="catalog-load-diagnostic">{{ listErrorDiagnostic }}</p>
        <el-button link type="primary" @click="loadMethods">重新加载{{ catalogCopy.title }}</el-button>
      </template>
    </el-alert>

    <ProjectScopeState
      v-if="!businessScopeReady"
      title="业务方法范围尚未确认"
      :message="businessScopeMessage"
      :status="businessScopeStatus"
      :can-retry="businessScopeCanRetry"
      :retrying="businessScopeRetrying"
      :retry-label="businessScopeRetryLabel"
      @retry="retryBusinessScope"
    />

    <BusinessCapabilityCatalogList
      v-else
      :current-page="currentPage"
      :page-size="pageSize"
      :loading="loading"
      :composing="composing"
      :empty="listState === 'ready' && methods.length === 0"
      :total="listState === 'ready' ? total : 0"
      :has-filters="hasActiveFilters"
      :empty-description="hasActiveFilters ? '没有符合当前条件的业务方法' : `${catalogCopy.title}还是空的`"
      empty-hint="请先在当前项目声明业务方法并完成 SDK 同步；已接纳的声明会自动出现在这里。"
      @query="applyFilters"
      @page-change="query.changePage"
      @size-change="query.changeSize"
    >
      <template #filters>
        <el-input
          v-model="query.draft.keyword"
          class="filter-control is-keyword"
          :prefix-icon="Search"
          placeholder="搜索 Java 业务方法名称、稳定标识或说明"
          aria-label="搜索 Java 业务方法"
          clearable
          @compositionstart="composing = true"
          @compositionend="composing = false"
          @clear="applyFilters"
        />
        <el-select
          v-if="showProjectFilter"
          :model-value="projectFilterValue"
          class="filter-control is-project"
          aria-label="项目筛选"
          placeholder="全部项目"
          filterable
          @change="selectProject($event || '')"
        >
          <el-option value="" label="全部项目" />
          <el-option v-for="project in readableProjects" :key="project.id"
            :value="String(project.id)" :label="project.name" />
          <el-option v-if="invalidProjectFilter" :value="projectFilterValue"
            label="所选项目不可用" disabled />
        </el-select>
        <el-select
          :model-value="query.filters.value.groupId"
          class="filter-control is-classification"
          :placeholder="allCatalogProjects ? '选择项目后按分类筛选' : '业务分类'"
          aria-label="业务分类筛选，数量为项目全量"
          :loading="classification.loading.value"
          :disabled="allCatalogProjects || invalidProjectFilter || classification.loading.value || !classification.index.value"
          clearable
          @change="selectClassification($event || '')"
        >
          <template v-if="classification.index.value">
            <el-option value="" :label="`全部能力（${classification.index.value.memberships.length}）`" />
            <el-option v-for="group in classificationFilterGroups" :key="group.id" :value="String(group.id)"
              :label="`${group.displayName || group.name}（${group.memberCount}）`" />
            <el-option value="unclassified" :label="`未分类（${classification.index.value.ungroupedCount}）`" />
          </template>
          <el-option v-if="classificationSelectionUnavailable" :value="query.filters.value.groupId"
            :label="query.filters.value.groupId === 'unclassified' ? '未分类（待更新）' : classification.index.value ? '所选分类已不可用' : '所选分类待更新'" disabled />
        </el-select>
        <el-select
          v-model="query.draft.enabled"
          class="filter-control is-status"
          placeholder="启用状态"
          aria-label="启用状态筛选"
          clearable
          @change="query.applyField('enabled')"
        >
          <el-option label="已启用" value="true" />
          <el-option label="已停用" value="false" />
        </el-select>
      </template>

      <CatalogClassificationSections :rows="methods" :identity="methodIdentity" :index="classification.index.value"
        :loading="classification.loading.value" :error="classification.error.value" :selected="query.filters.value.groupId"
        :all-projects="allCatalogProjects" :can-write="canWriteClassification" :adjusting="adjustingClassification" :search-key="classificationSearchKey" layout="flat" :show-navigation="false" @select="selectClassification"
        @retry="classification.refresh" @edit="openClassificationEditor" @assign="assignManyClassifications" @create="createClassification">
      <template #default="{ rows: pageRows, isSelected, toggleRow }">
      <el-table
        v-if="methods.length"
        :data="pageRows"
        row-key="assetId"
        class="capability-table"
        :class="{ 'capability-table--adjusting': adjustingClassification }"
        @row-click="handleMethodRowClick"
      >
        <el-table-column v-if="adjustingClassification" width="56" align="center">
          <template #default="{ row }"><span @click.stop><el-checkbox :model-value="isSelected(row)"
            :aria-label="`选择${capabilityDisplayName(row)}`" @change="toggleRow(row, !!$event)" /></span></template>
        </el-table-column>
        <el-table-column :label="'业务方法'" min-width="280">
          <template #default="{ row }">
            <div class="capability-cell catalog-asset-cell">
              <span class="catalog-asset-icon" aria-hidden="true">ƒ</span>
              <div class="capability-cell__copy">
                <div class="catalog-asset-heading">
                  <el-button v-if="!adjustingClassification" link class="capability-cell__name" @click.stop="openMethodDetail(row)">
                    {{ capabilityDisplayName(row) }}
                  </el-button>
                  <strong v-else class="capability-cell__name-static">{{ capabilityDisplayName(row) }}</strong>
                  <small v-if="!adjustingClassification && !showProjectFilter" class="catalog-method-meta">
                    {{ capabilitySourceLabel(row.source, row.sourceLocation) }}
                  </small>
                </div>
                <span class="catalog-asset-description">{{ capabilityDescription(row) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="adjustingClassification ? '当前归属' : '业务分类'" min-width="170" width="220">
          <template #default="{ row }">
            <div v-if="rowClassifications(row)?.length" class="catalog-category-cell">
              <el-button v-for="group in rowClassifications(row)" :key="group.id" class="catalog-category-link"
                :aria-label="`查看${group.displayName}分类详情`" :aria-busy="classificationDetailLoading === `${row.projectId}:${group.id}`"
                :disabled="!!classificationDetailLoading"
                @click.stop="openRowClassification(row, group)">{{ group.displayName }}</el-button>
            </div>
            <span v-else class="catalog-category-empty">{{ rowClassifications(row) ? '未分类' : '分类待更新' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!adjustingClassification" label="操作性质" width="112">
          <template #default="{ row }">
            <StatusTag
              :label="capabilitySideEffectLabel(row.sideEffect)"
              :tone="capabilitySideEffectTone(row.sideEffect)"
              class="catalog-side-effect"
            />
          </template>
        </el-table-column>
        <el-table-column v-if="!adjustingClassification" label="状态" width="132">
          <template #default="{ row }">
            <el-tooltip :content="`${businessMethodReadiness(row).title}：${businessMethodReadiness(row).description}`" :trigger="['hover', 'focus']" placement="top">
            <button type="button" class="catalog-method-status"
              :aria-label="`${businessMethodReadiness(row).label}：${businessMethodReadiness(row).title}。${businessMethodReadiness(row).description}`" @click.stop>
            <StatusTag
              :label="businessMethodReadiness(row).label"
              :tone="businessMethodReadiness(row).tone"
            />
            </button>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column :label="adjustingClassification ? '归类' : '操作'" width="102" fixed="right" align="center">
          <template #default="{ row }">
            <span class="catalog-row-actions" @click.stop>
              <el-button v-if="!adjustingClassification" class="catalog-row-action"
                :aria-label="`查看${capabilityDisplayName(row)}`" @click="openMethodDetail(row)">查看<el-icon aria-hidden="true"><ArrowRight /></el-icon></el-button>
              <el-button v-if="adjustingClassification" class="catalog-row-action" :aria-label="`归类${capabilityDisplayName(row)}`" @click="assignClassification(row)">归类</el-button>
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

    <BusinessMethodDetailDialog
      v-model="detailVisible"
      :method="selectedMethod"
      :fresh-detail="selectedMethodFresh"
      :context-key="detailContextKey"
      @refreshed="refreshSelectedMethod"
    />
    <CatalogClassificationEditor ref="classificationEditorRef" v-model:visible="classificationEditorVisible" :project-id="scopeProjectId || 0"
      :project-code="currentProject?.projectCode || undefined" :groups="classification.index.value?.groups || []"
      :group="classificationEditorGroup" :mode="classificationEditorMode" :subject="classificationEditorSubject || undefined" :subjects="classificationEditorSubjects"
      @updated="refreshClassifiedCatalog" />
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Search } from '@element-plus/icons-vue'
import BusinessCapabilityCatalogList from './components/BusinessCapabilityCatalogList.vue'
import BusinessMethodDetailDialog from './components/BusinessMethodDetailDialog.vue'
import CatalogClassificationSections from './components/CatalogClassificationSections.vue'
import CatalogClassificationEditor from './components/CatalogClassificationEditor.vue'
import { useCatalogClassification } from './components/useCatalogClassification'
import { useCatalogClassificationMode } from './composables/useCatalogClassificationMode'
import type { CatalogClassificationGroup, CatalogClassificationSubjectType } from '@/types/semanticDoc'
import { classificationMemberships, classificationNavigationGroups } from './components/catalogClassification'
import { businessMethodReadiness } from './businessMethodDetail'
import ProjectScopeState, { type ProjectScopeStateStatus } from '@/components/common/ProjectScopeState.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { getBusinessMethod, getBusinessMethods } from '@/api/businessMethod'
import { BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS, catalogQueryMatchesProject, projectCapabilityWorkspaceLocation } from './businessCapabilityRoutes'
import { useCatalogQuery } from '@/composables/useCatalogQuery'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { useProjectStore } from '@/store/project'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_WRITE } from '@/auth/platformAccess'
import { isBusinessMethodInfo, type BusinessMethodInfo } from '@/types/businessMethod'
import {
  capabilityDescription,
  capabilityDisplayName,
  capabilitySideEffectLabel,
  capabilitySideEffectTone,
  capabilitySourceLabel,
} from './capabilityGovernance'

const router = useRouter()
const route = useRoute()
let linkedDetailSequence = 0
const projectStore = useProjectStore()
const pageProjectScope = usePageProjectScope()
const query = useCatalogQuery('method', ['keyword', 'enabled', 'groupId', 'projectId'])
type LoadState = 'loading' | 'ready' | 'error'
const catalogCopy = { title: 'Java 业务方法' }
const businessScopeReady = computed(() => Boolean(pageProjectScope?.canLoadData.value) && !localProjectPending.value)
const businessScopeStatus = computed<ProjectScopeStateStatus>(() => (
  localProjectPending.value ? 'pending' : pageProjectScope?.status.value ?? 'pending'
))
const businessScopeMessage = computed(() => (
  localProjectPending.value ? '正在确认所选项目，请稍候。'
    : pageProjectScope?.feedbackMessage.value || '正在确认项目范围，确认后再读取业务方法。'
))
const businessScopeCanRetry = computed(() => {
  const action = pageProjectScope?.recoveryAction.value
  return action === 'retry-catalog' || action === 'retry-normalization'
})
const businessScopeRetrying = computed(() => pageProjectScope?.isCatalogLoading.value ?? false)
const businessScopeRetryLabel = computed(() => pageProjectScope?.recoveryLabel.value || '重试范围确认')
const sidebarProjectId = computed(() => {
  const resolution = pageProjectScope?.resolution?.value
  return resolution?.kind === 'project' ? resolution.project.id : pageProjectScope?.requestParams.value?.projectId ?? null
})
const localProjectPending = computed(() => sidebarProjectId.value === null
  && route.query.methodProjectId !== undefined && route.query.methodProjectId !== ''
  && !!pageProjectScope?.isCatalogLoading.value
  && !(pageProjectScope?.readableProjects?.value ?? []).some(project => String(project.id) === route.query.methodProjectId))
const showProjectFilter = computed(() => businessScopeReady.value && sidebarProjectId.value === null)
const readableProjects = computed(() => pageProjectScope?.readableProjects?.value ?? [])
const projectFilterValue = computed(() => {
  const value = route.query.methodProjectId
  return typeof value === 'string' ? value : value === undefined ? '' : 'invalid-project'
})
const filterProject = computed(() => showProjectFilter.value
  ? readableProjects.value.find(project => String(project.id) === projectFilterValue.value) : undefined)
const invalidProjectFilter = computed(() => showProjectFilter.value && route.query.methodProjectId !== undefined
  && (typeof route.query.methodProjectId !== 'string' || !!projectFilterValue.value && !filterProject.value))
const scopeProjectId = computed(() => sidebarProjectId.value ?? filterProject.value?.id ?? null)
const allCatalogProjects = computed(() => businessScopeReady.value && scopeProjectId.value === null)
const currentProject = computed(() => projectStore.projects.find(project => project.id === scopeProjectId.value))
const sidebarScopeContextKey = computed(() => JSON.stringify([platformSessionId.value, sidebarProjectId.value,
  pageProjectScope?.requestParams.value?.projectCode || '']))
const detailContextKey = computed(() => JSON.stringify([sidebarScopeContextKey.value, scopeProjectId.value,
  showProjectFilter.value ? projectFilterValue.value : '']))

const loading = ref(false)
const listState = ref<LoadState>('loading')
const listError = ref('')
const listErrorDiagnostic = ref('')
const methods = ref<BusinessMethodInfo[]>([])
const total = ref(0)
const classificationScopeReady = computed(() => businessScopeReady.value && !invalidProjectFilter.value)
const classification = useCatalogClassification(scopeProjectId, classificationScopeReady, 'BUSINESS_METHOD')
const searchSubmissionRevision = ref(0)
const classificationSearchKey = computed(() => JSON.stringify([query.filters.value, searchSubmissionRevision.value]))
let disposed = false
const ownerPath = route.path
const canWriteClassification = computed(() => !!sidebarProjectId.value && !!currentProject.value?.projectCode
  && hasPlatformResourcePermission(platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_WRITE, 'PROJECT', null, currentProject.value.projectCode))
const classificationMode = useCatalogClassificationMode()
const adjustingClassification = computed(() => !!classificationMode?.active.value && canWriteClassification.value)
const classificationFilterGroups = computed(() => classificationNavigationGroups(
  classification.index.value, query.filters.value.groupId, adjustingClassification.value,
))
const classificationSelectionUnavailable = computed(() => !!query.filters.value.groupId
  && (!classification.index.value || (query.filters.value.groupId !== 'unclassified'
    && !classificationFilterGroups.value.some(group => String(group.id) === query.filters.value.groupId))))
const classificationEditorRef = ref<InstanceType<typeof CatalogClassificationEditor> | null>(null)
const unregisterClassificationMode = classificationMode?.register(canWriteClassification, async () => {
  if (!classificationEditorVisible.value) return true
  return await classificationEditorRef.value?.requestClose() ?? false
})
const classificationEditorSubjects = ref<{ subjectType: CatalogClassificationSubjectType; subjectId: number; title: string }[]>([])
const classificationEditorVisible = ref(false)
const classificationEditorMode = ref<'description' | 'rename' | 'assign' | 'create'>('description')
const classificationEditorGroup = ref<CatalogClassificationGroup | null>(null)
const classificationDetailLoading = ref('')
const classificationEditorSubject = ref<{ subjectType: CatalogClassificationSubjectType; subjectId: number; title: string } | null>(null)
const currentPage = query.page
const pageSize = query.size
const composing = ref(false)
const requestSequence = ref(0)

const detailVisible = ref(false)
const selectedMethod = ref<BusinessMethodInfo | null>(null)
const selectedMethodFresh = ref(false)
const catalogNormalizationPending = ref(false)
const catalogReadRevision = ref(0)

const hasActiveFilters = query.hasFilters
let confirmedScopeContext = businessScopeReady.value ? sidebarScopeContextKey.value : null
let confirmedScopeSession = platformSessionId.value

watch([businessScopeReady, sidebarProjectId, sidebarScopeContextKey, platformSessionId], () => {
  invalidatePendingCatalogRequests()
  if (confirmedScopeSession !== platformSessionId.value) query.resetDraft()
  if (!businessScopeReady.value) { catalogReadRevision.value++; clearSelectedMethod(); clearListForUnconfirmedScope(); return }
  const contextChanged = confirmedScopeContext !== null && confirmedScopeContext !== sidebarScopeContextKey.value
  const preserveAddressedFilters = confirmedScopeSession === platformSessionId.value
    && catalogQueryMatchesProject(route.query, sidebarProjectId.value, pageProjectScope?.requestParams.value?.projectCode)
  confirmedScopeContext = sidebarScopeContextKey.value
  confirmedScopeSession = platformSessionId.value
  if (contextChanged) {
    query.resetDraft()
    clearSelectedMethod()
    classificationEditorVisible.value = false; classificationEditorGroup.value = null; classificationEditorSubject.value = null
    if (!preserveAddressedFilters && BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS.some(key => route.query[key] !== undefined)) {
      catalogNormalizationPending.value = true
      void query.clear(['groupId', 'projectId'], BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS).finally(() => { catalogNormalizationPending.value = false }); return
    }
    if (!preserveAddressedFilters) query.draft.groupId = ''
  }
}, { flush: 'sync' })
watch(detailContextKey, () => {
  invalidatePendingCatalogRequests()
  clearSelectedMethod()
  classificationEditorVisible.value = false
  classificationEditorGroup.value = null
  classificationEditorSubject.value = null
}, { flush: 'sync' })
const catalogLoadKey = computed(() => businessScopeReady.value && !catalogNormalizationPending.value
  ? JSON.stringify([detailContextKey.value, query.requestKey.value, catalogReadRevision.value]) : null)
watch(catalogLoadKey, key => { if (key) void loadMethods() }, { immediate: true, flush: 'post' })
onBeforeUnmount(() => { unregisterClassificationMode?.(); disposed = true; linkedDetailSequence += 1; invalidatePendingCatalogRequests() })
watch([() => route.query.methodDetail, () => route.query.methodAssetId, () => route.query.subjectKey,
  () => route.query.subjectId, () => route.query.subjectType, scopeProjectId, businessScopeReady, platformSessionId], async () => {
  const current = ++linkedDetailSequence
  const reference = route.query.subjectType === 'BUSINESS_METHOD' ? route.query.subjectKey : route.query.methodDetail
  const name = typeof reference === 'string' ? reference.trim() : ''
  if (!name || !businessScopeReady.value || !scopeProjectId.value) return
  const expectedProject = scopeProjectId.value
  const assetReference = route.query.subjectType === 'BUSINESS_METHOD' ? route.query.subjectId : route.query.methodAssetId
  const expectedAsset = Number(assetReference)
  try {
    const { data } = await getBusinessMethod(name)
    if (current !== linkedDetailSequence) return
    if (!isBusinessMethodInfo(data) || data.projectId !== expectedProject || (data.qualifiedName !== name && data.name !== name)
      || (assetReference !== undefined && (!Number.isSafeInteger(expectedAsset) || expectedAsset <= 0 || data.assetId !== expectedAsset))) throw new Error('Invalid asset owner')
    openMethodDetail(data, true)
  } catch { if (current === linkedDetailSequence) ElMessage.error('所链接的业务方法无法确认，请核对项目与来源。') }
}, { immediate: true })

async function loadMethods() {
  if (!businessScopeReady.value) return
  const sequence = ++requestSequence.value
  loading.value = true
  listState.value = 'loading'
  listError.value = ''
  listErrorDiagnostic.value = ''
  methods.value = []
  total.value = 0
  if (invalidProjectFilter.value) {
    loading.value = false
    listState.value = 'error'
    listError.value = '所选项目不存在或已不可访问，请重新选择项目。'
    return false
  }
  try {
    const projectId = activeProjectId()
    const ungrouped = query.filters.value.groupId === 'unclassified' ? true : undefined
    const groupId = query.filters.value.groupId && !ungrouped ? Number(query.filters.value.groupId) : undefined
    if ((ungrouped && !projectId) || (groupId !== undefined && (!projectId || !Number.isSafeInteger(groupId) || groupId <= 0))) throw new Error('Invalid group scope')
    const { data } = await getBusinessMethods({
      current: currentPage.value, size: pageSize.value, keyword: query.filters.value.keyword || undefined,
      projectId, groupId, ungrouped, enabled: query.filters.value.enabled === 'true' ? true : query.filters.value.enabled === 'false' ? false : undefined,
    })
    if (sequence !== requestSequence.value) return
    if (!Array.isArray(data?.records) || !data.records.every(method => isBusinessMethodInfo(method)
      && (projectId === undefined || method.projectId === projectId))
      || !Number.isSafeInteger(data.total) || data.total < data.records.length) throw new Error('Invalid business method owner response')
    methods.value = data.records
    total.value = data.total
    listState.value = 'ready'
    return true
  } catch (error) {
    if (sequence !== requestSequence.value) return
    methods.value = []
    total.value = 0
    listState.value = 'error'
    listError.value = catalogLoadError(error)
    return false
  } finally {
    if (sequence === requestSequence.value) loading.value = false
  }
}

async function applyFilters() {
  if (composing.value) return
  searchSubmissionRevision.value += 1
  await query.applyFields(['keyword', 'enabled', 'groupId'])
}

function selectProject(value: string) {
  query.draft.projectId = value
  query.draft.groupId = ''
  void query.applyFields(['projectId', 'groupId'], [
    'subjectType', 'subjectId', 'subjectKey', 'methodDetail', 'methodAssetId', 'classificationId', 'classificationAction',
  ])
}

function openMethodDetail(row: BusinessMethodInfo, fresh = false) {
  selectedMethodFresh.value = fresh
  selectedMethod.value = row
  detailVisible.value = true
}
function handleMethodRowClick(row: BusinessMethodInfo, _column: unknown, event: Event) {
  if (event.target instanceof Element && event.target.closest('.catalog-category-cell')) return
  if (!adjustingClassification.value) openMethodDetail(row)
}

function refreshSelectedMethod(tool: BusinessMethodInfo) {
  if (!detailVisible.value || selectedMethod.value?.assetId !== tool.assetId || selectedMethod.value?.projectId !== tool.projectId) return
  const index = methods.value.findIndex(item => item.assetId === tool.assetId)
  if (index >= 0) methods.value[index] = tool
  selectedMethod.value = tool
}

function methodIdentity(row: BusinessMethodInfo) { return row.assetId }
function rowClassifications(row: BusinessMethodInfo): Array<{ id: number; displayName: string }> | null {
  if (row.classifications === null) return null
  if (row.classifications !== undefined) return row.classifications
  const index = classification.index.value
  if (!index || index.projectId !== row.projectId || !index.memberships.some(member => member.subjectId === row.assetId)) return null
  return classificationMemberships(index, row.assetId).map(group => ({ id: group.id, displayName: group.displayName || group.name }))
}
async function openRowClassification(row: BusinessMethodInfo, group: { id: number; displayName: string }) {
  if (classificationDetailLoading.value) return
  if (scopeProjectId.value !== row.projectId) {
    const next = { ...route.query }
    for (const key of BUSINESS_CAPABILITY_SCOPE_QUERY_KEYS) delete next[key]
    delete next.scope
    void router.push({ path: route.path, query: { ...next, projectId: String(row.projectId),
      projectCode: row.projectCode, classificationId: String(group.id) }, hash: route.hash })
    return
  }
  const key = `${row.projectId}:${group.id}`
  const session = platformSessionId.value
  classificationDetailLoading.value = key
  try {
    if (!classification.index.value?.groups.some(item => item.id === group.id)) await classification.refresh()
    if (disposed || route.path !== ownerPath || session !== platformSessionId.value || scopeProjectId.value !== row.projectId) return
    const ownerGroup = classification.index.value?.groups.find(item => item.id === group.id && item.projectId === row.projectId)
    if (!ownerGroup) {
      ElMessage.warning('分类详情暂不可用，请重试分类')
      return
    }
    openClassificationEditor(ownerGroup, 'description')
  } finally { if (classificationDetailLoading.value === key) classificationDetailLoading.value = '' }
}
function selectClassification(value: string) {
  void query.applyField('groupId', value)
}
function openClassificationEditor(group: CatalogClassificationGroup, mode: 'description' | 'rename') {
  if (!scopeProjectId.value || group.projectId !== scopeProjectId.value || (mode === 'rename' && !adjustingClassification.value)) return
  classificationEditorSubjects.value = []; classificationEditorGroup.value = group; classificationEditorSubject.value = null; classificationEditorMode.value = mode; classificationEditorVisible.value = true
}
function createClassification() {
  if (!adjustingClassification.value || !scopeProjectId.value || !classification.index.value) return
  classificationEditorSubjects.value = []; classificationEditorGroup.value = null; classificationEditorSubject.value = null
  classificationEditorMode.value = 'create'; classificationEditorVisible.value = true
}
function assignClassification(row: BusinessMethodInfo) {
  if (!adjustingClassification.value || row.projectId !== scopeProjectId.value || !classification.index.value) return
  classificationEditorSubjects.value = []; classificationEditorGroup.value = null; classificationEditorMode.value = 'assign'
  classificationEditorSubject.value = { subjectType: 'BUSINESS_METHOD', subjectId: row.assetId, title: capabilityDisplayName(row) }
  classificationEditorVisible.value = true
}
function assignManyClassifications(selected: BusinessMethodInfo[]) {
  if (!adjustingClassification.value || !classification.index.value || !selected.length || selected.some(row => row.projectId !== scopeProjectId.value)) return
  classificationEditorSubject.value = null; classificationEditorGroup.value = null; classificationEditorMode.value = 'assign'
  classificationEditorSubjects.value = selected.map(row => ({ subjectType: 'BUSINESS_METHOD', subjectId: row.assetId, title: capabilityDisplayName(row) }))
  classificationEditorVisible.value = true
}
async function refreshClassifiedCatalog() {
  await classification.refresh()
  const selected = query.filters.value.groupId
  if (selected && selected !== 'unclassified' && classification.index.value
    && !classification.index.value.groups.some(group => String(group.id) === selected)) {
    selectClassification(''); return
  }
  await loadMethods()
}
watch([() => route.query.classificationAction, () => route.query.classificationId, classification.index], () => {
  if (!businessScopeReady.value || !scopeProjectId.value || !classification.index.value) return
  const hasAction = route.query.classificationAction !== undefined
  const group = classification.index.value.groups.find(item => String(item.id) === route.query.classificationId)
  if (!hasAction && group) openClassificationEditor(group, 'description')
  else if (!hasAction && route.query.classificationId === undefined) return
  const next = { ...route.query }; delete next.classificationAction; delete next.classificationId
  void router.replace({ path: route.path, query: next, hash: route.hash })
}, { immediate: true })

function activeProjectId() { return scopeProjectId.value ?? undefined }
function openProject() {
  const project = currentProject.value
  void router.push(project ? projectCapabilityWorkspaceLocation(project, 'sources', 'java-methods') : '/registry/projects')
}

function invalidatePendingCatalogRequests() {
  requestSequence.value += 1
}

function clearListForUnconfirmedScope() {
  loading.value = false
  listState.value = 'ready'
  listError.value = ''
  listErrorDiagnostic.value = ''
  methods.value = []
  total.value = 0
  clearSelectedMethod()
}

function clearSelectedMethod() {
  selectedMethodFresh.value = false
  detailVisible.value = false
  selectedMethod.value = null
}

function catalogLoadError(error: unknown) {
  const rawStatus = (error as { response?: { status?: unknown } })?.response?.status
  const status = typeof rawStatus === 'number' ? rawStatus : Number(rawStatus)
  const hasStatus = Number.isFinite(status) && status > 0
  listErrorDiagnostic.value = hasStatus
    ? `技术诊断：HTTP ${status}`
    : '技术诊断：未取得可确认的响应状态'
  if (status === 403) return '当前账号无权读取业务方法，请切换项目范围或联系项目管理员。'
  if (status === 404) return '当前环境暂未提供业务方法目录，请确认服务已更新后重试。'
  if (hasStatus && status >= 500) return '业务方法目录暂时不可用，请稍后重试。'
  return '业务方法目录加载失败，请检查当前项目范围后重试。'
}

async function retryBusinessScope() {
  await pageProjectScope?.retryScope()
}

</script>

<style scoped lang="scss">
.capability-catalog-page { display: flex; flex: 1; min-width: 0; min-height: 0; flex-direction: column; gap: var(--layout-page-gap); }
.catalog-load-alert { margin: 0; }
.catalog-load-diagnostic { margin: 0 0 6px; color: var(--text-secondary); font-size: 12px; }

.capability-table :deep(.el-table__row) { cursor: pointer; }
.capability-table--adjusting :deep(.el-table__row) { cursor: default; }
.capability-cell__copy { display: grid; flex: 1; min-width: 0; gap: 5px; }
.capability-cell__name { min-width: 0; max-width: 100%; height: auto; margin: 0; padding: 0; color: var(--text-primary); font-size: 14px; font-weight: 650; line-height: 1.5; }
.capability-cell__name :deep(span) { overflow-wrap: anywhere; white-space: normal; text-align: left; }
.capability-cell__name:hover { color: var(--brand-active); text-decoration: underline; }
.capability-cell__name:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; border-radius: var(--radius-sm); }
.capability-cell__name-static { color: var(--text-primary); font-size: 14px; font-weight: 650; line-height: 1.5; overflow-wrap: anywhere; }
.catalog-method-meta { flex: 0 0 auto; padding: 1px 6px; border: 1px solid var(--border-divider); border-radius: var(--radius-sm); color: var(--text-muted); font-size: 11px; line-height: 18px; }
.catalog-method-status { border: 0; padding: 4px 0; background: transparent; color: inherit; font: inherit; cursor: help; }
.catalog-method-status:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; border-radius: var(--radius-sm); }
</style>

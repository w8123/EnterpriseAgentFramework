<template>
  <WorkbenchPage class="api-market-page" density="compact">
    <PageHeader
      class="market-page-header"
      variant="overview"
      height-preset="standard"
      domain="tool"
      title="API 市场"
      description="集中发现、评估并接入公开 API。"
    >
      <template #tags>
        <el-tag effect="light">公开 API 目录</el-tag>
        <el-tag type="success" effect="light">当前 {{ stats.publishedApis }} 个 API</el-tag>
        <el-tag type="info" effect="light">{{ stats.verifiedApis }} 个已验证</el-tag>
      </template>
      <template #actions>
        <el-tooltip content="来源可追溯" placement="bottom">
          <span class="market-source-status" role="img" aria-label="来源可追溯">
            <el-icon><CircleCheckFilled /></el-icon>
          </span>
        </el-tooltip>
        <el-tooltip content="刷新目录" placement="bottom">
          <el-button
            class="market-refresh"
            circle
            :icon="Refresh"
            :loading="refreshing"
            aria-label="刷新目录"
            @click="refreshCurrentTab"
          />
        </el-tooltip>
      </template>
    </PageHeader>

    <nav class="market-tabs" aria-label="API 市场功能导航">
      <button
        v-for="tab in tabs"
        :key="tab.name"
        type="button"
        :class="{ 'is-active': activeTab === tab.name }"
        @click="switchTab(tab.name)"
      >
        <span class="market-tabs__icon"><component :is="tab.icon" /></span>
        <span class="market-tabs__copy">
          <strong>{{ tab.label }}</strong>
          <small>{{ tab.description }}</small>
        </span>
      </button>
    </nav>

    <template v-if="activeTab === 'discover'">
      <section class="catalog-toolbar">
        <el-input
          v-model="filters.keyword"
          class="catalog-keyword"
          clearable
          :prefix-icon="Search"
          placeholder="搜索 API 名称、能力或提供方"
          @keyup.enter="applyFilters"
          @clear="applyFilters"
        />
        <el-select v-model="filters.authType" clearable placeholder="认证方式" aria-label="认证方式" @change="applyFilters">
          <el-option label="无需认证" value="NONE" />
          <el-option label="API Key · Header" value="API_KEY_HEADER" />
          <el-option label="API Key · Query" value="API_KEY_QUERY" />
          <el-option label="Bearer Token" value="BEARER" />
          <el-option label="OAuth 2.0" value="OAUTH2" />
        </el-select>
        <el-select
          v-model="filters.verificationStatus"
          clearable
          placeholder="验证状态"
          aria-label="验证状态"
          @change="applyFilters"
        >
          <el-option label="已验证" value="VERIFIED" />
          <el-option label="待验证" value="UNVERIFIED" />
          <el-option label="验证已过期" value="STALE" />
          <el-option label="验证失败" value="FAILED" />
        </el-select>
        <el-select v-model="filters.source" clearable placeholder="目录来源" aria-label="目录来源" @change="applyFilters">
          <el-option
            v-for="source in sources"
            :key="source.sourceKey"
            :label="source.name"
            :value="source.sourceKey"
          />
        </el-select>
        <el-select v-model="filters.specFilter" clearable placeholder="机器可读契约" aria-label="机器可读契约" @change="applyFilters">
          <el-option label="有 OpenAPI" value="yes" />
          <el-option label="暂无 OpenAPI" value="no" />
        </el-select>
        <el-button
          class="catalog-reset"
          :icon="RefreshLeft"
          :disabled="!hasActiveFilters"
          @click="resetFilters"
        >重置</el-button>
        <el-button class="catalog-search" type="primary" :icon="Search" @click="applyFilters">搜索</el-button>
        <footer class="catalog-toolbar__summary">
          <span><strong>{{ page.total }}</strong> 个可接入 API</span>
          <small>按认证、验证状态、目录来源与契约筛选</small>
        </footer>
      </section>

      <section v-loading="loadingEntries" class="catalog-results" aria-live="polite">
        <el-empty
          v-if="!loadingEntries && !entries.length"
          description="没有找到符合条件的 API，试试减少筛选条件或换一个关键词。"
        >
          <el-button type="primary" @click="resetFilters">查看全部 API</el-button>
        </el-empty>
        <div v-else class="api-card-grid">
          <ApiMarketEntryCard
            v-for="entry in entries"
            :key="entry.entryKey"
            :entry="entry"
            @open="openEntry"
            @integrate="quickIntegrate"
          />
        </div>
        <footer v-if="page.total > page.size" class="catalog-pagination">
          <span>第 {{ page.current }} / {{ page.pages }} 页</span>
          <el-pagination
            v-model:current-page="page.current"
            :page-size="page.size"
            :total="page.total"
            layout="prev, pager, next"
            background
            @current-change="loadEntries"
          />
        </footer>
      </section>
    </template>

    <template v-else-if="activeTab === 'integrations'">
      <section class="workspace-panel">
        <header class="workspace-panel__header">
          <div>
            <h2>我的接入</h2>
            <span>接入记录保存“项目准备使用哪些 API”，凭据和实际执行仍由 Runtime 管理。</span>
          </div>
          <div class="workspace-panel__filters">
            <el-select
              v-model="integrationFilters.projectId"
              clearable
              filterable
              placeholder="全部项目"
              style="width: 220px"
              @change="loadIntegrations"
            >
              <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
            </el-select>
            <el-select
              v-model="integrationFilters.status"
              clearable
              placeholder="全部状态"
              style="width: 150px"
              @change="loadIntegrations"
            >
              <el-option label="待配置" value="CONFIGURING" />
              <el-option label="可使用" value="READY" />
              <el-option label="异常" value="BROKEN" />
              <el-option label="已停用" value="DISABLED" />
            </el-select>
          </div>
        </header>

        <div v-loading="loadingIntegrations" class="integration-table-shell">
          <el-empty v-if="!loadingIntegrations && !integrations.length" description="还没有项目接入外部 API">
            <el-button type="primary" @click="switchTab('discover')">去发现 API</el-button>
          </el-empty>
          <el-table v-else :data="integrations" class="integration-table">
            <el-table-column label="API / 项目" min-width="250">
              <template #default="{ row }">
                <button class="table-api-link" type="button" @click="openIntegrationEntry(row)">
                  <strong>{{ row.entry?.title || '未知 API' }}</strong>
                  <span>{{ row.projectName }} · {{ row.projectCode }}</span>
                </button>
              </template>
            </el-table-column>
            <el-table-column label="环境" prop="environment" width="120" />
            <el-table-column label="Operations" min-width="190">
              <template #default="{ row }">
                <div class="operation-summary">
                  <el-tag v-for="operation in row.selectedOperations.slice(0, 2)" :key="operation.id" size="small" effect="plain">
                    {{ operation.httpMethod }} {{ operation.operationKey }}
                  </el-tag>
                  <span v-if="row.selectedOperations.length > 2">+{{ row.selectedOperations.length - 2 }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="凭据" width="130">
              <template #default="{ row }">
                <span :class="['credential-state', { 'is-required': row.credentialRequired }]">
                  {{ row.credentialRequired ? '需在 Workflow 配置' : '无需凭据' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="112">
              <template #default="{ row }">
                <StatusTag
                  :label="formatApiMarketIntegrationStatus(row.status)"
                  :tone="apiMarketIntegrationTone(row.status)"
                />
              </template>
            </el-table-column>
            <el-table-column label="最近更新" width="170">
              <template #default="{ row }">{{ formatDate(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" fixed="right" width="210">
              <template #default="{ row }">
                <el-button text type="primary" @click="createWorkflowFromIntegration(row)">创建 Workflow</el-button>
                <el-button
                  v-if="row.status !== 'DISABLED'"
                  text
                  type="danger"
                  @click="setIntegrationStatus(row, 'DISABLED')"
                >停用</el-button>
                <el-button
                  v-else
                  text
                  type="success"
                  @click="setIntegrationStatus(row, row.credentialRequired ? 'CONFIGURING' : 'READY')"
                >启用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>
    </template>

    <template v-else-if="activeTab === 'changes'">
      <section class="workspace-panel">
        <header class="workspace-panel__header">
          <div>
            <h2>变更与风险</h2>
            <span>优先处理 Spec 变化、验证过期和失败条目；市场不会自动改写已发布 Workflow。</span>
          </div>
        </header>
        <div v-loading="loadingChanges" class="risk-list">
          <el-empty v-if="!loadingChanges && !riskEntries.length" description="当前没有待处理的 API 风险" />
          <article v-for="entry in riskEntries" :key="entry.entryKey" class="risk-card">
            <div class="risk-card__icon"><WarningFilled /></div>
            <div class="risk-card__main">
              <strong>{{ entry.title }}</strong>
              <p>{{ riskMessage(entry) }}</p>
              <span>{{ entry.provider?.name }} · {{ entry.source?.name }}</span>
            </div>
            <StatusTag
              :label="entry.specStatus === 'CHANGED' ? 'Spec 有变更' : formatApiMarketVerification(entry.verificationStatus)"
              :tone="entry.verificationStatus === 'FAILED' ? 'danger' : 'warning'"
            />
            <el-button @click="openEntry(entry)">查看证据</el-button>
          </article>
        </div>
      </section>
    </template>

    <template v-else>
      <section class="workspace-panel">
        <header class="workspace-panel__header">
          <div>
            <h2>来源与质量</h2>
            <span>目录保存来源、同步策略和可信级别；外部来源变化先进入审核，不直接覆盖在用版本。</span>
          </div>
        </header>
        <div class="source-grid">
          <article v-for="source in sources" :key="source.sourceKey" class="source-card">
            <header>
              <div class="source-card__icon"><DataLine /></div>
              <div>
                <strong>{{ source.name }}</strong>
                <span>{{ source.sourceType }} · {{ source.trustLevel || 'COMMUNITY' }}</span>
              </div>
              <StatusTag :label="source.status === 'ACTIVE' ? '启用' : source.status" :tone="source.status === 'ACTIVE' ? 'success' : 'neutral'" />
            </header>
            <p>{{ source.description || '暂无来源说明' }}</p>
            <dl>
              <div><dt>已发布条目</dt><dd>{{ source.entryCount }}</dd></div>
              <div><dt>同步策略</dt><dd>{{ source.syncStrategy || '人工审核' }}</dd></div>
              <div><dt>最近同步</dt><dd>{{ formatDate(source.lastSyncedAt) }}</dd></div>
            </dl>
            <el-link
              v-if="source.homepageUrl || source.sourceUrl"
              :href="source.homepageUrl || source.sourceUrl || undefined"
              target="_blank"
              rel="noopener noreferrer"
              type="primary"
            >
              查看来源 <el-icon><TopRight /></el-icon>
            </el-link>
          </article>
        </div>
      </section>
    </template>

    <ApiMarketDetailDrawer
      v-model="detailVisible"
      :detail="selectedDetail"
      :loading="detailLoading"
      @integrate="beginIntegration"
    />

    <AppDialog
      v-model="integrationDialogVisible"
      title="接入 API 并生成 Workflow"
      :description="integrationOperation ? `${selectedDetail?.entry.title} · ${integrationOperation.title}` : '选择项目和 Operation'"
      width="min(720px, 94vw)"
      destroy-on-close
    >
      <el-form label-position="top" class="integration-form">
        <section class="selected-operation" v-if="integrationOperation && integrationVersion">
          <el-tag :type="integrationOperation.httpMethod === 'GET' ? 'success' : 'warning'" effect="dark">
            {{ integrationOperation.httpMethod }}
          </el-tag>
          <div>
            <strong>{{ integrationOperation.title }}</strong>
            <code>{{ integrationVersion.baseUrl }}{{ integrationOperation.path }}</code>
          </div>
          <StatusTag
            :label="formatApiMarketSideEffect(integrationOperation.sideEffect)"
            :tone="apiMarketSideEffectTone(integrationOperation.sideEffect)"
          />
        </section>

        <div class="integration-form__grid">
          <el-form-item label="接入项目" required>
            <el-select
              v-model="integrationForm.projectId"
              filterable
              placeholder="选择项目"
              @change="applySelectedProject"
            >
              <el-option
                v-for="project in projects"
                :key="project.id"
                :label="`${project.name} · ${project.projectCode || '未设置编码'}`"
                :value="project.id"
                :disabled="!project.projectCode"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="环境">
            <el-select v-model="integrationForm.environment">
              <el-option label="开发" value="DEVELOPMENT" />
              <el-option label="测试" value="TEST" />
              <el-option label="预发布" value="STAGING" />
              <el-option label="生产" value="PRODUCTION" />
            </el-select>
          </el-form-item>
        </div>

        <el-alert
          v-if="selectedDetail?.entry.authType !== 'NONE'"
          title="该 API 需要凭据"
          description="本次只保存项目接入意图。Workflow 创建后，请在 Studio 的 HTTP 节点中选择 Runtime 加密凭据；API Key 不会写入市场目录。"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-alert
          v-else
          title="该 Operation 无需认证"
          description="系统会生成带参数输入和来源追踪的 HTTP_REQUEST Workflow 草稿。"
          type="success"
          :closable="false"
          show-icon
        />

        <el-checkbox v-model="integrationForm.createWorkflow" class="workflow-toggle">
          同时创建可编辑的 Workflow 草稿
        </el-checkbox>

        <template v-if="integrationForm.createWorkflow">
          <div class="integration-form__grid">
            <el-form-item label="Workflow 名称" required>
              <el-input v-model="integrationForm.workflowName" maxlength="80" show-word-limit />
            </el-form-item>
            <el-form-item label="Workflow Key" required>
              <el-input v-model="integrationForm.workflowKey" maxlength="128" />
            </el-form-item>
          </div>
          <p class="workflow-hint">
            草稿会固定市场版本和 Spec Hash，但实际 Method、URL 和参数会作为 GraphSpec 运行语义保存，可在 Studio 中继续调整。
          </p>
        </template>
      </el-form>

      <template #footer>
        <el-button @click="integrationDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submittingIntegration" @click="submitIntegration">
          {{ integrationForm.createWorkflow ? '接入并进入 Workflow Studio' : '保存项目接入' }}
        </el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheckFilled,
  Collection,
  Connection,
  DataLine,
  Refresh,
  RefreshLeft,
  Search,
  TopRight,
  TrendCharts,
  WarningFilled,
} from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import ApiMarketEntryCard from './components/ApiMarketEntryCard.vue'
import ApiMarketDetailDrawer from './components/ApiMarketDetailDrawer.vue'
import {
  createApiMarketIntegration,
  getApiMarketEntry,
  getApiMarketStats,
  listApiMarketEntries,
  listApiMarketIntegrations,
  listApiMarketSources,
  updateApiMarketIntegrationStatus,
} from '@/api/apiMarket'
import { getScanProjects } from '@/api/scanProject'
import { createWorkflow } from '@/api/workflow'
import type {
  ApiMarketEntryDetail,
  ApiMarketEntrySummary,
  ApiMarketIntegration,
  ApiMarketOperation,
  ApiMarketPage,
  ApiMarketSource,
  ApiMarketStats,
  ApiMarketVersion,
} from '@/types/apiMarket'
import type { ScanProject } from '@/types/scanProject'
import { buildApiMarketWorkflowDraft } from '@/utils/apiMarketWorkflow'
import {
  apiMarketIntegrationTone,
  apiMarketSideEffectTone,
  formatApiMarketIntegrationStatus,
  formatApiMarketSideEffect,
  formatApiMarketVerification,
} from '@/utils/apiMarketLabels'

type MarketTab = 'discover' | 'integrations' | 'changes' | 'sources'

const router = useRouter()
const tabs = [
  { name: 'discover' as const, label: '发现 API', description: '搜索与评估', icon: markRaw(Collection) },
  { name: 'integrations' as const, label: '我的接入', description: '项目与环境', icon: markRaw(Connection) },
  { name: 'changes' as const, label: '变更与风险', description: '升级影响', icon: markRaw(TrendCharts) },
  { name: 'sources' as const, label: '来源与质量', description: '证据与同步', icon: markRaw(DataLine) },
]

const activeTab = ref<MarketTab>('discover')
const refreshing = ref(false)
const loadingEntries = ref(false)
const loadingIntegrations = ref(false)
const loadingChanges = ref(false)
const detailLoading = ref(false)
const submittingIntegration = ref(false)
const entries = ref<ApiMarketEntrySummary[]>([])
const integrations = ref<ApiMarketIntegration[]>([])
const riskEntries = ref<ApiMarketEntrySummary[]>([])
const sources = ref<ApiMarketSource[]>([])
const projects = ref<ScanProject[]>([])
const stats = reactive<ApiMarketStats>({
  publishedApis: 0,
  verifiedApis: 0,
  apiSpecs: 0,
  projectIntegrations: 0,
})
const page = reactive<ApiMarketPage>({ records: [], total: 0, current: 1, size: 24, pages: 0 })
const filters = reactive({
  keyword: '',
  authType: '',
  verificationStatus: '',
  source: '',
  specFilter: '' as '' | 'yes' | 'no',
})
const integrationFilters = reactive<{ projectId?: number; status?: string }>({})

const detailVisible = ref(false)
const selectedDetail = ref<ApiMarketEntryDetail | null>(null)
const integrationDialogVisible = ref(false)
const integrationVersion = ref<ApiMarketVersion | null>(null)
const integrationOperation = ref<ApiMarketOperation | null>(null)
const integrationForm = reactive({
  projectId: undefined as number | undefined,
  projectCode: '',
  environment: 'DEVELOPMENT',
  createWorkflow: true,
  workflowName: '',
  workflowKey: '',
})

const hasActiveFilters = computed(() => Boolean(
  filters.keyword || filters.authType || filters.verificationStatus
  || filters.source || filters.specFilter,
))

onMounted(async () => {
  await Promise.allSettled([
    loadStats(),
    loadSources(),
    loadProjects(),
    loadEntries(),
  ])
})

async function loadEntries() {
  loadingEntries.value = true
  try {
    const { data } = await listApiMarketEntries({
      current: page.current,
      size: page.size,
      keyword: filters.keyword.trim() || undefined,
      authType: filters.authType || undefined,
      verificationStatus: filters.verificationStatus || undefined,
      source: filters.source || undefined,
      specAvailable: filters.specFilter ? filters.specFilter === 'yes' : undefined,
    })
    entries.value = data.records || []
    Object.assign(page, data)
  } finally {
    loadingEntries.value = false
  }
}

async function loadStats() {
  const { data } = await getApiMarketStats()
  Object.assign(stats, data)
}

async function loadSources() {
  const { data } = await listApiMarketSources()
  sources.value = data || []
}

async function loadProjects() {
  const { data } = await getScanProjects()
  projects.value = (data || []).filter(project => Boolean(project.projectCode))
}

async function loadIntegrations() {
  loadingIntegrations.value = true
  try {
    const { data } = await listApiMarketIntegrations({
      projectId: integrationFilters.projectId,
      status: integrationFilters.status,
    })
    integrations.value = data || []
  } finally {
    loadingIntegrations.value = false
  }
}

async function loadChanges() {
  loadingChanges.value = true
  try {
    const requests = await Promise.all([
      listApiMarketEntries({ current: 1, size: 60, verificationStatus: 'STALE' }),
      listApiMarketEntries({ current: 1, size: 60, verificationStatus: 'FAILED' }),
      listApiMarketEntries({ current: 1, size: 60, specStatus: 'CHANGED' }),
    ])
    const unique = new Map<string, ApiMarketEntrySummary>()
    ;[...requests[0].data.records, ...requests[1].data.records, ...requests[2].data.records]
      .forEach(entry => unique.set(entry.entryKey, entry))
    riskEntries.value = [...unique.values()]
  } finally {
    loadingChanges.value = false
  }
}

function switchTab(tab: MarketTab) {
  activeTab.value = tab
  if (tab === 'integrations') void loadIntegrations()
  if (tab === 'changes') void loadChanges()
}

async function refreshCurrentTab() {
  refreshing.value = true
  try {
    if (activeTab.value === 'discover') {
      await Promise.all([loadEntries(), loadStats()])
    } else if (activeTab.value === 'integrations') {
      await Promise.all([loadIntegrations(), loadStats()])
    } else if (activeTab.value === 'changes') {
      await loadChanges()
    } else {
      await loadSources()
    }
  } finally {
    refreshing.value = false
  }
}

function applyFilters() {
  page.current = 1
  void loadEntries()
}

function resetFilters() {
  Object.assign(filters, {
    keyword: '', authType: '', verificationStatus: '', source: '', specFilter: '',
  })
  applyFilters()
}

async function openEntry(entry: ApiMarketEntrySummary) {
  detailVisible.value = true
  detailLoading.value = true
  selectedDetail.value = null
  try {
    const { data } = await getApiMarketEntry(entry.entryKey)
    selectedDetail.value = data
  } finally {
    detailLoading.value = false
  }
}

async function quickIntegrate(entry: ApiMarketEntrySummary) {
  detailLoading.value = true
  try {
    const { data } = await getApiMarketEntry(entry.entryKey)
    selectedDetail.value = data
    const version = data.versions[0]
    const operation = version?.operations[0]
    if (!version || !operation) {
      detailVisible.value = true
      ElMessage.warning('该 API 暂无可接入的 Operation')
      return
    }
    beginIntegration(version, operation)
  } finally {
    detailLoading.value = false
  }
}

function beginIntegration(version: ApiMarketVersion, operation: ApiMarketOperation) {
  if (!selectedDetail.value) return
  integrationVersion.value = version
  integrationOperation.value = operation
  integrationForm.projectId = undefined
  integrationForm.projectCode = ''
  integrationForm.environment = 'DEVELOPMENT'
  integrationForm.createWorkflow = true
  integrationForm.workflowName = `${selectedDetail.value.entry.title} · ${operation.title}`.slice(0, 80)
  integrationForm.workflowKey = uniqueWorkflowKey(selectedDetail.value.entry.entryKey, operation.operationKey)
  detailVisible.value = false
  integrationDialogVisible.value = true
}

function applySelectedProject(projectId: number) {
  const project = projects.value.find(item => item.id === projectId)
  integrationForm.projectCode = project?.projectCode || ''
  integrationForm.environment = normalizeEnvironment(project?.environment)
}

async function submitIntegration() {
  const detail = selectedDetail.value
  const version = integrationVersion.value
  const operation = integrationOperation.value
  if (!detail || !version || !operation) return
  if (!integrationForm.projectId || !integrationForm.projectCode) {
    ElMessage.warning('请选择具有项目编码的接入项目')
    return
  }
  if (integrationForm.createWorkflow
      && !/^[A-Za-z0-9][A-Za-z0-9_-]{1,127}$/.test(integrationForm.workflowKey.trim())) {
    ElMessage.warning('Workflow Key 需为 2-128 位，仅支持字母、数字、下划线或连字符')
    return
  }
  submittingIntegration.value = true
  try {
    const { data: integration } = await createApiMarketIntegration(detail.entry.entryKey, {
      projectId: integrationForm.projectId,
      projectCode: integrationForm.projectCode,
      versionId: version.id,
      operationIds: [operation.id],
      environment: integrationForm.environment,
      note: `从 API 市场接入 ${operation.title}`,
    })
    await Promise.all([loadStats(), loadIntegrations()])
    if (!integrationForm.createWorkflow) {
      integrationDialogVisible.value = false
      activeTab.value = 'integrations'
      ElMessage.success('项目 API 接入已保存')
      return
    }

    try {
      const draft = buildApiMarketWorkflowDraft(detail, version, operation, {
        name: integrationForm.workflowName.trim(),
        keySlug: integrationForm.workflowKey.trim(),
        projectId: integrationForm.projectId,
        projectCode: integrationForm.projectCode,
        integrationId: integration.id,
      })
      const { data: workflow } = await createWorkflow(draft)
      integrationDialogVisible.value = false
      ElMessage.success(integration.credentialRequired
        ? '接入已保存，请在 Workflow Studio 配置凭据并调试'
        : 'API 已接入，Workflow 草稿已生成')
      await router.push(`/workflows/${workflow.id}/studio`)
    } catch (error) {
      activeTab.value = 'integrations'
      integrationDialogVisible.value = false
      ElMessage.warning(`项目接入已保存，但 Workflow 创建失败：${errorMessage(error)}`)
    }
  } finally {
    submittingIntegration.value = false
  }
}

async function openIntegrationEntry(row: ApiMarketIntegration) {
  if (!row.entry) return
  await openEntry(row.entry)
}

async function createWorkflowFromIntegration(row: ApiMarketIntegration) {
  if (!row.entry || !row.version || !row.selectedOperations.length) return
  detailLoading.value = true
  try {
    const { data } = await getApiMarketEntry(row.entry.entryKey)
    selectedDetail.value = data
    const version = data.versions.find(item => item.id === row.version?.id) || data.versions[0]
    const operation = version?.operations.find(item => item.id === row.selectedOperations[0].id)
    if (!version || !operation) {
      ElMessage.error('接入记录引用的 API 版本或 Operation 已不可用')
      return
    }
    beginIntegration(version, operation)
    integrationForm.projectId = row.projectId
    integrationForm.projectCode = row.projectCode
    integrationForm.environment = row.environment
  } finally {
    detailLoading.value = false
  }
}

async function setIntegrationStatus(row: ApiMarketIntegration, status: string) {
  const action = status === 'DISABLED' ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `${action}后不会修改已经发布的 Workflow，但会改变市场接入状态。`,
      `${action} ${row.entry?.title || 'API'}`,
      { type: 'warning', confirmButtonText: `确认${action}`, cancelButtonText: '取消' },
    )
    await updateApiMarketIntegrationStatus(row.id, { status })
    ElMessage.success(`已${action}`)
    await Promise.all([loadIntegrations(), loadStats()])
  } catch {
    // 用户取消或请求拦截器已提示。
  }
}

function uniqueWorkflowKey(entryKey: string, operationKey: string) {
  return `api-${entryKey}-${operationKey}-${Date.now().toString(36).slice(-5)}`
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 128)
}

function normalizeEnvironment(value?: string | null) {
  const normalized = (value || '').toUpperCase()
  if (normalized === 'DEV') return 'DEVELOPMENT'
  if (normalized === 'PROD') return 'PRODUCTION'
  if (['DEVELOPMENT', 'TEST', 'STAGING', 'PRODUCTION'].includes(normalized)) return normalized
  return 'DEVELOPMENT'
}

function riskMessage(entry: ApiMarketEntrySummary) {
  if (entry.specStatus === 'CHANGED') return '机器可读契约发生变化，需要查看 Diff 并评估受影响的 Workflow。'
  if (entry.verificationStatus === 'FAILED') return '最近一次质量验证失败，继续使用前应重新测试。'
  return '验证证据已经过期，需要刷新官方文档和可用性检查。'
}

function formatDate(value?: string | null) {
  if (!value) return '暂无记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

function errorMessage(error: unknown) {
  const candidate = error as { response?: { data?: { message?: string } }; message?: string }
  return candidate.response?.data?.message || candidate.message || '未知错误'
}
</script>

<style scoped lang="scss">
.api-market-page {
  gap: 14px;
}

.market-page-header {
  :deep(.app-page-header__actions) {
    gap: 8px;
  }
}

.market-source-status,
.market-refresh {
  display: inline-grid;
  width: 36px;
  height: 36px;
  place-items: center;
  padding: 0 !important;
  border: 1px solid var(--border-subtle) !important;
  border-radius: 10px !important;
  background: var(--surface-solid-control) !important;
  box-shadow: var(--inner-highlight);
}

.market-source-status {
  color: var(--status-success);
  font-size: 17px;
}

.market-refresh {
  color: var(--text-secondary) !important;

  &:hover {
    border-color: rgb(var(--brand-primary-rgb) / 0.28) !important;
    color: var(--brand-active) !important;
  }
}

.market-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  padding: 0 8px;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background: var(--surface-glass-panel);
  box-shadow: none;

  button {
    position: relative;
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 8px;
    padding: 11px 14px;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: var(--text-secondary);
    text-align: left;
    cursor: pointer;
    transition:
      background var(--motion-duration-fast) ease,
      color var(--motion-duration-fast) ease;

    &:hover {
      background: rgb(var(--brand-primary-rgb) / 0.045);
      color: var(--brand-active);
    }

    &.is-active {
      background: rgb(var(--brand-primary-rgb) / 0.065);
      color: var(--brand-active);
      box-shadow: none;

      &::after {
        content: '';
        position: absolute;
        right: 14px;
        bottom: 0;
        left: 14px;
        height: 2px;
        border-radius: 999px;
        background: var(--brand-primary);
      }
    }
  }
}

.market-tabs__icon {
  display: grid;
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  place-items: center;
  background: transparent;
  color: var(--text-muted);
  font-size: 15px;
}

.market-tabs button.is-active .market-tabs__icon {
  background: transparent;
  color: var(--brand-active);
}

.market-tabs__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;

  strong {
    overflow: hidden;
    font-size: 12px;
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    overflow: hidden;
    color: var(--text-muted);
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.catalog-toolbar,
.workspace-panel {
  border: 1px solid var(--border-subtle);
  border-radius: 15px;
  background: color-mix(in srgb, var(--surface-glass-panel) 92%, var(--surface-solid-control));
  box-shadow: var(--shadow-sm);
}

.catalog-toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 2fr) repeat(4, minmax(108px, 1fr)) auto auto;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 9px;
  box-shadow: none;

  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    min-height: 38px;
    border-radius: 9px;
    background: var(--surface-solid-control);
    box-shadow: 0 0 0 1px var(--border-subtle) inset;
  }

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}

.workspace-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.catalog-toolbar__summary {
  display: flex;
  align-items: center;
  grid-column: 1 / -1;
  gap: 7px;
  padding-top: 8px;
  border-top: 1px solid var(--border-subtle);
  color: var(--text-secondary);
  font-size: 11px;

  strong {
    color: var(--text-primary);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }

  small {
    color: var(--text-muted);
    font-size: 10px;
  }
}

.catalog-reset,
.catalog-search {
  min-width: 72px;
  height: 38px;
  border-radius: 9px;
  font-weight: 650;
}

.catalog-reset {
  border-color: var(--border-subtle);
  background: var(--surface-solid-control);
  color: var(--text-secondary);

  &:hover {
    border-color: rgb(var(--brand-primary-rgb) / 0.35);
    color: var(--brand-active);
  }
}

.catalog-search {
  min-width: 80px;
}

.catalog-results {
  min-height: 280px;
}

.api-card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.catalog-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18px;
  padding: 13px 18px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-glass-panel);
  color: var(--text-muted);
  font-size: 12px;
}

.workspace-panel {
  overflow: hidden;
}

.workspace-panel__header {
  padding: 17px 20px;
  border-bottom: 1px solid var(--border-divider);

  h2,
  span {
    margin: 0;
  }

  h2 {
    color: var(--text-primary);
    font-size: 18px;
  }

  span {
    display: block;
    margin-top: 4px;
    color: var(--text-secondary);
    font-size: 12px;
  }
}

.workspace-panel__filters,
.operation-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.integration-table-shell {
  min-height: 300px;
}

.integration-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: var(--surface-solid-control);
  --el-table-row-hover-bg-color: rgb(var(--brand-primary-rgb) / 0.06);
  --el-table-border-color: var(--border-divider);
  --el-table-text-color: var(--text-secondary);
  --el-table-header-text-color: var(--text-muted);
}

.table-api-link {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;

  strong {
    color: var(--text-primary);
  }

  span {
    color: var(--text-muted);
    font-size: 12px;
  }

  &:hover strong {
    color: var(--brand-active);
  }
}

.operation-summary {
  span {
    color: var(--text-muted);
    font-size: 11px;
  }
}

.credential-state {
  color: var(--status-success);
  font-size: 12px;

  &.is-required {
    color: var(--status-warning);
  }
}

.risk-list {
  display: flex;
  min-height: 300px;
  flex-direction: column;
  gap: 10px;
  padding: 18px;
}

.risk-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 15px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.risk-card__icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  border-radius: 11px;
  background: var(--status-warning-soft);
  color: var(--status-warning);
}

.risk-card__main {
  min-width: 0;
  flex: 1;

  strong {
    color: var(--text-primary);
  }

  p {
    margin: 5px 0;
    color: var(--text-secondary);
    font-size: 13px;
  }

  span {
    color: var(--text-muted);
    font-size: 11px;
  }
}

.source-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  padding: 18px;
}

.source-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 15px;
  padding: 18px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);

  > header {
    display: flex;
    align-items: center;
    gap: 11px;

    > div:nth-child(2) {
      display: flex;
      min-width: 0;
      flex: 1;
      flex-direction: column;
      gap: 3px;
    }

    strong {
      color: var(--text-primary);
    }

    span {
      color: var(--text-muted);
      font-size: 11px;
    }
  }

  > p {
    min-height: 42px;
    margin: 0;
    color: var(--text-secondary);
    font-size: 13px;
    line-height: 1.6;
  }

  dl {
    display: flex;
    flex-direction: column;
    gap: 9px;
    margin: 0;
    padding-block: 13px;
    border-block: 1px solid var(--border-divider);

    div {
      display: flex;
      justify-content: space-between;
      gap: 12px;
    }

    dt {
      color: var(--text-muted);
      font-size: 12px;
    }

    dd {
      margin: 0;
      color: var(--text-primary);
      font-size: 12px;
      font-weight: 650;
      text-align: right;
    }
  }
}

.source-card__icon {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 12px;
  background: rgb(var(--brand-primary-rgb) / 0.12);
  color: var(--brand-active);
}

.integration-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.selected-operation {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);

  > div {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 5px;
  }

  strong {
    color: var(--text-primary);
  }

  code {
    overflow: hidden;
    color: var(--text-muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.integration-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;

  :deep(.el-form-item) {
    margin-bottom: 0;
  }

  :deep(.el-select) {
    width: 100%;
  }
}

.workflow-toggle {
  align-self: flex-start;
  font-weight: 650;
}

.workflow-hint {
  margin: -4px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.55;
}

@media (max-width: 1320px) {
  .api-card-grid,
  .source-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .catalog-toolbar {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .catalog-keyword {
    grid-column: span 2;
  }
}

@media (max-width: 1100px) {
  .workspace-panel__header {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (max-width: 900px) {
  .market-page-header {
    grid-template-columns: minmax(0, 1fr) auto;

    :deep(.app-page-header__tags) {
      display: none;
    }

    :deep(.app-page-header__trailing) {
      width: auto;
      grid-row: 1;
      grid-column: 2;
      justify-content: flex-end;
    }
  }

  .api-card-grid,
  .source-grid {
    grid-template-columns: 1fr;
  }

  .market-tabs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .catalog-toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .catalog-keyword {
    grid-column: 1 / -1;
  }
}

@media (max-width: 680px) {
  .market-page-header {
    grid-template-columns: minmax(0, 1fr);

    :deep(.app-page-header__trailing) {
      width: 100%;
      grid-row: auto;
      grid-column: 1;
      justify-content: flex-start;
    }
  }

  .api-card-grid,
  .source-grid,
  .integration-form__grid {
    grid-template-columns: 1fr;
  }

  .catalog-toolbar {
    grid-template-columns: 1fr;
  }

  .catalog-keyword {
    grid-column: 1;
  }

  .catalog-reset,
  .catalog-search {
    width: 100%;
  }

  .risk-card,
  .selected-operation {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

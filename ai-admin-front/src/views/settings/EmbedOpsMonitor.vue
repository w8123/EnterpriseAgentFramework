<template>
  <WorkbenchPage class="embed-ops-page project-workbench-page" layout="list">
    <PageHeader
      variant="overview"
      domain="project"
      eyebrow="Page Access"
      title="前端页面管理"
      description="管理当前项目已接入的业务页面、页面动作、嵌入授权和会话审计。"
    >
      <template #actions>
        <el-button :icon="ChatDotRound" @click="goSessionAudit">嵌入式会话审计</el-button>
        <el-button :icon="Lock" @click="openCredentialDrawer">嵌入授权</el-button>
        <el-button :icon="Cpu" @click="openRendererDrawer">嵌入渲染器</el-button>
      </template>
    </PageHeader>

    <MetricStrip :items="metricItems" density="compact" aria-label="页面接入指标" />

    <DataTableShell
      class="embed-ops-shell workbench-list-surface project-list-card-mode"
      density="compact"
      :loading="catalogLoading"
      :empty="filteredPages.length === 0"
      empty-description="暂无匹配页面"
    >
      <template #toolbar>
        <FilterBar
          class="project-list-filter-bar"
          density="compact"
          :loading="catalogLoading"
          query-label="搜索"
          @query="loadCatalog"
          @reset="resetCatalogFilters"
        >
          <el-form-item label="页面">
            <el-input
              v-model="catalogFilters.pageKey"
              clearable
              :prefix-icon="Search"
              placeholder="搜索 pageKey / 路由"
              @keyup.enter="loadCatalog"
            />
          </el-form-item>
          <el-form-item label="动作">
            <el-input
              v-model="catalogFilters.actionKeyword"
              clearable
              placeholder="标题 / actionKey"
              @keyup.enter="loadCatalog"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="catalogFilters.status" clearable placeholder="全部状态">
              <el-option label="已启用" value="ACTIVE" />
              <el-option label="已移除" value="REMOVED" />
              <el-option label="已禁用" value="DISABLED" />
            </el-select>
          </el-form-item>
          <template #actions>
            <el-button native-type="button" @click="resetCatalogFilters">重置</el-button>
            <el-button type="primary" native-type="submit" :icon="Search" :loading="catalogLoading">
              搜索
            </el-button>
          </template>
        </FilterBar>
      </template>

      <div class="project-list-card-grid">
        <article
          v-for="page in filteredPages"
          :key="page.pageKey"
          class="project-list-card"
          :class="{ 'is-active': page.pageKey === selectedPageKey && actionDrawerVisible }"
          @click="selectPage(page.pageKey)"
        >
          <div class="project-list-card__header">
            <div class="project-list-card__icon">
              <el-icon :size="20"><Monitor /></el-icon>
            </div>
            <div class="project-list-card__identity">
              <h3>{{ page.name || page.pageKey }}</h3>
              <code>{{ page.pageKey }}</code>
            </div>
            <CommonStatusTag :status="page.status" />
          </div>

          <p class="project-list-card__description">
            {{ page.routePattern || '未登记路由' }}
          </p>

          <div class="project-list-card__metrics">
            <span>
              <strong>{{ actionCount(page.pageKey) }}</strong>
              <small>动作</small>
            </span>
            <span>
              <strong class="embed-ops-card-time">{{ formatRelativeTime(page.lastSeenAt) }}</strong>
              <small>最近上报</small>
            </span>
          </div>

          <div class="project-list-card__footer">
            <el-button link type="primary" size="small" @click.stop="selectPage(page.pageKey)">动作详情</el-button>
            <el-button link type="primary" size="small" @click.stop="openPageMemoryWorkbench(page)">上下文</el-button>
            <el-button link type="primary" size="small" @click.stop="previewPage(page)">预览</el-button>
            <el-button
              link
              type="danger"
              size="small"
              :loading="deletingPageId === page.id"
              @click.stop="confirmDeletePage(page)"
            >
              删除
            </el-button>
          </div>
        </article>
      </div>
    </DataTableShell>

    <AppDrawer
      v-model="actionDrawerVisible"
      size="640px"
      :title="selectedPage?.name || selectedPage?.pageKey || '页面动作'"
      destroy-on-close
    >
      <div v-if="selectedPage" class="drawer-page-summary">
        <div>
          <span>页面标识</span>
          <strong>{{ selectedPage.pageKey }}</strong>
        </div>
        <div>
          <span>路由</span>
          <strong>{{ selectedPage.routePattern || '-' }}</strong>
        </div>
        <div>
          <span>最近上报</span>
          <strong>{{ formatRelativeTime(selectedPage.lastSeenAt) }}</strong>
        </div>
        <div>
          <span>状态</span>
          <strong>
            <CommonStatusTag :status="selectedPage.status" />
          </strong>
        </div>
      </div>
      <div class="drawer-section-title">
        <span>页面动作</span>
        <el-tag effect="plain">{{ selectedPageActions.length }} 个动作</el-tag>
      </div>
      <el-table v-if="selectedPageActions.length" :data="selectedPageActions" size="small" class="drawer-action-table">
        <el-table-column prop="title" label="动作标题" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.title || '-' }}</template>
        </el-table-column>
        <el-table-column prop="actionKey" label="actionKey" min-width="150" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="96">
          <template #default="{ row }">
            <CommonStatusTag :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="允许智能体" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ formatJsonArray(row.allowedAgentIdsJson) || '-' }}</template>
        </el-table-column>
        <el-table-column label="确认方式" width="100">
          <template #default="{ row }">{{ row.confirmRequired ? '二次确认' : '免确认' }}</template>
        </el-table-column>
        <el-table-column label="最近上报" width="120">
          <template #default="{ row }">{{ formatRelativeTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="170" show-overflow-tooltip />
        <el-table-column label="引用" width="96" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openActionReferences(row)">Workflow</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="当前页面暂无匹配动作" :image-size="88" />
    </AppDrawer>

    <AppDialog
      v-model="referenceDialogVisible"
      title="页面动作 Workflow 引用"
      width="980px"
      destroy-on-close
    >
      <div class="reference-head">
        <span>
          <b>{{ referenceAction?.title || referenceAction?.actionKey || '-' }}</b>
          <small>{{ referenceAction?.pageKey || '-' }} / {{ referenceAction?.actionKey || '-' }}</small>
        </span>
        <el-button size="small" :loading="referenceLoading" @click="reloadActionReferences">刷新</el-button>
      </div>
      <el-table
        v-loading="referenceLoading"
        :data="actionReferences"
        row-key="referenceKey"
        size="small"
      >
        <el-table-column label="Workflow" min-width="210" show-overflow-tooltip>
          <template #default="{ row }">
            <strong>{{ row.workflowName || row.workflowKeySlug || row.workflowId || '-' }}</strong>
            <div class="reference-sub">{{ row.workflowKeySlug || row.workflowId || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="来源 Agent" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.agentName || row.agentKeySlug || row.agentId || '-' }}</span>
            <div class="reference-sub">{{ row.agentKeySlug || row.agentId || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="节点" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.nodeName || row.nodeId || '-' }}</span>
            <div class="reference-sub">{{ row.nodeId || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="版本/来源" width="150">
          <template #default="{ row }">
            <span>{{ row.workflowVersion || '-' }}</span>
            <div class="reference-sub">{{ row.graphSource || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="workflowStatus" label="状态" width="100">
          <template #default="{ row }">
            <CommonStatusTag :status="row.workflowStatus || '-'" />
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!referenceLoading && !actionReferences.length" description="暂无 Workflow 引用" :image-size="88" />
    </AppDialog>

    <AppDrawer
      v-model="credentialDrawerVisible"
      title="嵌入授权策略"
      size="920px"
      destroy-on-close
    >
      <div class="drawer-section-title">
        <span>授权策略</span>
        <el-button size="small" :loading="credentialLoading" @click="loadCredentialPolicies">刷新</el-button>
      </div>
      <el-table
        v-loading="credentialLoading"
        :data="credentialPolicies"
        row-key="id"
        size="small"
      >
        <el-table-column prop="appKey" label="接入密钥" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button link type="primary" @click="editCredentialPolicy(row)">{{ row.appKey }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="allowedOriginsJson" label="允许来源" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ formatOriginPolicy(row.allowedOriginsJson) }}</template>
        </el-table-column>
        <el-table-column prop="allowedAgentIdsJson" label="允许智能体" min-width="180" show-overflow-tooltip />
        <el-table-column prop="tokenTtlSeconds" label="令牌 TTL" width="90" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <CommonStatusTag :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="editCredentialPolicy(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </AppDrawer>

    <AppDialog
      v-model="credentialDialogVisible"
      title="编辑嵌入授权策略"
      width="720px"
      destroy-on-close
    >
      <el-form label-width="110px">
        <el-form-item label="接入密钥">
          <el-input :model-value="editingCredentialKey" disabled />
        </el-form-item>
        <el-form-item label="允许来源">
          <el-input v-model="credentialOriginsText" type="textarea" :rows="2" placeholder="留空时仅允许 localhost / 127.0.0.1 / ::1；生产环境请填写 https://app.example.com" />
        </el-form-item>
        <el-form-item label="允许智能体">
          <el-input v-model="credentialAgentsText" placeholder="agent-a,agent-b" />
        </el-form-item>
        <el-form-item label="令牌 TTL">
          <el-input-number v-model="credentialTtlSeconds" :min="60" :max="3600" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="credentialSaving" @click="saveCredentialPolicy">保存策略</el-button>
      </template>
    </AppDialog>

    <AppDrawer
      v-model="rendererDrawerVisible"
      title="嵌入渲染器注册表"
      size="760px"
      destroy-on-close
    >
      <el-form class="renderer-form" inline>
        <el-form-item label="渲染器">
          <el-input v-model="rendererForm.rendererKey" clearable placeholder="bzsdk.teamProfile" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="rendererForm.version" clearable placeholder="1.0.0" />
        </el-form-item>
        <el-form-item label="智能体">
          <el-input v-model="rendererAgentsText" clearable placeholder="agent-a,agent-b" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="rendererSaving" @click="saveRenderer">保存渲染器</el-button>
          <el-button :loading="rendererLoading" @click="loadRenderers">刷新</el-button>
        </el-form-item>
      </el-form>
      <el-table
        v-loading="rendererLoading"
        :data="renderers"
        row-key="id"
        size="small"
      >
        <el-table-column prop="rendererKey" label="渲染器" min-width="180" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="120" />
        <el-table-column prop="allowedAgentIdsJson" label="允许智能体" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <CommonStatusTag :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button link type="primary" @click="editRenderer(row)">编辑</el-button>
            <el-button link type="danger" @click="disableRenderer(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </AppDrawer>

    <PageMemoryWorkbench
      v-model="pageMemoryWorkbenchVisible"
      :page="selectedMemoryPage"
      :project-code="currentProjectCode"
    />
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import FilterBar from '@/components/common/FilterBar.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Cpu, Lock, Monitor, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import CommonStatusTag from '@/components/CommonStatusTag.vue'
import { formatRelativeTime } from '@/utils/relativeTime'
import PageMemoryWorkbench from './PageMemoryWorkbench.vue'
import {
  createEmbedRenderer,
  deletePageRegistry,
  disableEmbedRenderer as disableEmbedRendererApi,
  listEmbedCredentialPolicies,
  listEmbedRenderers,
  listPageActionReferences,
  listPageActionCatalog,
  listPageRegistry,
  updateEmbedCredentialPolicy,
  updateEmbedRenderer,
  type EmbedCredentialPolicyView,
  type EmbedRendererPayload,
  type EmbedRendererView,
  type PageActionReferenceView,
  type PageActionRegistryView,
  type PageRegistryView,
} from '@/api/embedOps'

const route = useRoute()
const router = useRouter()
const catalogLoading = ref(false)
const credentialLoading = ref(false)
const credentialSaving = ref(false)
const rendererLoading = ref(false)
const rendererSaving = ref(false)
const referenceLoading = ref(false)
const deletingPageId = ref<number | null>(null)
const pageRegistry = ref<PageRegistryView[]>([])
const pageActionCatalog = ref<PageActionRegistryView[]>([])
const actionReferences = ref<PageActionReferenceView[]>([])
const selectedPageKey = ref('')
const actionDrawerVisible = ref(false)
const referenceDialogVisible = ref(false)
const referenceAction = ref<PageActionRegistryView | null>(null)
const credentialPolicies = ref<EmbedCredentialPolicyView[]>([])
const renderers = ref<EmbedRendererView[]>([])
const credentialDrawerVisible = ref(false)
const credentialDialogVisible = ref(false)
const rendererDrawerVisible = ref(false)
const pageMemoryWorkbenchVisible = ref(false)
const selectedMemoryPage = ref<PageRegistryView | null>(null)
const currentProjectCode = computed(() => String(route.params.projectCode || route.query.projectCode || ''))
const editingCredentialId = ref<number | null>(null)
const editingCredentialKey = ref('')
const credentialOriginsText = ref('')
const credentialAgentsText = ref('')
const credentialTtlSeconds = ref(600)
const editingRendererId = ref<number | null>(null)
const rendererAgentsText = ref('')
const catalogFilters = reactive({
  pageKey: String(route.query.pageKey || ''),
  actionKeyword: '',
  status: '',
})
const rendererForm = reactive({
  rendererKey: '',
  name: '',
  version: '1.0.0',
  status: 'ACTIVE',
})

const filteredActions = computed(() => {
  const keyword = catalogFilters.actionKeyword.trim().toLowerCase()
  const status = catalogFilters.status
  return pageActionCatalog.value.filter((action) => {
    const matchedKeyword = !keyword || [
      action.actionKey,
      action.title,
      action.description,
      action.allowedAgentIdsJson,
    ].some((value) => String(value || '').toLowerCase().includes(keyword))
    const matchedStatus = !status || action.status === status
    return matchedKeyword && matchedStatus
  })
})

const filteredPages = computed(() => {
  const keyword = catalogFilters.pageKey.trim().toLowerCase()
  const shouldMatchAction = Boolean(catalogFilters.actionKeyword.trim() || catalogFilters.status)
  return pageRegistry.value.filter((page) => {
    const matchedPage = !keyword || [
      page.pageKey,
      page.name,
      page.routePattern,
      page.currentPageInstanceId,
    ].some((value) => String(value || '').toLowerCase().includes(keyword))
    const matchedAction = !shouldMatchAction || filteredActions.value.some((action) => action.pageKey === page.pageKey)
    return matchedPage && matchedAction
  })
})

const selectedPage = computed(() => {
  return filteredPages.value.find((page) => page.pageKey === selectedPageKey.value) || null
})

const selectedPageActions = computed(() => {
  if (!selectedPage.value) return []
  return filteredActions.value.filter((action) => action.pageKey === selectedPage.value?.pageKey)
})

const activeActionCount = computed(() => {
  return pageActionCatalog.value.filter((action) => action.status === 'ACTIVE').length
})

const lastSeenAt = computed(() => {
  const values = [
    ...pageRegistry.value.map((page) => page.lastSeenAt),
    ...pageActionCatalog.value.map((action) => action.lastSeenAt),
  ].filter(Boolean) as string[]
  if (!values.length) return '-'
  values.sort()
  return values[values.length - 1] || '-'
})

const metricItems = computed<MetricStripItem[]>(() => [
  {
    key: 'pages',
    label: '已接入页面',
    value: pageRegistry.value.length,
    iconKey: 'agent-page',
    tone: 'brand',
  },
  {
    key: 'actions',
    label: '页面动作',
    value: pageActionCatalog.value.length,
    iconKey: 'ai-semantic',
    tone: 'info',
  },
  {
    key: 'active-actions',
    label: '已启用动作',
    value: activeActionCount.value,
    iconKey: 'agent-ready',
    tone: 'success',
  },
  {
    key: 'last-seen',
    label: '最近上报',
    value: formatRelativeTime(lastSeenAt.value === '-' ? null : lastSeenAt.value),
    iconKey: 'sdk',
    tone: 'warning',
  },
])

watch(filteredPages, (pages) => {
  if (!pages.length || !pages.some((page) => page.pageKey === selectedPageKey.value)) {
    selectedPageKey.value = ''
    actionDrawerVisible.value = false
  }
})

async function loadCatalog() {
  catalogLoading.value = true
  try {
    const params = Object.fromEntries(Object.entries({
      projectCode: currentProjectCode.value,
      pageKey: catalogFilters.pageKey,
    }).filter(([, value]) => value))
    const [pages, actions] = await Promise.all([
      listPageRegistry(params),
      listPageActionCatalog(params),
    ])
    pageRegistry.value = pages.data || []
    pageActionCatalog.value = actions.data || []
  } finally {
    catalogLoading.value = false
  }
}

function resetCatalogFilters() {
  catalogFilters.pageKey = ''
  catalogFilters.actionKeyword = ''
  catalogFilters.status = ''
  void loadCatalog()
}

async function loadCredentialPolicies() {
  credentialLoading.value = true
  try {
    const params = currentProjectCode.value ? { projectCode: currentProjectCode.value } : {}
    const { data } = await listEmbedCredentialPolicies(params)
    credentialPolicies.value = data || []
  } finally {
    credentialLoading.value = false
  }
}

async function openCredentialDrawer() {
  credentialDrawerVisible.value = true
  await loadCredentialPolicies()
}

function editCredentialPolicy(row: EmbedCredentialPolicyView) {
  editingCredentialId.value = row.id
  editingCredentialKey.value = row.appKey
  credentialTtlSeconds.value = row.tokenTtlSeconds || 600
  credentialOriginsText.value = parseJsonArray(row.allowedOriginsJson).join(',')
  credentialAgentsText.value = parseJsonArray(row.allowedAgentIdsJson).join(',')
  credentialDialogVisible.value = true
}

async function saveCredentialPolicy() {
  if (!editingCredentialId.value) return
  credentialSaving.value = true
  try {
    await updateEmbedCredentialPolicy(editingCredentialId.value, {
      allowedOrigins: splitCsv(credentialOriginsText.value),
      allowedAgentIds: splitCsv(credentialAgentsText.value),
      tokenTtlSeconds: credentialTtlSeconds.value,
      status: 'ACTIVE',
    })
    await loadCredentialPolicies()
    credentialDialogVisible.value = false
  } finally {
    credentialSaving.value = false
  }
}

async function loadRenderers() {
  rendererLoading.value = true
  try {
    const params = currentProjectCode.value ? { appId: currentProjectCode.value } : {}
    const { data } = await listEmbedRenderers(params)
    renderers.value = data || []
  } finally {
    rendererLoading.value = false
  }
}

async function openRendererDrawer() {
  rendererDrawerVisible.value = true
  await loadRenderers()
}

async function saveRenderer() {
  rendererSaving.value = true
  try {
    const payload: EmbedRendererPayload = {
      appId: currentProjectCode.value,
      rendererKey: rendererForm.rendererKey.trim(),
      name: rendererForm.name.trim() || rendererForm.rendererKey.trim(),
      version: rendererForm.version.trim() || '1.0.0',
      inputSchema: {},
      allowedAgentIds: rendererAgentsText.value.split(',').map((item) => item.trim()).filter(Boolean),
      status: rendererForm.status,
    }
    if (editingRendererId.value) {
      await updateEmbedRenderer(editingRendererId.value, payload)
    } else {
      await createEmbedRenderer(payload)
    }
    editingRendererId.value = null
    await loadRenderers()
  } finally {
    rendererSaving.value = false
  }
}

function editRenderer(row: EmbedRendererView) {
  editingRendererId.value = row.id
  rendererForm.rendererKey = row.rendererKey
  rendererForm.name = row.name || row.rendererKey
  rendererForm.version = row.version || '1.0.0'
  rendererForm.status = row.status || 'ACTIVE'
  try {
    const agents = JSON.parse(row.allowedAgentIdsJson || '[]')
    rendererAgentsText.value = Array.isArray(agents) ? agents.join(',') : ''
  } catch {
    rendererAgentsText.value = ''
  }
}

async function disableRenderer(row: EmbedRendererView) {
  await disableEmbedRendererApi(row.id)
  await loadRenderers()
}

async function openActionReferences(row: PageActionRegistryView) {
  referenceAction.value = row
  referenceDialogVisible.value = true
  await reloadActionReferences()
}

async function reloadActionReferences() {
  if (!referenceAction.value?.id) return
  referenceLoading.value = true
  try {
    const { data } = await listPageActionReferences(referenceAction.value.id)
    actionReferences.value = (data || []).map((item, index) => ({
      ...item,
      referenceKey: `${item.workflowId || 'workflow'}-${item.workflowVersionId || 'version'}-${item.nodeId || index}`,
    })) as PageActionReferenceView[]
  } catch (error) {
    actionReferences.value = []
    ElMessage.error(error instanceof Error ? error.message : '加载 Workflow 引用失败')
  } finally {
    referenceLoading.value = false
  }
}

function goSessionAudit() {
  router.push({
    name: 'EmbedSessionAudit',
    params: { projectCode: currentProjectCode.value },
  })
}

function selectPage(pageKey: string) {
  selectedPageKey.value = pageKey
  actionDrawerVisible.value = true
}

function openPageMemoryWorkbench(page: PageRegistryView) {
  selectedMemoryPage.value = page
  pageMemoryWorkbenchVisible.value = true
}

function previewPage(page: PageRegistryView) {
  const routePath = page.routePattern || ''
  const origin = page.origin || ''
  const url = origin ? `${origin.replace(/\/$/, '')}${routePath.startsWith('/') ? routePath : `/${routePath}`}` : routePath
  if (url) {
    window.open(url, '_blank', 'noopener,noreferrer')
  }
}

async function confirmDeletePage(page: PageRegistryView) {
  const pageLabel = page.name || page.pageKey
  try {
    await ElMessageBox.confirm(
      `确认删除页面「${pageLabel}」吗？将同时删除该页面的动作目录、嵌入式会话绑定及页面助手接入进度，此操作不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deletingPageId.value = page.id
  try {
    await deletePageRegistry(page.id)
    if (selectedPageKey.value === page.pageKey) {
      selectedPageKey.value = ''
      actionDrawerVisible.value = false
    }
    ElMessage.success('页面及关联数据已删除')
    await loadCatalog()
  } finally {
    deletingPageId.value = null
  }
}

function actionCount(pageKey: string): number {
  return filteredActions.value.filter((action) => action.pageKey === pageKey).length
}

function splitCsv(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}

function parseJsonArray(value?: string): string[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : []
  } catch {
    return []
  }
}

function formatJsonArray(value?: string): string {
  return parseJsonArray(value).join(', ')
}

function formatOriginPolicy(value?: string): string {
  const origins = parseJsonArray(value)
  return origins.length ? origins.join(', ') : '开发默认：localhost / 127.0.0.1 / ::1'
}

onMounted(loadCatalog)
</script>

<style scoped lang="scss">
.embed-ops-shell.project-list-card-mode :deep(.data-table-shell__body) {
  padding: 4px 4px 8px;
}

.embed-ops-shell :deep(.project-list-card) {
  min-height: 228px;
}

.embed-ops-shell :deep(.project-list-filter-bar .el-form-item:not(:first-child) .el-input) {
  width: 100% !important;
}

.project-list-card.is-active {
  border-color: color-mix(in srgb, var(--brand-primary) 42%, var(--border-subtle));
  box-shadow:
    var(--inner-highlight),
    var(--shadow-md),
    inset 3px 0 0 var(--brand-primary);
}

.embed-ops-card-time {
  font-size: 13px !important;
  font-weight: 650 !important;
  letter-spacing: -0.01em;
}

.drawer-page-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 18px;
  padding: 14px;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background: color-mix(in srgb, var(--surface-solid-panel) 88%, transparent);
}

.drawer-page-summary > div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.drawer-page-summary span,
.drawer-section-title span {
  color: var(--text-muted);
  font-size: 12px;
}

.drawer-page-summary strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.drawer-section-title span {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 700;
}

.drawer-action-table {
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  overflow: hidden;
}

.reference-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  background: color-mix(in srgb, var(--surface-solid-control) 72%, transparent);
}

.reference-head span {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.reference-head b {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reference-head small,
.reference-sub {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.renderer-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-bottom: 14px;
}

.renderer-form :deep(.el-form-item) {
  margin: 0;
}

@media (max-width: 980px) {
  .drawer-page-summary {
    grid-template-columns: 1fr;
  }
}
</style>

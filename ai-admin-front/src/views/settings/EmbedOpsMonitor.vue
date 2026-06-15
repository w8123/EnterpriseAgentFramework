<template>
  <div class="embed-ops-page">
    <div class="page-hero">
      <div class="page-head">
        <div>
          <h1>前端页面管理</h1>
          <p>管理当前项目已接入的业务页面、页面动作、嵌入授权和会话审计。</p>
        </div>
        <div class="page-actions">
          <el-button :icon="ChatDotRound" @click="goSessionAudit">嵌入式会话审计</el-button>
          <el-button :icon="Lock" @click="openCredentialDrawer">嵌入授权</el-button>
          <el-button :icon="Cpu" @click="openRendererDrawer">嵌入渲染器</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </div>

    <section class="stats-row">
      <div v-for="item in accessStats" :key="item.label" class="stat-card">
        <span class="stat-icon" :class="item.tone">
          <el-icon><component :is="item.icon" /></el-icon>
        </span>
        <span>
          <small>{{ item.label }}</small>
          <strong>{{ item.value }}</strong>
        </span>
      </div>
    </section>

    <el-card class="catalog-card" shadow="never">
      <template #header>
        <div class="card-head">
          <span>页面动作目录</span>
          <el-button size="small" :icon="Refresh" :loading="catalogLoading" @click="loadCatalog">刷新目录</el-button>
        </div>
      </template>
      <el-form class="catalog-filters" inline>
        <el-form-item label="页面">
          <el-input v-model="catalogFilters.pageKey" clearable placeholder="pageKey / 路由" />
        </el-form-item>
        <el-form-item label="动作">
          <el-input v-model="catalogFilters.actionKeyword" clearable placeholder="标题 / actionKey" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="catalogFilters.status" clearable placeholder="全部状态" style="width: 130px">
            <el-option label="已启用" value="ACTIVE" />
            <el-option label="已移除" value="REMOVED" />
            <el-option label="已禁用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="catalogLoading" @click="loadCatalog">查询目录</el-button>
        </el-form-item>
      </el-form>
      <div class="catalog-summary">
        <span>业务页面</span>
        <el-tag effect="plain">{{ filteredPages.length }} 个页面</el-tag>
      </div>
      <div v-if="filteredPages.length" class="page-card-grid">
        <article
          v-for="page in filteredPages"
          :key="page.pageKey"
          class="page-card"
          :class="{ active: page.pageKey === selectedPageKey && actionDrawerVisible }"
          @click="selectPage(page.pageKey)"
        >
          <span class="page-card-main">
            <span class="page-card-title">
              <strong>{{ page.name || page.pageKey }}</strong>
              <CommonStatusTag :status="page.status" />
            </span>
          </span>
          <span class="page-field">
            <b>pageKey</b>
            <span>{{ page.pageKey }}</span>
          </span>
          <span class="page-field">
            <b>路由</b>
            <span>{{ page.routePattern || '-' }}</span>
          </span>
          <span class="page-card-footer">
            <span>
              <el-icon><Grid /></el-icon>
              {{ actionCount(page.pageKey) }} 个动作
            </span>
            <span>
              <el-icon><Clock /></el-icon>
              最近上报：{{ page.lastSeenAt || '-' }}
            </span>
            <span class="page-card-actions">
              <el-button size="small" type="primary" @click.stop="selectPage(page.pageKey)">动作详情</el-button>
              <el-button size="small" @click.stop="previewPage(page)">预览页面</el-button>
            </span>
          </span>
        </article>
      </div>
      <el-empty v-else description="暂无页面" :image-size="88" />
    </el-card>

    <el-drawer
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
          <strong>{{ selectedPage.lastSeenAt || '-' }}</strong>
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
        <el-table-column prop="lastSeenAt" label="最近上报" width="150" />
        <el-table-column prop="description" label="描述" min-width="170" show-overflow-tooltip />
        <el-table-column label="引用" width="96" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openActionReferences(row)">Workflow</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="当前页面暂无匹配动作" :image-size="88" />
    </el-drawer>

    <el-dialog
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
        <el-table-column label="入口 Agent" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.agentName || row.agentKeySlug || row.agentId || '-' }}</span>
            <div class="reference-sub">{{ row.agentKeySlug || row.agentId || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="绑定" width="150">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.bindingType || '-' }}</el-tag>
            <el-tag class="ml-6" size="small" :type="row.bindingEnabled ? 'success' : 'info'" effect="plain">
              {{ row.bindingEnabled ? '启用' : '未启用' }}
            </el-tag>
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
    </el-dialog>

    <el-drawer
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
    </el-drawer>

    <el-dialog
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
    </el-dialog>

    <el-drawer
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
    </el-drawer>

  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Clock, Cpu, Document, Grid, Lock, MagicStick, Refresh, Select } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import CommonStatusTag from '@/components/CommonStatusTag.vue'
import {
  createEmbedRenderer,
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
const loading = ref(false)
const catalogLoading = ref(false)
const credentialLoading = ref(false)
const credentialSaving = ref(false)
const rendererLoading = ref(false)
const rendererSaving = ref(false)
const referenceLoading = ref(false)
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

const accessStats = computed(() => [
  {
    label: '已接入页面',
    value: pageRegistry.value.length,
    icon: Document,
    tone: 'blue',
  },
  {
    label: '页面动作',
    value: pageActionCatalog.value.length,
    icon: MagicStick,
    tone: 'green',
  },
  {
    label: '已启用动作',
    value: activeActionCount.value,
    icon: Select,
    tone: 'violet',
  },
  {
    label: '最近上报',
    value: lastSeenAt.value,
    icon: Clock,
    tone: 'orange',
  },
])

watch(filteredPages, (pages) => {
  if (!pages.length || !pages.some((page) => page.pageKey === selectedPageKey.value)) {
    selectedPageKey.value = ''
    actionDrawerVisible.value = false
  }
})

async function load() {
  loading.value = true
  try {
    await loadCatalog()
  } finally {
    loading.value = false
  }
}

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
      referenceKey: `${item.workflowId || 'workflow'}-${item.bindingId || 'binding'}-${item.nodeId || index}`,
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

function previewPage(page: PageRegistryView) {
  const routePath = page.routePattern || ''
  const origin = page.origin || ''
  const url = origin ? `${origin.replace(/\/$/, '')}${routePath.startsWith('/') ? routePath : `/${routePath}`}` : routePath
  if (url) {
    window.open(url, '_blank', 'noopener,noreferrer')
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

onMounted(load)
</script>

<style scoped>
.embed-ops-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: calc(100vh - 96px);
  margin: -16px -18px -24px;
  padding: 24px 32px 36px;
}

.page-hero {
  padding: 18px 20px;
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--bg-card) 90%, rgb(var(--brand-primary-rgb) / 0.08)), var(--bg-card));
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.05);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.page-head h1 {
  margin: 0;
  color: var(--text-primary);
  font-size: 24px;
  font-weight: 800;
  line-height: 1.25;
}

.page-head p {
  max-width: 560px;
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.page-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.stat-card {
  min-height: 68px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--bg-card) 88%, #ffffff 12%), var(--bg-card));
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.04);
}

.stat-icon {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 8px;
  font-size: 19px;
}

.stat-icon.blue {
  background: rgba(59, 130, 246, 0.12);
  color: #2563eb;
}

.stat-icon.green {
  background: rgba(34, 197, 94, 0.12);
  color: #16a34a;
}

.stat-icon.violet {
  background: rgba(124, 92, 255, 0.13);
  color: #6d5dfc;
}

.stat-icon.orange {
  background: rgba(245, 158, 11, 0.14);
  color: #d97706;
}

.stat-card span:last-child {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.stat-card small {
  color: var(--text-secondary);
  font-size: 13px;
}

.stat-card strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 18px;
  font-weight: 750;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-card {
  overflow: hidden;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 16px;
  font-weight: 750;
}

.catalog-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 16px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  background: color-mix(in srgb, var(--bg-tertiary) 42%, transparent);
}

.catalog-filters :deep(.el-form-item) {
  margin: 0;
}

.catalog-filters :deep(.el-input),
.catalog-filters :deep(.el-select) {
  width: 220px;
}

.catalog-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--text-primary);
  font-weight: 700;
}

.page-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}

.page-card {
  position: relative;
  width: 100%;
  min-width: 0;
  display: grid;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--bg-secondary) 94%, rgb(var(--brand-primary-rgb) / 0.08)), var(--bg-secondary));
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.page-card:hover,
.page-card.active {
  border-color: var(--accent-color);
  box-shadow: 0 14px 32px rgba(59, 91, 255, 0.12);
}

.page-card:hover {
  transform: translateY(-1px);
}

.page-card.active::after {
  content: "✓";
  position: absolute;
  top: 0;
  right: 0;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 0 8px 0 8px;
  background: var(--accent-color);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
}

.page-card-main {
  min-width: 0;
  display: grid;
  gap: 8px;
}

.page-card-title {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.page-card-title strong,
.action-main strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-field {
  min-width: 0;
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
}

.page-field b {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
}

.page-field span,
.action-main small,
.action-desc {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-card-footer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 14px;
  justify-content: flex-start;
  margin-top: 4px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-glass);
}

.page-card-footer span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-secondary);
  font-size: 12px;
}

.page-card-actions {
  display: inline-flex !important;
  align-items: center;
  gap: 8px !important;
  margin-left: auto;
}

.drawer-page-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 18px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--accent-color) 24%, var(--border-glass));
  border-radius: 8px;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--bg-card) 88%, rgb(var(--brand-primary-rgb) / 0.08)), var(--bg-card));
}

.drawer-page-summary > div {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 4px;
}

.drawer-page-summary span,
.drawer-section-title span {
  color: var(--text-secondary);
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
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  overflow: hidden;
}

.reference-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border-glass);
  border-radius: 8px;
  background: color-mix(in srgb, var(--bg-tertiary) 44%, transparent);
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
  color: var(--text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ml-6 {
  margin-left: 6px;
}

.action-main {
  min-width: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.action-main > div {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.action-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.action-meta span {
  min-width: 0;
  display: grid;
  gap: 3px;
  padding: 8px 10px;
  overflow: hidden;
  border-radius: 7px;
  background: var(--bg-muted);
  color: var(--text-primary);
  font-size: 12px;
  text-overflow: ellipsis;
}

.action-meta b {
  color: var(--text-secondary);
  font-weight: 600;
}

@media (max-width: 980px) {
  .embed-ops-page {
    margin: -16px -18px -24px;
    padding: 18px;
  }

  .page-hero {
    padding: 16px;
  }

  .page-head {
    display: grid;
  }

  .page-actions {
    justify-content: flex-start;
  }

  .stats-row,
  .page-card-grid {
    grid-template-columns: 1fr;
  }

  .catalog-filters :deep(.el-input),
  .catalog-filters :deep(.el-select) {
    width: 100%;
  }

  .action-meta {
    grid-template-columns: 1fr;
  }

  .drawer-page-summary {
    grid-template-columns: 1fr;
  }
}

/* SDK wizard aligned skin: tech-violet glass for frontend page access management. */
:global(.main-layout.registry-shell:has(.embed-ops-page) .main-content) {
  background:
    radial-gradient(circle at 78% 0%, rgb(var(--brand-selected-rgb) / 0.92), transparent 28%),
    radial-gradient(circle at 20% 18%, rgb(var(--brand-selected-rgb) / 0.38), transparent 34%),
    radial-gradient(circle at 72% 72%, rgb(var(--brand-hover-rgb) / 0.16), transparent 32%),
    var(--brand-page-bg) !important;
}

:global(.main-layout.registry-shell:has(.embed-ops-page) .main-content::before) {
  opacity: 0 !important;
}

:global(.main-layout.registry-shell:has(.embed-ops-page) .topbar) {
  border-bottom-color: rgba(255, 255, 255, 0.5) !important;
  background:
    var(--brand-topbar-bg) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.5), 0 16px 34px rgb(var(--brand-primary-rgb) / 0.18) !important;
  backdrop-filter: blur(22px) saturate(1.1) !important;
}

:global(.main-layout.registry-shell:has(.embed-ops-page) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.embed-ops-page) .breadcrumb-area .el-breadcrumb__separator) {
  color: rgba(255, 255, 255, 0.84) !important;
}

:global(.main-layout.registry-shell:has(.embed-ops-page) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #ffffff !important;
}

:global(.main-layout.registry-shell:has(.embed-ops-page) .topbar-btn) {
  border-color: rgba(255, 255, 255, 0.7) !important;
  background: rgba(255, 255, 255, 0.48) !important;
  color: #4338ca !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 8px 20px rgb(var(--brand-primary-rgb) / 0.14) !important;
  backdrop-filter: blur(16px) !important;
}

:global(.main-layout.registry-shell:has(.embed-ops-page) .user-avatar) {
  box-shadow: 0 10px 24px rgb(var(--brand-active-rgb) / 0.24) !important;
}

.embed-ops-page {
  position: relative;
  gap: 12px;
  min-height: calc(100vh - 72px);
  margin: 0;
  padding: 8px 22px 18px;
  overflow: hidden;
  background:
    linear-gradient(rgba(255, 255, 255, 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.13) 1px, transparent 1px),
    radial-gradient(circle at 78% 0%, rgb(var(--brand-selected-rgb) / 0.92), transparent 28%),
    radial-gradient(circle at 20% 18%, rgb(var(--brand-selected-rgb) / 0.38), transparent 34%),
    radial-gradient(circle at 72% 72%, rgb(var(--brand-hover-rgb) / 0.16), transparent 32%),
    var(--brand-page-bg) !important;
  background-size: 56px 56px, 56px 56px, auto, auto, auto, auto;
  color: #11183a;
}

.embed-ops-page::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  background:
    radial-gradient(circle at 90% 18%, rgba(255, 255, 255, 0.78), transparent 23%),
    linear-gradient(110deg, transparent 0%, rgb(var(--brand-hover-rgb) / 0.12) 38%, transparent 66%);
  opacity: 0.9;
  pointer-events: none;
}

.embed-ops-page::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  z-index: 0;
  width: min(520px, 42vw);
  height: 380px;
  background:
    linear-gradient(rgb(var(--brand-hover-rgb) / 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgb(var(--brand-primary-rgb) / 0.1) 1px, transparent 1px);
  background-size: 28px 28px;
  mask-image: linear-gradient(135deg, transparent 0%, #000 45%, #000 100%);
  opacity: 0.68;
  pointer-events: none;
}

.page-hero,
.stats-row,
.catalog-card {
  position: relative;
  z-index: 1;
}

.page-hero {
  min-height: 112px;
  padding: 18px 26px;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.66) !important;
  border-radius: 8px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.6), rgb(var(--brand-selected-rgb) / 0.38)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 22px 48px rgb(var(--brand-primary-rgb) / 0.14) !important;
  backdrop-filter: blur(24px) saturate(1.08) !important;
}

.page-hero::after {
  content: '';
  position: absolute;
  right: 5%;
  top: 28%;
  width: 46%;
  height: 64%;
  background:
    radial-gradient(circle at 12% 36%, rgba(255, 255, 255, 0.95) 0 2px, transparent 3px),
    radial-gradient(circle at 42% 20%, rgba(255, 255, 255, 0.86) 0 2px, transparent 3px),
    radial-gradient(circle at 68% 44%, rgba(255, 255, 255, 0.8) 0 2px, transparent 3px),
    linear-gradient(168deg, transparent 0%, rgba(255, 255, 255, 0.5) 47%, transparent 53%),
    repeating-linear-gradient(168deg, transparent 0 10px, rgba(255, 255, 255, 0.16) 11px 12px);
  opacity: 0.82;
  pointer-events: none;
}

.page-head {
  position: relative;
  z-index: 1;
  align-items: center;
}

.page-head h1 {
  color: #11183a !important;
  font-size: 29px;
  font-weight: 850;
  line-height: 1.12;
  text-shadow: none !important;
}

.page-head p {
  max-width: 660px;
  color: #4f5f81 !important;
  font-size: 14px;
}

.page-actions :deep(.el-button) {
  height: 38px;
  border-color: rgba(148, 163, 184, 0.28) !important;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.68) !important;
  color: #27364f !important;
  font-weight: 750;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 14px 32px rgb(var(--brand-primary-rgb) / 0.1) !important;
  backdrop-filter: blur(16px);
}

.stats-row {
  gap: 12px;
}

.stat-card,
.catalog-card,
.catalog-filters,
.page-card,
.drawer-page-summary,
.drawer-action-table,
.action-meta span {
  border-color: rgb(var(--brand-selected-rgb) / 0.48) !important;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.7), rgb(var(--brand-selected-rgb) / 0.34)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 14px 30px rgb(var(--brand-primary-rgb) / 0.08) !important;
  backdrop-filter: blur(18px) saturate(1.04);
}

.stat-card {
  min-height: 76px;
  padding: 14px 16px;
  overflow: hidden;
}

.stat-icon {
  border: 1px solid rgb(var(--brand-hover-rgb) / 0.24);
  background: rgba(238, 242, 255, 0.72) !important;
  color: var(--brand-primary) !important;
}

.stat-icon.green {
  border-color: rgba(34, 197, 94, 0.22);
  background: rgba(220, 252, 231, 0.74) !important;
  color: #16a34a !important;
}

.stat-icon.orange {
  border-color: rgba(245, 158, 11, 0.24);
  background: rgba(254, 243, 199, 0.74) !important;
  color: #b45309 !important;
}

.stat-card small,
.page-field b,
.page-card-footer span,
.action-main small,
.action-desc,
.drawer-page-summary span {
  color: #4f5f81 !important;
}

.stat-card strong,
.card-head,
.catalog-summary,
.page-card-title strong,
.page-field span,
.drawer-page-summary strong,
.drawer-section-title span,
.action-main strong {
  color: #11183a !important;
}

.catalog-card :deep(.el-card__header) {
  border-bottom-color: rgb(var(--brand-selected-rgb) / 0.42);
  background: rgba(255, 255, 255, 0.22);
}

.catalog-card :deep(.el-card__body) {
  background: transparent;
}

.catalog-filters :deep(.el-input__wrapper),
.catalog-filters :deep(.el-select__wrapper),
.renderer-form :deep(.el-input__wrapper),
.renderer-form :deep(.el-select__wrapper) {
  background: rgba(255, 255, 255, 0.72);
  box-shadow:
    0 0 0 1px rgb(var(--brand-selected-rgb) / 0.42) inset,
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
}

.catalog-filters :deep(.el-button--primary),
.page-card-actions :deep(.el-button--primary),
.card-head :deep(.el-button--primary),
.page-actions :deep(.el-button--primary) {
  border-color: rgb(var(--brand-primary-rgb) / 0.34) !important;
  background:
    linear-gradient(135deg, var(--brand-primary), var(--brand-hover)),
    radial-gradient(circle at 18% 0%, rgba(255, 255, 255, 0.34), transparent 42%) !important;
  color: #ffffff !important;
  box-shadow: 0 12px 28px rgb(var(--brand-primary-rgb) / 0.24) !important;
}

.catalog-summary :deep(.el-tag),
.drawer-section-title :deep(.el-tag) {
  border-color: rgb(var(--brand-hover-rgb) / 0.28) !important;
  background: rgba(238, 242, 255, 0.68) !important;
  color: var(--brand-active) !important;
}

.page-card {
  overflow: hidden;
}

.page-card::before {
  content: '';
  position: absolute;
  right: -16px;
  bottom: -34px;
  width: 110px;
  height: 110px;
  border-radius: 18px;
  background:
    linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.18), rgb(var(--brand-hover-rgb) / 0.1)),
    linear-gradient(rgb(var(--brand-primary-rgb) / 0.18) 1px, transparent 1px),
    linear-gradient(90deg, rgb(var(--brand-hover-rgb) / 0.14) 1px, transparent 1px);
  background-size: auto, 18px 18px, 18px 18px;
  opacity: 0.62;
  transform: rotate(-25deg);
  pointer-events: none;
}

.page-card:hover,
.page-card.active {
  border-color: rgb(var(--brand-hover-rgb) / 0.58) !important;
  box-shadow:
    0 16px 32px rgb(var(--brand-primary-rgb) / 0.14),
    inset 4px 0 0 rgb(var(--brand-primary-rgb) / 0.7) !important;
}

.page-card.active::after {
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-hover));
}

.page-card-footer {
  border-top-color: rgb(var(--brand-selected-rgb) / 0.42);
}

@media (max-width: 980px) {
  .embed-ops-page {
    margin: 0;
    padding: 14px;
  }

  .page-hero {
    min-height: auto;
  }
}
</style>

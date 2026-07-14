<template>
  <div class="registry-project-page project-workbench-page workbench-page--list" :class="{ 'is-dark': theme === 'dark' }">
    <CollapsibleHeaderRegion :collapsed="isProjectHeaderCollapsed">
      <PageHeader
        variant="overview"
        domain="project"
        title="项目管理"
        description="统一管理 AI 项目的注册、SDK 接入、API 接入与能力扫描。"
        :collapsed="isProjectHeaderCollapsed"
      >
        <template #tags>
          <el-tag effect="light">项目中心</el-tag>
          <el-tag type="success" effect="light">当前 {{ projects.length }} 个项目</el-tag>
          <el-tag type="info" effect="light">SDK Starter 可用</el-tag>
        </template>
        <template #actions>
          <el-button type="primary" :icon="Plus" @click="openAccessDialog('sdk')">
            接入项目
          </el-button>
        </template>
      </PageHeader>

      <template #summary>
        <div class="metric-strip">
          <template v-for="(metric, index) in metrics" :key="metric.label">
            <div v-if="index > 0" class="metric-divider" aria-hidden="true" />
            <div class="metric-segment">
              <MetricIconBg class="metric-segment-icon" :icon-key="metric.iconKey" :tone="metric.tone" />
              <div class="metric-content">
                <div class="metric-line">
                  <span class="metric-label">{{ metric.label }}</span>
                  <em v-if="metric.delta" class="metric-delta" :class="metric.deltaTone">{{ metric.delta }}</em>
                </div>
                <strong>{{ metric.value }}</strong>
                <small>{{ metric.caption }}</small>
              </div>
            </div>
          </template>
        </div>
      </template>
    </CollapsibleHeaderRegion>

    <el-card class="project-card workbench-list-surface" shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          class="search-input"
          clearable
          :prefix-icon="Search"
          placeholder="搜索项目名称、描述、负责人"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="kindFilter" clearable placeholder="接入方式">
          <el-option label="SDK 接入" value="REGISTERED" />
          <el-option label="API / 扫描接入" value="SCAN" />
          <el-option label="混合接入" value="HYBRID" />
        </el-select>
        <el-select v-model="statusFilter" clearable placeholder="项目状态">
          <el-option label="已创建" value="created" />
          <el-option label="扫描中" value="scanning" />
          <el-option label="已接入" value="scanned" />
          <el-option label="异常" value="failed" />
        </el-select>
        <el-button class="toolbar-reset" @click="resetFilters">重置</el-button>
        <el-button class="toolbar-search" :icon="Search" :loading="loading" @click="handleSearch">搜索</el-button>
      </div>

      <div class="project-table-shell">
        <el-table
          v-loading="loading"
          :data="pagedProjects"
          row-key="id"
          class="project-table"
          :max-height="projectTableMaxHeight"
        >
          <el-table-column label="项目名称" min-width="190">
            <template #default="{ row }">
              <button type="button" class="project-name-cell project-name-cell-btn" @click="goDetail(row)">
                <div class="project-avatar" :class="avatarClass(row.projectKind)">{{ projectInitial(row.name) }}</div>
                <div>
                  <strong>{{ row.name }}</strong>
                  <span>{{ row.projectCode || `ID ${row.id}` }}</span>
                </div>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="项目描述" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="muted">{{ projectDescription(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="接入方式" width="100">
            <template #default="{ row }">
              <el-tag :type="kindTagType(row.projectKind)" effect="light">
                {{ formatProjectKindLabel(row.projectKind || 'SCAN') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <span class="status-pill" :class="`status-${row.status || 'created'}`">
                <i />
                {{ statusLabel(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="API 数量" width="86" align="center">
            <template #default="{ row }">{{ apiCountOf(row) }}</template>
          </el-table-column>
          <el-table-column label="SDK 版本" width="96" align="center">
            <template #default="{ row }">{{ sdkVersionLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="最近扫描时间" width="124">
            <template #default="{ row }">
              <span class="muted">{{ formatDate(row.lastScannedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="负责人" width="110">
            <template #default="{ row }">
              <div class="owner-cell">
                <el-avatar :size="22">{{ projectInitial(row.owner || row.name) }}</el-avatar>
                <span>{{ row.owner || '未分配' }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="!loading && filteredProjects.length === 0" class="empty-state">
          <div class="empty-main">
            <div class="empty-illustration" aria-hidden="true">
              <img class="empty-illustration-img" src="/智能化.svg" alt="" />
            </div>
            <div class="empty-body">
              <div class="empty-copy">
                <h3>还没有接入任何 AI 项目</h3>
                <p>你可以通过创建接入项目，选择 SDK 接入或扫描接入来发现 AI 能力。</p>
              </div>
              <div class="empty-actions">
                <el-button type="primary" :icon="Plus" @click="openAccessDialog('sdk')">接入项目</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="filteredProjects.length > 0" class="table-footer">
        <span>共 {{ filteredProjects.length }} 条</span>
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          class="registry-pagination"
          background
          layout="prev, pager, next, sizes"
          :page-sizes="[5, 10, 20, 50]"
          :total="filteredProjects.length"
        />
      </div>
    </el-card>

    <AppDialog
      v-model="accessDialogVisible"
      title="接入项目"
      description="选择 SDK 接入或扫描接入，完成项目身份、连接信息与能力发现配置。"
      width="760px"
      destroy-on-close
    >
      <el-tabs v-model="accessDialogTab" class="access-dialog-tabs">
        <el-tab-pane label="SDK 接入" name="sdk">
          <el-form :model="sdkForm" label-width="120px">
            <el-form-item label="项目名称" required>
              <el-input v-model="sdkForm.name" placeholder="如：智能客服平台" />
            </el-form-item>
            <el-form-item label="项目编码" required>
              <el-input v-model="sdkForm.projectCode" placeholder="如：customer-service" />
            </el-form-item>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="环境">
                  <el-input v-model="sdkForm.environment" placeholder="dev / test / prod" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="负责人">
                  <el-input v-model="sdkForm.owner" placeholder="负责人姓名" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="可见性">
              <el-select v-model="sdkForm.visibility" style="width: 100%">
                <el-option v-for="opt in VISIBILITY_SELECT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="Base URL" required>
              <el-input v-model="sdkForm.baseUrl" placeholder="http://localhost:8080" />
            </el-form-item>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="App Key" required>
                  <el-input v-model="sdkForm.appKey" placeholder="业务系统 Registry App Key" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="App Secret" required>
                  <el-input v-model="sdkForm.appSecret" show-password placeholder="业务系统 Registry App Secret" />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="扫描接入" name="scan">
          <el-form label-width="120px">
            <el-form-item label="项目名称" required>
              <el-input v-model="scanForm.name" placeholder="如 legacy-crm" />
            </el-form-item>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="项目编码">
                  <el-input v-model="scanForm.projectCode" placeholder="如 order-service" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="接入方式">
                  <el-select v-model="scanForm.projectKind" style="width: 100%">
                    <el-option v-for="opt in PROJECT_KIND_SELECT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="环境">
                  <el-input v-model="scanForm.environment" placeholder="dev / test / prod" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="负责人">
                  <el-input v-model="scanForm.owner" placeholder="负责人姓名" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="项目域名" required>
                  <el-input v-model="scanForm.baseUrl" placeholder="http://localhost:18602" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="可见性">
                  <el-select v-model="scanForm.visibility" style="width: 100%">
                    <el-option v-for="opt in VISIBILITY_SELECT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="扫描路径" :required="scanForm.projectKind !== 'REGISTERED'">
              <el-input v-model="scanForm.scanPath" placeholder="服务器上的绝对路径" />
              <div v-if="scanForm.projectKind === 'REGISTERED'" class="form-hint">SDK 接入项目可不配置扫描路径。</div>
            </el-form-item>
            <el-form-item label="扫描方式" required>
              <el-radio-group v-model="scanForm.scanType">
                <el-radio value="openapi">OpenAPI</el-radio>
                <el-radio value="controller">Controller</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="scanForm.scanType === 'openapi'" label="规范文件">
              <el-input v-model="scanForm.specFile" placeholder="可选，相对 scanPath；留空自动发现" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="accessDialogVisible = false">取消</el-button>
        <el-button
          v-if="accessDialogTab === 'sdk'"
          type="primary"
          :loading="saving"
          @click="saveSdkProject"
        >
          保存并生成接入配置
        </el-button>
        <el-button
          v-else
          type="primary"
          :loading="saving"
          @click="saveScanProject"
        >
          保存
        </el-button>
      </template>
    </AppDialog>
  </div>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Plus,
  Search,
} from '@element-plus/icons-vue'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import {
  createScanProject,
  getScanProjects,
  type ScanProjectListQuery,
} from '@/api/scanProject'
import { registerRegistryProject } from '@/api/registry'
import type { ScanProject, ScanProjectUpsertRequest } from '@/types/scanProject'
import type { RegistryProjectRegisterRequest } from '@/types/registry'
import { useProjectStore } from '@/store/project'
import { useTheme } from '@/composables/useTheme'
import {
  PROJECT_KIND_SELECT_OPTIONS,
  VISIBILITY_SELECT_OPTIONS,
  formatProjectKindLabel,
  formatScanStatusLabel,
} from '@/utils/projectLabels'

const router = useRouter()
const projectStore = useProjectStore()
const { theme } = useTheme()

const loading = ref(false)
const saving = ref(false)
const projects = ref<ScanProject[]>([])
const keyword = ref('')
const kindFilter = ref<ScanProjectListQuery['projectKind']>('')
const statusFilter = ref<ScanProjectListQuery['status']>('')
const currentPage = ref(1)
const pageSize = ref(10)
const accessDialogVisible = ref(false)
const accessDialogTab = ref<'sdk' | 'scan'>('sdk')

/** 限制表格主体高度，使横向滚动条落在视口内（靠近浏览器窗口底部），无需先滚到卡片最底 */
const projectTableMaxHeight = ref(480)

let tableHeightRaf = 0
function scheduleUpdateProjectTableMaxHeight() {
  if (typeof window === 'undefined') return
  if (tableHeightRaf) return
  tableHeightRaf = requestAnimationFrame(() => {
    tableHeightRaf = 0
    updateProjectTableMaxHeight()
  })
}

const {
  collapsed: isProjectHeaderCollapsed,
  refreshScrollTargets: refreshProjectHeaderScrollTargets,
} = useCollapsiblePageHeader({
  rootSelector: '.registry-project-page',
  scrollSelectors: [
    '.project-table .el-scrollbar__wrap',
    '.project-table .el-table__body-wrapper',
  ],
  onScroll: scheduleUpdateProjectTableMaxHeight,
  onLayoutChange: scheduleUpdateProjectTableMaxHeight,
})

function updateProjectTableMaxHeight() {
  if (typeof window === 'undefined') return
  const root = document.querySelector('.registry-project-page')
  const cardEl = root?.querySelector('.project-card') as HTMLElement | undefined
  const tableEl = root?.querySelector('.project-table') as HTMLElement | undefined
  const footerEl = root?.querySelector('.table-footer') as HTMLElement | undefined
  if (!tableEl) {
    projectTableMaxHeight.value = Math.max(280, window.innerHeight - 420)
    return
  }
  const tableTop = tableEl.getBoundingClientRect().top
  const viewportPad = 16
  let bottomLimit = window.innerHeight - viewportPad
  if (cardEl) {
    const cardBottom = cardEl.getBoundingClientRect().bottom
    if (cardBottom > tableTop && cardBottom <= window.innerHeight) {
      bottomLimit = Math.min(bottomLimit, cardBottom - 12)
    }
  }
  if (footerEl && footerEl.offsetParent !== null) {
    const footerTop = footerEl.getBoundingClientRect().top
    if (footerTop > tableTop && footerTop < bottomLimit) {
      bottomLimit = footerTop - 12
    }
  }
  const h = bottomLimit - tableTop
  projectTableMaxHeight.value = Math.max(260, Math.floor(h))
}

const sdkForm = reactive<RegistryProjectRegisterRequest>({
  projectCode: '',
  name: '',
  environment: 'dev',
  owner: '',
  visibility: 'PRIVATE',
  baseUrl: '',
  contextPath: '',
  appKey: '',
  appSecret: '',
})

const scanForm = reactive<ScanProjectUpsertRequest>(createEmptyScanForm())

const filteredProjects = computed(() => projects.value)

const pagedProjects = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredProjects.value.slice(start, start + pageSize.value)
})

const metrics = computed(() => {
  const total = projects.value.length
  const apiCount = projects.value.reduce((sum, project) => sum + apiCountOf(project), 0)
  const sdkCount = projects.value.filter((project) => ['REGISTERED', 'HYBRID'].includes(project.projectKind || '')).length
  const failedCount = projects.value.filter((project) => project.status === 'failed').length
  const scannedCount = projects.value.filter((project) => project.lastScannedAt).length
  return [
    { label: '已注册项目数', value: total, caption: '统一项目目录', delta: total ? '已同步' : '待接入', deltaTone: 'brand', iconKey: 'project', tone: 'brand' },
    { label: '已接入 API 数', value: apiCount, caption: '来自 SDK 与扫描', delta: apiCount ? '已同步' : '待接入', deltaTone: 'brand', iconKey: 'api', tone: 'brand' },
    { label: '已生成 SDK 数', value: sdkCount, caption: 'Starter 配置可用', delta: sdkCount ? '可生成' : '待生成', deltaTone: 'success', iconKey: 'sdk', tone: 'brand' },
    {
      label: '异常扫描项目',
      value: `${scannedCount} / ${failedCount}`,
      caption: '扫描项目 / 异常项目',
      delta: failedCount ? `异常 ${failedCount}` : '稳定',
      deltaTone: failedCount ? 'warning' : 'warning',
      iconKey: 'scan',
      tone: 'brand',
    },
  ]
})

onMounted(() => {
  loadProjects().finally(() => {
    nextTick(() => {
      refreshProjectHeaderScrollTargets()
      scheduleUpdateProjectTableMaxHeight()
    })
  })
  nextTick(() => {
    refreshProjectHeaderScrollTargets()
    scheduleUpdateProjectTableMaxHeight()
  })
  window.addEventListener('resize', scheduleUpdateProjectTableMaxHeight)
})

onUnmounted(() => {
  window.removeEventListener('resize', scheduleUpdateProjectTableMaxHeight)
  if (tableHeightRaf) {
    cancelAnimationFrame(tableHeightRaf)
    tableHeightRaf = 0
  }
})

watch(pageSize, () => {
  currentPage.value = 1
})

watch([() => filteredProjects.value.length], () => {
  nextTick(scheduleUpdateProjectTableMaxHeight)
})

function createEmptyScanForm(): ScanProjectUpsertRequest {
  return {
    name: '',
    projectCode: '',
    projectKind: 'SCAN',
    environment: 'dev',
    owner: '',
    visibility: 'PRIVATE',
    baseUrl: '',
    contextPath: '',
    scanPath: '',
    scanType: 'openapi',
    specFile: '',
  }
}

function resetSdkForm() {
  sdkForm.projectCode = ''
  sdkForm.name = ''
  sdkForm.environment = 'dev'
  sdkForm.owner = ''
  sdkForm.visibility = 'PRIVATE'
  sdkForm.baseUrl = ''
  sdkForm.contextPath = ''
  sdkForm.appKey = ''
  sdkForm.appSecret = ''
}

function applyScanForm(project: ScanProjectUpsertRequest) {
  scanForm.name = project.name
  scanForm.projectCode = project.projectCode ?? ''
  scanForm.projectKind = project.projectKind || 'SCAN'
  scanForm.environment = project.environment || 'dev'
  scanForm.owner = project.owner ?? ''
  scanForm.visibility = project.visibility || 'PRIVATE'
  scanForm.baseUrl = project.baseUrl
  scanForm.contextPath = project.contextPath || ''
  scanForm.scanPath = project.scanPath || ''
  scanForm.scanType = project.scanType || 'openapi'
  scanForm.specFile = project.specFile || ''
}

function buildProjectListQuery(): ScanProjectListQuery {
  const keywordText = keyword.value.trim()
  return {
    keyword: keywordText || undefined,
    projectKind: kindFilter.value || undefined,
    status: statusFilter.value || undefined,
  }
}

async function loadProjects(query: ScanProjectListQuery = buildProjectListQuery()) {
  loading.value = true
  try {
    const { data } = await getScanProjects(query)
    projects.value = Array.isArray(data) ? data : []
    projectStore.projects = projects.value
  } catch {
    projects.value = []
    ElMessage.error('加载项目失败')
  } finally {
    loading.value = false
    nextTick(scheduleUpdateProjectTableMaxHeight)
  }
}

function handleSearch() {
  currentPage.value = 1
  loadProjects()
}

function openAccessDialog(tab: 'sdk' | 'scan' = 'sdk') {
  resetSdkForm()
  applyScanForm(createEmptyScanForm())
  accessDialogTab.value = tab
  accessDialogVisible.value = true
}

async function saveSdkProject() {
  const appKey = String(sdkForm.appKey || '').trim()
  const appSecret = String(sdkForm.appSecret || '').trim()
  if (!sdkForm.name.trim() || !sdkForm.projectCode.trim() || !sdkForm.baseUrl.trim()) {
    ElMessage.warning('请填写项目名称、项目编码和 Base URL')
    return
  }
  if (!appKey || !appSecret) {
    ElMessage.warning('请填写 App Key 和 App Secret')
    return
  }
  saving.value = true
  try {
    await registerRegistryProject({
      ...sdkForm,
      appKey,
      appSecret,
    })
    ElMessage.success('SDK 接入项目已创建')
    accessDialogVisible.value = false
    await loadProjects()
  } finally {
    saving.value = false
  }
}

async function saveScanProject() {
  if (!scanForm.name.trim() || !scanForm.baseUrl.trim() || (scanForm.projectKind !== 'REGISTERED' && !scanForm.scanPath.trim())) {
    ElMessage.warning('请填写项目名称、域名和扫描路径')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...scanForm,
      specFile: scanForm.scanType === 'openapi' ? scanForm.specFile || null : null,
      contextPath: scanForm.contextPath || '',
    }
    await createScanProject(payload)
    ElMessage.success('扫描项目已创建')
    accessDialogVisible.value = false
    await loadProjects()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

function resetFilters() {
  keyword.value = ''
  kindFilter.value = ''
  statusFilter.value = ''
  currentPage.value = 1
  loadProjects()
}

function kindTagType(kind?: string) {
  if (kind === 'REGISTERED') return 'success'
  if (kind === 'HYBRID') return 'warning'
  return 'info'
}

function formatProjectStatusLabel(status?: string | null) {
  if (!status) return '-'
  const normalized = String(status).trim()
  const labels: Record<string, string> = {
    REGISTERED: '已注册',
    HYBRID: '混合接入',
    SCAN: '扫描接入',
    CREATED: '已创建',
    SCANNING: '扫描中',
    SCANNED: '已接入',
    FAILED: '异常',
    created: '已创建',
    scanning: '扫描中',
    scanned: '已接入',
    failed: '异常',
  }
  return labels[normalized] ?? labels[normalized.toUpperCase()] ?? formatScanStatusLabel(normalized)
}

function statusLabel(project: ScanProject) {
  if (project.registryStatusSummary) return formatProjectStatusLabel(project.registryStatusSummary)
  return formatProjectStatusLabel(project.status)
}

function apiCountOf(project: ScanProject) {
  return project.apiCount ?? project.toolCount ?? 0
}

function avatarClass(kind?: string) {
  if (kind === 'REGISTERED') return 'sdk'
  if (kind === 'HYBRID') return 'hybrid'
  return 'scan'
}

function projectInitial(value?: string | null) {
  return (value || 'P').trim().slice(0, 1).toUpperCase()
}

function projectDescription(project: ScanProject) {
  if (project.description) return project.description
  const env = project.environment ? `${project.environment} 环境` : '未标注环境'
  return `${env} · ${project.baseUrl || '未配置根地址'}`
}

function sdkVersionLabel(project: ScanProject) {
  return project.sdkVersion || (['REGISTERED', 'HYBRID'].includes(project.projectKind || '') ? 'Starter' : '-')
}

function formatDate(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function goDetail(project: ScanProject) {
  if (project.projectCode) {
    router.push(`/registry/projects/${encodeURIComponent(project.projectCode)}`)
    return
  }
  router.push(`/scan-project/${project.id}`)
}
</script>

<style scoped lang="scss">
.registry-project-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  min-height: 100%;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  background: transparent;
}

.empty-actions,
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.empty-main {
  display: flex;
  align-items: stretch;
  gap: 28px;
  min-width: 0;
}

.empty-body {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 18px;
  flex: 1;
  min-width: 0;
}

.empty-copy {
  flex: 0 0 auto;
  min-width: 0;
}

.empty-illustration {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 240px;
  width: 240px;
  min-height: 140px;
  align-self: stretch;
  border-radius: 22px;
  overflow: hidden;
  background: linear-gradient(135deg, var(--brand-selected-bg), rgba(255, 255, 255, 0.88));
}

.empty-illustration-img {
  max-width: 100%;
  max-height: 100%;
  width: auto;
  height: auto;
  object-fit: contain;
  display: block;
}

.metric-strip {
  display: flex;
  align-items: stretch;
  width: 100%;
  min-width: 0;
  margin: 0;
  flex-shrink: 0;
}

.metric-divider {
  flex: 0 0 1px;
  align-self: center;
  width: 1px;
  height: 68px;
  background: rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: 1px;
}

.metric-segment {
  display: flex;
  flex: 1 1 0;
  gap: 18px;
  align-items: center;
  min-width: 0;
  min-height: 104px;
  padding: 18px 22px;

  .metric-label,
  small {
    display: block;
    color: #64748b;
  }

  .metric-label {
    font-size: 13px;
    font-weight: 500;
    line-height: 17px;
  }

  strong {
    display: block;
    margin: 4px 0 2px;
    color: #0f172a;
    font-size: 25px;
    font-weight: 700;
    line-height: 30px;
  }

  small {
    font-size: 12px;
    line-height: 16px;
  }
}

.project-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  min-height: 0;
  margin: 0;
  border: 1px solid #eaecf5;
  border-radius: 12px;
  box-shadow: 0 14px 34px rgba(17, 24, 39, 0.045);

  :deep(.el-card__body) {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
    padding: 0;
  }
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 2fr) repeat(2, minmax(150px, 1fr)) 74px 74px;
  align-items: center;
  gap: 10px;
  padding: 14px 22px 16px;
  border-bottom: 1px solid #eef1f7;
  background: #fff;
  flex-shrink: 0;

  .search-input {
    width: 100%;
    min-width: 0;
  }

  .el-select {
    width: 100%;
    min-width: 0;
  }

  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    min-height: 34px;
    border-radius: 7px;
    box-shadow: 0 0 0 1px #d9deea inset;
  }

  :deep(.el-button) {
    min-height: 34px;
    border-radius: 7px;
  }
}

.project-table {
  width: 100%;
  flex: 1;
  min-height: 0;

  :deep(th.el-table__cell) {
    height: 44px;
    color: var(--brand-active);
    background: rgb(var(--brand-selected-rgb) / 0.34);
    font-size: 12px;
    font-weight: 700;
  }

  :deep(td.el-table__cell) {
    height: 58px;
    color: #344054;
    border-bottom-color: #f0f2f7;
  }

  :deep(.el-table__row:hover > td.el-table__cell) {
    background: rgb(var(--brand-selected-rgb) / 0.22);
  }

  /* 固定操作列不透明，避免与下方行叠字 */
  :deep(.el-table-fixed-column--right),
  :deep(.el-table-fixed-column--left) {
    background-color: #fff !important;
  }

  :deep(th.el-table-fixed-column--right),
  :deep(th.el-table-fixed-column--left) {
    background-color: rgb(var(--brand-selected-rgb) / 0.34) !important;
  }

  :deep(td.el-table-fixed-column--right),
  :deep(td.el-table-fixed-column--left) {
    background-color: #fff !important;
  }

  :deep(.el-table__row:hover > td.el-table-fixed-column--right),
  :deep(.el-table__row:hover > td.el-table-fixed-column--left),
  :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
  :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left),
  :deep(.el-table__body tr.current-row > td.el-table-fixed-column--right),
  :deep(.el-table__body tr.current-row > td.el-table-fixed-column--left) {
    background-color: rgb(var(--brand-selected-rgb) / 0.22) !important;
  }

  :deep(.el-table__fixed-right-patch) {
    background-color: #fff !important;
  }
}

.project-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;

  strong,
  span {
    display: block;
  }

  strong {
    color: #101828;
    font-weight: 700;
  }

  span {
    margin-top: 4px;
    color: #667085;
    font-size: 12px;
  }
}

.project-name-cell-btn {
  border: none;
  background: transparent;
  padding: 0;
  margin: 0;
  font: inherit;
  text-align: left;
  cursor: pointer;
  width: 100%;
  min-width: 0;

  &:hover strong {
    color: var(--brand-primary);
  }

  &:focus-visible {
    outline: 2px solid rgb(var(--brand-primary-rgb) / 0.35);
    outline-offset: 2px;
    border-radius: 10px;
  }
}

.project-avatar {
  display: grid;
  place-items: center;
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.16);
  border-radius: 8px;
  color: var(--brand-active);
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.94) 0%, rgb(var(--brand-selected-rgb) / 0.58) 100%),
    rgba(255, 255, 255, 0.78);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.88),
    0 8px 16px -14px rgb(var(--brand-primary-rgb) / 0.42);
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
  transition:
    border-color 0.16s ease,
    color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;
}

.muted,
.form-hint {
  color: #667085;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;

  i {
    width: 7px;
    height: 7px;
    border-radius: 999px;
  }
}

.status-scanned,
.status-created {
  color: #16a34a;

  i {
    background: #22c55e;
  }
}

.status-scanning {
  color: var(--brand-primary);

  i {
    background: var(--brand-primary);
  }
}

.status-failed {
  color: #dc2626;

  i {
    background: #ef4444;
  }
}

.owner-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.owner-cell span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  margin: 20px;
  padding: 28px 32px;
  border: 1px dashed rgb(var(--brand-primary-rgb) / 0.28);
  border-radius: 18px;
  background: linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.06), rgb(var(--brand-hover-rgb) / 0.03));
  min-height: 0;

  h3 {
    margin: 0 0 8px;
    color: #101828;
    font-size: 18px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: #667085;
    line-height: 1.55;
  }
}

.empty-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 22px 16px;
  border-top: 1px solid #eef1f7;
  color: #667085;
  font-size: 13px;
  flex-shrink: 0;
  margin-top: auto;
}

/* Figma: 项目管理 / 项目列表 */
.registry-project-page {
  background: transparent;
}

.metric-strip {
  align-items: stretch;
  min-height: 104px;
  max-height: 104px;
  padding: 0;
  gap: 0;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.5);
  box-shadow: 0 18px 42px rgb(var(--brand-primary-rgb) / 0.052);
  backdrop-filter: blur(9px);
  overflow: hidden;
  transition:
    min-height 0.2s ease,
    max-height 0.22s ease,
    padding 0.2s ease,
    opacity 0.18s ease,
    transform 0.2s ease;
}

.metric-segment {
  position: relative;
  min-height: 104px;
  padding: 18px 22px;
  gap: 18px;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
  transition:
    min-height 0.2s ease,
    padding 0.2s ease,
    opacity 0.16s ease,
    transform 0.18s ease;
}

.metric-segment-icon {
  flex-shrink: 0;
  --metric-icon-glyph-size: 24px;
}

.metric-content {
  flex: 1;
  min-width: 0;
}

.metric-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.metric-segment .metric-label {
  overflow: hidden;
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-delta {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  min-width: 72px;
  height: 24px;
  padding: 0 12px;
  font-size: 12px;
  font-style: normal;
  font-weight: 500;
  line-height: 18px;
  border-radius: 12px;
}

.metric-delta.brand {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.metric-delta.success {
  color: #16a34a;
  background: #dcfce7;
}

.metric-delta.warning {
  color: #f97316;
  background: #fff7ed;
}

.metric-segment strong {
  margin: 4px 0 2px;
  color: #0f172a;
  font-size: 25px;
  font-weight: 700;
  line-height: 30px;
}

.metric-segment small {
  color: #64748b;
  font-size: 12px;
  line-height: 16px;
}

@media (prefers-reduced-motion: reduce) {
  .registry-project-page,
  .metric-strip,
  .metric-segment,
  .project-card,
  .toolbar,
  .project-table-shell {
    transition: none !important;
  }
}

.project-card {
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.14);
  border-radius: 16px;
  background:
    var(--brand-glass-card-bg, rgba(255, 255, 255, 0.76)),
    rgba(255, 255, 255, 0.76);
  box-shadow: 0 18px 42px -16px rgb(var(--brand-primary-rgb) / 0.08);
  backdrop-filter: blur(18px);
  transition:
    box-shadow 0.2s ease,
    border-color 0.2s ease;
}

.project-card :deep(.el-card__body) {
  padding: 0;
}

.toolbar {
  grid-template-columns: minmax(340px, 2.4fr) repeat(2, minmax(170px, 1fr)) 74px 88px;
  gap: 12px;
  margin: 28px 28px 0;
  padding: 9px 14px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.11);
  border-radius: 12px;
  background: var(--brand-soft-bg, rgba(238, 242, 255, 0.76));
  transition:
    margin 0.2s ease,
    background 0.2s ease,
    border-color 0.2s ease;
}

.toolbar :deep(.el-input__wrapper),
.toolbar :deep(.el-select__wrapper) {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.18) inset;
}

.toolbar :deep(.el-input__inner),
.toolbar :deep(.el-select__placeholder),
.toolbar :deep(.el-select__selected-item) {
  color: #8290a9;
  font-size: 13px;
  line-height: 17px;
}

.toolbar :deep(.el-input__prefix),
.toolbar :deep(.el-input__suffix),
.toolbar :deep(.el-select__suffix),
.toolbar :deep(.el-select__caret) {
  color: rgb(var(--brand-primary-rgb) / 0.42);
}

.toolbar-reset,
.toolbar-search {
  height: 40px;
  min-height: 40px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: 10px;
  font-weight: 700;
  box-shadow: 0 6px 16px -8px rgba(100, 116, 139, 0.08);
}

.toolbar-reset {
  color: #334155;
  background: rgba(255, 255, 255, 0.86);
}

.toolbar-search {
  color: #fff;
  border-color: transparent;
  background: var(--brand-primary-gradient);
  box-shadow: 0 10px 18px -10px rgb(var(--brand-primary-rgb) / 0.24);
}

.toolbar-reset:hover,
.toolbar-search:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.24);
}

.toolbar-reset:hover {
  color: var(--brand-active);
  background: #f8fbff;
}

.toolbar-search:hover {
  color: #fff;
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-primary));
}

.project-table-shell {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  margin: 22px 28px 0;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.68);
  transition:
    margin 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease;
}

.project-table {
  --el-table-header-bg-color: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb);
  --el-table-row-hover-bg-color: rgba(255, 255, 255, 0.72);
  --el-table-border-color: rgb(var(--brand-primary-rgb) / 0.1);
  background: transparent;
}

.project-table :deep(.el-table__inner-wrapper::before) {
  height: 0;
}

.project-table :deep(.cell) {
  padding: 0 8px;
}

.project-table :deep(th.el-table__cell) {
  height: 44px;
  color: var(--brand-active);
  background: color-mix(in srgb, var(--brand-selected-bg) 42%, #f1f5fb) !important;
  font-size: 12px;
  font-weight: 700;
  line-height: 16px;
}

.project-table :deep(td.el-table__cell) {
  height: 58px;
  color: #334155;
  background: rgba(255, 255, 255, 0.72) !important;
  border-bottom-color: rgb(var(--brand-primary-rgb) / 0.1);
}

.project-table :deep(.el-table__row:hover > td.el-table__cell),
.project-table :deep(.el-table__body tr.hover-row > td.el-table__cell) {
  background: rgba(255, 255, 255, 0.72) !important;
}

.project-table :deep(.el-table__empty-block) {
  background: rgba(255, 255, 255, 0.46);
}

.project-table :deep(.el-table-fixed-column--right),
.project-table :deep(.el-table-fixed-column--left) {
  background-color: rgba(255, 255, 255, 0.88) !important;
}

.project-table :deep(th.el-table-fixed-column--right),
.project-table :deep(th.el-table-fixed-column--left) {
  background-color: rgb(var(--brand-selected-rgb) / 0.34) !important;
}

.project-table :deep(td.el-table-fixed-column--right),
.project-table :deep(td.el-table-fixed-column--left) {
  background-color: rgba(255, 255, 255, 0.88) !important;
}

.project-name-cell strong {
  color: #0f172a;
}

.project-name-cell span,
.muted,
.form-hint {
  color: #64748b;
}

.project-avatar {
  color: var(--brand-active);
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.96) 0%, rgb(var(--brand-selected-rgb) / 0.88) 100%);
  box-shadow: 0 8px 16px -12px rgb(var(--brand-primary-rgb) / 0.16);
}

.project-name-cell-btn:hover .project-avatar {
  color: var(--brand-primary);
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 10px 18px -14px rgb(var(--brand-primary-rgb) / 0.46);
  transform: translateY(-1px);
}

.owner-cell :deep(.el-avatar) {
  color: #94a3b8;
  background: #e2e8f0;
}

.table-footer {
  min-height: 64px;
  padding: 14px 28px 16px;
  border-top: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  color: #64748b;
  background: rgba(255, 255, 255, 0.34);
}

/* Figma: ReachAI/Pagination/CompactGlass */
.table-footer :deep(.registry-pagination.el-pagination) {
  --el-pagination-button-width: 34px;
  --el-pagination-button-height: 34px;
  --el-pagination-hover-color: var(--brand-active);
  gap: 6px;
}

.table-footer :deep(.registry-pagination.is-background .btn-prev),
.table-footer :deep(.registry-pagination.is-background .btn-next),
.table-footer :deep(.registry-pagination.is-background .el-pager li:not(.is-active)) {
  min-width: 34px;
  height: 34px;
  line-height: 34px;
  margin: 0;
  color: #cbd5e1 !important;
  border: none !important;
  border-radius: 8px;
  background: #f3f7fc !important;
  box-shadow: none !important;
}

.table-footer :deep(.registry-pagination.is-background .btn-prev .el-icon),
.table-footer :deep(.registry-pagination.is-background .btn-next .el-icon) {
  color: #cbd5e1;
  font-size: 12px;
}

.table-footer :deep(.registry-pagination.is-background .btn-prev:hover:not(:disabled)),
.table-footer :deep(.registry-pagination.is-background .btn-next:hover:not(:disabled)),
.table-footer :deep(.registry-pagination.is-background .el-pager li:not(.is-active):hover) {
  color: var(--brand-active) !important;
  background: color-mix(in srgb, var(--brand-selected-bg) 42%, #f3f7fc) !important;
}

.table-footer :deep(.registry-pagination.is-background .el-pager li.is-active) {
  color: var(--brand-active) !important;
  font-size: 13px;
  font-weight: 700;
  line-height: 34px;
  border: none !important;
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgb(var(--brand-selected-rgb) / 0.8) 0%, rgb(var(--brand-selected-rgb) / 0.64) 100%),
    rgba(255, 255, 255, 0.72) !important;
  background-color: rgba(255, 255, 255, 0.72) !important;
  box-shadow:
    0 5px 12px -7px rgb(var(--brand-primary-rgb) / 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.7) !important;
}

.table-footer :deep(.registry-pagination .el-pagination__sizes) {
  margin-left: 2px;
}

.table-footer :deep(.registry-pagination .el-pagination__sizes .el-select) {
  width: 94px;
}

.table-footer :deep(.registry-pagination .el-pagination__sizes .el-select .el-select__wrapper) {
  min-height: 34px;
  height: 34px;
  padding: 0 8px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.86) !important;
  box-shadow: 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.12) inset !important;
}

.table-footer :deep(.registry-pagination .el-pagination__sizes .el-select .el-select__selected-item),
.table-footer :deep(.registry-pagination .el-pagination__sizes .el-select .el-select__placeholder) {
  color: #64748b;
  font-size: 12px;
}

.empty-state {
  margin: 20px;
  border-color: rgb(var(--brand-primary-rgb) / 0.2);
  background: linear-gradient(135deg, rgb(var(--brand-selected-rgb) / 0.55), rgba(255, 255, 255, 0.62));
}

.registry-project-page.is-dark {
  background: transparent;

  .empty-actions {
    :deep(.el-button--default) {
      color: #cbd5e1;
      background: rgba(255, 255, 255, 0.04);
      border-color: rgba(255, 255, 255, 0.1);

      &:hover {
        color: #e2e8f0;
        background: rgb(var(--brand-primary-rgb) / 0.12);
        border-color: rgb(var(--brand-primary-rgb) / 0.35);
      }
    }
  }

  .metric-strip,
  .project-card {
    border-color: rgba(255, 255, 255, 0.07);
    background: rgba(255, 255, 255, 0.035);
    box-shadow: 0 14px 34px rgba(0, 0, 0, 0.22);
    backdrop-filter: blur(12px);
  }

  .empty-illustration {
    background: linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.22), rgb(var(--brand-hover-rgb) / 0.08));
  }

  .metric-strip {
    .metric-label,
    small {
      color: #94a3b8;
    }

    strong {
      color: #e2e8f0;
    }

    small em {
      color: var(--brand-hover);
    }
  }

  .project-table-shell {
    background: var(--surface-solid-panel);
  }

  .project-card {
    --el-card-bg-color: transparent;

    :deep(.el-card) {
      background: transparent;
      border: none;
      box-shadow: none;
    }

    :deep(.el-card__body) {
      background: transparent;
    }
  }

  .toolbar {
    background: rgba(255, 255, 255, 0.02);
    border-bottom-color: rgba(255, 255, 255, 0.06);

    :deep(.el-input__wrapper),
    :deep(.el-select__wrapper) {
      background: rgba(255, 255, 255, 0.035);
      box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
    }

    :deep(.el-input__inner),
    :deep(.el-select__placeholder),
    :deep(.el-select__selected-item) {
      color: #cbd5e1;
    }

    :deep(.el-input__inner::placeholder) {
      color: #64748b;
    }

    :deep(.el-input__prefix),
    :deep(.el-input__suffix),
    :deep(.el-select__suffix),
    :deep(.el-select__caret) {
      color: #94a3b8;
    }

    :deep(.el-button--default) {
      color: #cbd5e1;
      background: rgba(255, 255, 255, 0.04);
      border-color: rgba(255, 255, 255, 0.1);

      &:hover {
        color: #e2e8f0;
        background: rgb(var(--brand-primary-rgb) / 0.12);
        border-color: rgb(var(--brand-primary-rgb) / 0.35);
      }
    }
  }

  .owner-cell span {
    color: #cbd5e1;
  }

  .owner-cell :deep(.el-avatar) {
    border: 1px solid rgba(255, 255, 255, 0.1);
    background: rgb(var(--brand-primary-rgb) / 0.22);
    color: #e2e8f0;
  }

  .project-table {
    --el-table-bg-color: transparent;
    --el-table-tr-bg-color: transparent;
    --el-table-header-bg-color: rgba(255, 255, 255, 0.045);
    --el-table-row-hover-bg-color: rgb(var(--brand-primary-rgb) / 0.08);
    --el-table-border-color: rgba(255, 255, 255, 0.06);
    --el-table-text-color: #cbd5e1;
    --el-table-header-text-color: #94a3b8;
    background: transparent;

    :deep(.el-table__inner-wrapper::before),
    :deep(.el-table__border-left-patch),
    :deep(.el-table__border-bottom-patch) {
      background-color: rgba(255, 255, 255, 0.06);
    }

    :deep(th.el-table__cell) {
      color: #94a3b8;
      background: rgba(255, 255, 255, 0.045) !important;
    }

    :deep(td.el-table__cell) {
      color: #cbd5e1;
      background: rgba(255, 255, 255, 0.015) !important;
      border-bottom-color: rgba(255, 255, 255, 0.06);
    }

    :deep(.el-table__row:hover > td.el-table__cell),
    :deep(.el-table__body tr.hover-row > td.el-table__cell) {
      background: rgb(var(--brand-primary-rgb) / 0.08) !important;
    }

    :deep(.el-table__inner-wrapper),
    :deep(.el-table__header-wrapper),
    :deep(.el-table__body-wrapper) {
      background: transparent;
    }

    :deep(.el-table__empty-block) {
      background: transparent;
    }

    :deep(.el-table__empty-text) {
      color: #94a3b8;
    }

    /* 固定列必须用不透明底，否则滚动时下一行会透出 */
    :deep(.el-table-fixed-column--right),
    :deep(.el-table-fixed-column--left) {
      background-color: var(--bg-secondary) !important;
    }

    :deep(th.el-table-fixed-column--right),
    :deep(th.el-table-fixed-column--left) {
      background-color: #1c1c2a !important;
    }

    :deep(td.el-table-fixed-column--right),
    :deep(td.el-table-fixed-column--left) {
      background-color: var(--bg-secondary) !important;
    }

    :deep(.el-table__row:hover > td.el-table-fixed-column--right),
    :deep(.el-table__row:hover > td.el-table-fixed-column--left),
    :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right),
    :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--left),
    :deep(.el-table__body tr.current-row > td.el-table-fixed-column--right),
    :deep(.el-table__body tr.current-row > td.el-table-fixed-column--left) {
      background-color: #252538 !important;
    }

    :deep(.el-table__fixed-right-patch) {
      background-color: var(--bg-secondary) !important;
    }

    :deep(.el-button.is-link) {
      color: var(--brand-disabled);

      &:hover {
        color: var(--brand-selected-bg);
      }

      &.is-disabled {
        color: rgba(148, 163, 184, 0.45);
      }
    }

    /* 「更多」为默认 link；触发器外层可能是 span，勿用子选择器 */
    :deep(.el-dropdown .el-button.is-link:not(.el-button--primary):not(.is-disabled)) {
      color: #94a3b8;

      &:hover {
        color: var(--brand-selected-bg);
      }
    }

    :deep(.el-tag.el-tag--light) {
      border-width: 1px;
      border-style: solid;
    }

    :deep(.el-tag--light.el-tag--success) {
      background: rgba(34, 211, 238, 0.12) !important;
      border-color: rgba(34, 211, 238, 0.22) !important;
      color: #5eead4 !important;
    }

    :deep(.el-tag--light.el-tag--info) {
      background: rgba(148, 163, 184, 0.14) !important;
      border-color: rgba(148, 163, 184, 0.22) !important;
      color: #cbd5e1 !important;
    }

    :deep(.el-tag--light.el-tag--warning) {
      background: rgba(245, 158, 11, 0.14) !important;
      border-color: rgba(245, 158, 11, 0.22) !important;
      color: #fcd34d !important;
    }

    :deep(.el-tag--light.el-tag--danger) {
      background: rgba(244, 63, 94, 0.12) !important;
      border-color: rgba(244, 63, 94, 0.22) !important;
      color: #fda4af !important;
    }

    :deep(.el-loading-mask) {
      background-color: rgba(10, 10, 15, 0.72);
      backdrop-filter: blur(4px);
    }
  }

  .project-name-cell {
    strong {
      color: #e2e8f0;
    }

    span {
      color: #64748b;
    }
  }

  .project-name-cell-btn:hover strong {
    color: var(--brand-disabled);
  }

  .project-name-cell-btn:focus-visible {
    outline-color: rgba(165, 180, 252, 0.45);
  }

  .project-avatar {
    color: var(--brand-disabled);
    border-color: rgb(var(--brand-primary-rgb) / 0.26);
    background:
      linear-gradient(145deg, rgba(255, 255, 255, 0.1) 0%, rgb(var(--brand-primary-rgb) / 0.16) 100%),
      rgba(15, 23, 42, 0.64);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.1),
      0 10px 20px -16px rgb(var(--brand-primary-rgb) / 0.55);
  }

  .project-name-cell-btn:hover .project-avatar {
    color: #fff;
    border-color: rgb(var(--brand-primary-rgb) / 0.42);
  }

  .muted,
  .form-hint {
    color: #94a3b8;
  }

  .empty-state {
    border-color: rgb(var(--brand-primary-rgb) / 0.22);
    background: linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.1), rgb(var(--brand-hover-rgb) / 0.04));

    h3 {
      color: #e2e8f0;
    }

    p {
      color: #94a3b8;
    }
  }

  .table-footer {
    color: #94a3b8;
    border-top-color: rgba(255, 255, 255, 0.06);
    background: color-mix(in srgb, var(--surface-solid-panel) 72%, transparent);

    :deep(.registry-pagination.is-background .btn-prev),
    :deep(.registry-pagination.is-background .btn-next),
    :deep(.registry-pagination.is-background .el-pager li:not(.is-active)) {
      color: #64748b !important;
      background: rgba(255, 255, 255, 0.06) !important;
    }

    :deep(.registry-pagination.is-background .btn-prev .el-icon),
    :deep(.registry-pagination.is-background .btn-next .el-icon) {
      color: #94a3b8;
    }

    :deep(.registry-pagination.is-background .btn-prev:hover:not(:disabled)),
    :deep(.registry-pagination.is-background .btn-next:hover:not(:disabled)),
    :deep(.registry-pagination.is-background .el-pager li:not(.is-active):hover) {
      color: var(--brand-selected-bg) !important;
      background: rgb(var(--brand-primary-rgb) / 0.12) !important;
    }

    :deep(.registry-pagination.is-background .el-pager li.is-active) {
      color: var(--brand-active) !important;
      background:
        linear-gradient(90deg, rgb(var(--brand-selected-rgb) / 0.42) 0%, rgb(var(--brand-selected-rgb) / 0.28) 100%),
        rgba(255, 255, 255, 0.08) !important;
      background-color: rgba(255, 255, 255, 0.08) !important;
      box-shadow:
        0 5px 12px -7px rgb(0 0 0 / 0.28),
        inset 0 1px 0 rgba(255, 255, 255, 0.08) !important;
    }

    :deep(.registry-pagination .el-pagination__sizes .el-select .el-select__wrapper) {
      background: rgba(255, 255, 255, 0.06) !important;
      box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08) inset !important;
    }

    :deep(.el-pagination.is-background .btn-prev.is-disabled),
    :deep(.el-pagination.is-background .btn-next.is-disabled),
    :deep(.el-pagination.is-background .el-pager li.is-disabled) {
      color: rgba(148, 163, 184, 0.35);
      background: rgba(255, 255, 255, 0.02);
    }

    :deep(.el-select__wrapper) {
      background: rgba(255, 255, 255, 0.035);
      box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
    }

    :deep(.el-pagination__sizes .el-select .el-select__wrapper) {
      background: rgba(255, 255, 255, 0.035);
      box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
    }

    :deep(.el-pagination__sizes .el-select .el-select__selected-item),
    :deep(.el-pagination__sizes .el-select .el-select__placeholder) {
      color: #cbd5e1;
    }
  }

}

@media (max-width: 1200px) {
  .metric-strip {
    flex-direction: column;
    max-height: none;
  }

  .metric-divider {
    width: auto;
    height: 1px;
    align-self: stretch;
    margin: 0 22px;
  }

  .metric-segment {
    min-height: 92px;
  }

  .empty-main {
    flex-direction: column;
    align-items: center;
    text-align: center;
  }

  .empty-body {
    align-items: center;
    width: 100%;
  }

  .empty-actions {
    justify-content: center;
  }

  /* 纵向堆叠时左侧插图单独一行，不再与右侧拉伸对齐 */
  .empty-illustration {
    align-self: center;
    flex: 0 0 auto;
    width: min(100%, 240px);
    height: 200px;
    min-height: 0;
  }

  .toolbar {
    grid-template-columns: minmax(240px, 1fr) repeat(2, minmax(150px, 1fr));

    .search-input {
      min-width: 0;
    }

    .el-select {
      min-width: 0;
    }
  }
}

@media (max-width: 760px) {
  .metric-segment {
    min-height: 84px;
  }

  .toolbar {
    grid-template-columns: 1fr;
  }

  .empty-illustration {
    width: min(100%, 240px);
    height: 176px;
  }

  .table-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

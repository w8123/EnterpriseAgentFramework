<template>
  <div class="registry-project-page" :class="{ 'is-dark': theme === 'dark' }">
    <div class="page-hero">
      <div>
        <h1>项目管理</h1>
        <p>统一管理 AI 项目的注册、SDK 接入、API 接入与能力扫描</p>
      </div>
      <div class="hero-actions">
        <el-button type="primary" size="large" class="primary-action" :icon="Plus" @click="openAccessDialog('sdk')">
          接入项目
        </el-button>
      </div>
    </div>

    <div class="metric-grid">
      <div v-for="metric in metrics" :key="metric.label" class="metric-card">
        <div class="metric-icon" :class="metric.tone" aria-hidden="true">
          <el-icon>
            <component :is="metric.icon" />
          </el-icon>
        </div>
        <div>
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
          <small>
            {{ metric.caption }}
            <em v-if="metric.delta">{{ metric.delta }}</em>
          </small>
        </div>
      </div>
    </div>

    <el-card class="project-card" shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          class="search-input"
          clearable
          :prefix-icon="Search"
          placeholder="搜索项目名称、描述、负责人"
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
        <el-select v-model="ownerFilter" clearable filterable placeholder="负责人">
          <el-option v-for="owner in ownerOptions" :key="owner" :label="owner" :value="owner" />
        </el-select>
        <el-button @click="resetFilters">重置</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadProjects" />
      </div>

      <div v-if="projectStore.currentProjectId" class="context-filter">
        当前仅展示顶部项目选择器选中的项目。
        <el-button link type="primary" @click="projectStore.setCurrentProject(null)">查看全部项目</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="pagedProjects"
        row-key="id"
        class="project-table"
        :max-height="projectTableMaxHeight"
      >
        <el-table-column label="项目名称" min-width="230">
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
        <el-table-column label="项目描述" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="muted">{{ projectDescription(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="接入方式" width="120">
          <template #default="{ row }">
            <el-tag :type="kindTagType(row.projectKind)" effect="light">
              {{ formatProjectKindLabel(row.projectKind || 'SCAN') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="status-pill" :class="`status-${row.status || 'created'}`">
              <i />
              {{ statusLabel(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="API 数量" width="100" align="center">
          <template #default="{ row }">{{ apiCountOf(row) }}</template>
        </el-table-column>
        <el-table-column label="SDK 版本" width="110" align="center">
          <template #default="{ row }">{{ sdkVersionLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="最近扫描时间" width="160">
          <template #default="{ row }">
            <span class="muted">{{ formatDate(row.lastScannedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="负责人" width="120">
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

      <div v-if="filteredProjects.length > 0" class="table-footer">
        <span>共 {{ filteredProjects.length }} 条</span>
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          background
          layout="prev, pager, next, sizes"
          :page-sizes="[5, 10, 20, 50]"
          :total="filteredProjects.length"
        />
      </div>
    </el-card>

    <el-dialog v-model="accessDialogVisible" title="接入项目" width="760px" destroy-on-close>
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
                <el-radio label="openapi">OpenAPI</el-radio>
                <el-radio label="controller">Controller</el-radio>
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
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Box,
  Connection,
  FolderChecked,
  Plus,
  Refresh,
  RefreshRight,
  Search,
} from '@element-plus/icons-vue'
import {
  createScanProject,
  getScanProjects,
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
const kindFilter = ref('')
const statusFilter = ref('')
const ownerFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const accessDialogVisible = ref(false)
const accessDialogTab = ref<'sdk' | 'scan'>('sdk')

/** 限制表格主体高度，使横向滚动条落在视口内（靠近浏览器窗口底部），无需先滚到卡片最底 */
const projectTableMaxHeight = ref(480)

let mainContentScrollEl: HTMLElement | null = null

let tableHeightRaf = 0
function scheduleUpdateProjectTableMaxHeight() {
  if (typeof window === 'undefined') return
  if (tableHeightRaf) return
  tableHeightRaf = requestAnimationFrame(() => {
    tableHeightRaf = 0
    updateProjectTableMaxHeight()
  })
}

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

const ownerOptions = computed(() => {
  return Array.from(new Set(projects.value.map((project) => project.owner).filter(Boolean))) as string[]
})

const filteredProjects = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return projects.value.filter((project) => {
    const kind = project.projectKind || 'SCAN'
    const matchesContext = !projectStore.currentProjectId || project.id === projectStore.currentProjectId
    const matchesKeyword =
      !text ||
      [project.name, project.projectCode, project.owner, project.baseUrl, project.environment, project.description]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(text))

    return (
      matchesContext &&
      matchesKeyword &&
      (!kindFilter.value || kind === kindFilter.value) &&
      (!statusFilter.value || project.status === statusFilter.value) &&
      (!ownerFilter.value || project.owner === ownerFilter.value)
    )
  })
})

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
  const ownerCount = ownerOptions.value.length
  return [
    { label: '已注册项目数', value: total, caption: '统一项目目录', delta: ownerCount ? `${ownerCount} 位负责人` : '', icon: FolderChecked, tone: 'purple' },
    { label: '已接入 API 数', value: apiCount, caption: '来自 SDK 与扫描', delta: apiCount ? '已同步目录' : '', icon: Connection, tone: 'blue' },
    { label: '已生成 SDK 数', value: sdkCount, caption: 'SDK / 混合接入', delta: sdkCount ? '可生成接入配置' : '', icon: Box, tone: 'green' },
    {
      label: '最近扫描情况',
      value: `${scannedCount} / ${failedCount}`,
      caption: '扫描项目 / 异常项目',
      delta: failedCount ? `异常 ${failedCount}` : '稳定',
      icon: RefreshRight,
      tone: 'orange',
    },
  ]
})

onMounted(() => {
  loadProjects().finally(() => {
    nextTick(scheduleUpdateProjectTableMaxHeight)
  })
  mainContentScrollEl = document.querySelector('.main-layout .main-content') as HTMLElement | null
  mainContentScrollEl?.addEventListener('scroll', scheduleUpdateProjectTableMaxHeight, { passive: true })
  window.addEventListener('resize', scheduleUpdateProjectTableMaxHeight)
})

onUnmounted(() => {
  mainContentScrollEl?.removeEventListener('scroll', scheduleUpdateProjectTableMaxHeight)
  window.removeEventListener('resize', scheduleUpdateProjectTableMaxHeight)
})

watch([keyword, kindFilter, statusFilter, ownerFilter, () => projectStore.currentProjectId, pageSize], () => {
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

async function loadProjects() {
  loading.value = true
  try {
    const { data } = await getScanProjects()
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
  ownerFilter.value = ''
  currentPage.value = 1
}

function kindTagType(kind?: string) {
  if (kind === 'REGISTERED') return 'success'
  if (kind === 'HYBRID') return 'warning'
  return 'info'
}

function statusLabel(project: ScanProject) {
  if (project.registryStatusSummary) return project.registryStatusSummary
  if (project.status === 'scanned') return '已接入'
  if (project.status === 'failed') return '异常'
  return formatScanStatusLabel(project.status)
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
  gap: 16px;
  min-height: 0;
  height: calc(100vh - 56px);
  padding: 24px 28px 24px;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  background: var(--brand-page-bg);
  background-size: 28px 28px, 28px 28px, auto, auto, auto, auto;
}

.page-hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: center;
  width: 100%;
  min-width: 0;
  margin: 0;
  flex-shrink: 0;

  h1 {
    margin: 0 0 8px;
    color: #111827;
    font-size: 26px;
    font-weight: 800;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: #667085;
    font-size: 14px;
  }
}

.hero-actions,
.empty-actions,
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.primary-action {
  --el-button-bg-color: var(--brand-primary);
  --el-button-border-color: var(--brand-primary);
  --el-button-hover-bg-color: var(--brand-hover);
  --el-button-hover-border-color: var(--brand-hover);
  min-width: 142px;
  border-radius: 8px;
  box-shadow: 0 10px 20px rgb(var(--brand-primary-rgb) / 0.22);
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

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  width: 100%;
  min-width: 0;
  margin: 0;
  flex-shrink: 0;
}

.metric-card {
  display: flex;
  gap: 14px;
  align-items: center;
  min-height: 92px;
  padding: 18px 20px;
  border: 1px solid #eaecf5;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 12px 28px rgba(17, 24, 39, 0.035);

  span,
  small {
    display: block;
    color: #667085;
  }

  span {
    font-size: 12px;
  }

  strong {
    display: block;
    margin: 5px 0 4px;
    color: #101828;
    font-size: 25px;
    font-weight: 800;
    line-height: 1;
  }

  small {
    font-size: 12px;

    em {
      margin-left: 6px;
      color: #039855;
      font-style: normal;
      font-weight: 700;
    }
  }
}

.metric-icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border: 1px solid transparent;
  border-radius: 14px;
  font-size: 22px;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.8),
    0 10px 22px rgba(16, 24, 40, 0.06);

  .el-icon {
    filter: drop-shadow(0 1px 0 rgba(255, 255, 255, 0.7));
  }

  &.purple {
    color: var(--brand-active);
    border-color: rgb(var(--brand-hover-rgb) / 0.22);
    background: linear-gradient(135deg, rgb(var(--brand-selected-rgb) / 0.7), rgba(255, 255, 255, 0.86));
  }

  &.blue {
    color: var(--brand-primary);
    border-color: rgb(var(--brand-primary-rgb) / 0.2);
    background: linear-gradient(135deg, rgb(var(--brand-selected-rgb) / 0.72), rgba(255, 255, 255, 0.88));
  }

  &.green {
    color: #16a34a;
    border-color: #cdefd8;
    background: linear-gradient(135deg, #ecfdf3, #dcfce7);
  }

  &.orange {
    color: #ea580c;
    border-color: #fedfc2;
    background: linear-gradient(135deg, #fff7ed, #ffedd5);
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
  grid-template-columns: minmax(260px, 2fr) repeat(3, minmax(150px, 1fr)) auto auto;
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

.context-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 22px;
  color: var(--brand-primary);
  background: rgb(var(--brand-selected-rgb) / 0.52);
  border-bottom: 1px solid rgb(var(--brand-selected-rgb) / 0.72);
  font-size: 13px;
  flex-shrink: 0;
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
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: #fff;
  font-weight: 800;

  &.sdk {
    background: linear-gradient(135deg, var(--brand-hover), var(--brand-active));
  }

  &.hybrid {
    background: linear-gradient(135deg, #f97316, #f59e0b);
  }

  &.scan {
    background: linear-gradient(135deg, #22c55e, #14b8a6);
  }
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

  :deep(.el-pagination.is-background .el-pager li.is-active) {
    background-color: var(--brand-primary);
  }
}

.registry-project-page.is-dark {
  background:
    radial-gradient(circle at 14% 8%, rgb(var(--brand-primary-rgb) / 0.18), transparent 28%),
    linear-gradient(180deg, #0a0a0f 0%, #10101a 48%, #0a0a0f 100%);

  .page-hero {
    h1 {
      color: #e2e8f0;
    }

    p {
      color: #94a3b8;
    }
  }

  .primary-action {
    box-shadow: 0 10px 22px rgb(var(--brand-primary-rgb) / 0.28);
  }

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

  .metric-card,
  .project-card {
    border-color: rgba(255, 255, 255, 0.07);
    background: rgba(255, 255, 255, 0.035);
    box-shadow: 0 14px 34px rgba(0, 0, 0, 0.22);
    backdrop-filter: blur(12px);
  }

  .empty-illustration {
    background: linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.22), rgb(var(--brand-hover-rgb) / 0.08));
  }

  .metric-card {
    span,
    small {
      color: #94a3b8;
    }

    strong {
      color: #e2e8f0;
    }

    small em {
      color: #22d3ee;
    }
  }

  .metric-icon {
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.08),
      0 12px 26px rgba(0, 0, 0, 0.18);

    .el-icon {
      filter: drop-shadow(0 0 10px currentColor);
    }

    &.purple {
      color: var(--brand-selected-bg);
      border-color: rgb(var(--brand-hover-rgb) / 0.22);
      background: rgb(var(--brand-hover-rgb) / 0.18);
    }

    &.blue {
      color: #93c5fd;
      border-color: rgba(147, 197, 253, 0.18);
      background: rgba(37, 99, 235, 0.18);
    }

    &.green {
      color: #86efac;
      border-color: rgba(134, 239, 172, 0.16);
      background: rgba(22, 163, 74, 0.16);
    }

    &.orange {
      color: #fdba74;
      border-color: rgba(253, 186, 116, 0.18);
      background: rgba(234, 88, 12, 0.16);
    }
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

  .context-filter {
    color: var(--brand-selected-bg);
    background: rgb(var(--brand-primary-rgb) / 0.12);
    border-bottom-color: rgb(var(--brand-primary-rgb) / 0.22);

    :deep(.el-button.is-link) {
      color: var(--brand-disabled);

      &:hover {
        color: var(--brand-selected-bg);
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

    :deep(.el-pagination.is-background .btn-prev),
    :deep(.el-pagination.is-background .btn-next),
    :deep(.el-pagination.is-background .el-pager li) {
      color: #94a3b8;
      background: rgba(255, 255, 255, 0.035);
    }

    :deep(.el-pagination.is-background .btn-prev:hover),
    :deep(.el-pagination.is-background .btn-next:hover),
    :deep(.el-pagination.is-background .el-pager li:hover) {
      color: var(--brand-selected-bg);
      background: rgb(var(--brand-primary-rgb) / 0.12);
    }

    :deep(.el-pagination.is-background .el-pager li.is-active) {
      color: #fff;
      background: linear-gradient(135deg, var(--brand-primary), var(--brand-hover));
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

:global(.main-layout.registry-shell .main-content:has(.registry-project-page)) {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

@media (max-width: 1200px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .page-hero {
    align-items: flex-start;
    flex-direction: column;
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
  .registry-project-page {
    padding: 18px 14px 24px;
  }

  .metric-grid {
    grid-template-columns: 1fr;
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

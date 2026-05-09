<template>
  <div class="registry-project-page" :class="{ 'is-dark': theme === 'dark' }">
    <div class="page-hero">
      <div>
        <h1>AI 项目管理</h1>
        <p>统一管理 AI 项目的注册、SDK 接入、API 接入与能力扫描</p>
      </div>
      <div class="hero-actions">
        <el-button type="primary" size="large" class="primary-action" :icon="Plus" @click="openSdkDialog">
          创建 SDK 接入
        </el-button>
        <el-button size="large" class="secondary-action" :icon="Refresh" @click="openScanCreateDialog">扫描项目</el-button>
      </div>
    </div>

    <section v-if="accessBannerVisible" class="access-banner">
      <button type="button" class="banner-close" aria-label="关闭提示" @click="dismissAccessBanner">
        <el-icon :size="12"><Close /></el-icon>
      </button>
      <div class="banner-icon" aria-hidden="true">
        <img class="banner-icon-img" src="/SDK接入1.svg" alt="" />
      </div>
      <div class="banner-copy">
        <strong>通过 SDK 接入或扫描项目，快速注册并发现 AI 能力</strong>
        <span>支持多种接入方式，自动识别 API 能力，生成 SDK，统一管理项目全生命周期。</span>
      </div>
      <div class="banner-links">
        <el-button link type="primary" @click="guideDrawerVisible = true">了解接入流程</el-button>
        <el-button link type="primary" @click="guideDrawerVisible = true">查看接入文档</el-button>
      </div>
    </section>

    <div class="metric-grid">
      <div v-for="metric in metrics" :key="metric.label" class="metric-card">
        <div class="metric-icon" :class="metric.tone">{{ metric.icon }}</div>
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
      <el-tabs v-model="activeTab" class="project-tabs">
        <el-tab-pane label="全部项目" name="all" />
        <el-tab-pane label="SDK 接入" name="sdk" />
        <el-tab-pane label="API 接入" name="api" />
        <el-tab-pane label="扫描记录" name="scan" />
      </el-tabs>

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

      <el-table v-loading="loading" :data="pagedProjects" row-key="id" class="project-table">
        <el-table-column label="项目名称" min-width="230">
          <template #default="{ row }">
            <div class="project-name-cell">
              <div class="project-avatar" :class="avatarClass(row.projectKind)">{{ projectInitial(row.name) }}</div>
              <div>
                <strong>{{ row.name }}</strong>
                <span>{{ row.projectCode || `ID ${row.id}` }}</span>
              </div>
            </div>
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
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">查看详情</el-button>
            <el-button link type="primary" @click="goApiConfig(row)">配置 API</el-button>
            <el-button
              link
              type="primary"
              :disabled="row.projectKind === 'REGISTERED'"
              :loading="scanLoadingId === row.id"
              @click="handleScan(row)"
            >
              {{ row.toolCount > 0 ? '重新扫描' : '扫描' }}
            </el-button>
            <el-dropdown trigger="click" @command="(cmd: string) => handleRowCommand(row, cmd)">
              <el-button link>更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑项目</el-dropdown-item>
                  <el-dropdown-item command="sdk">接入文档</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除项目</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
              <p>你可以通过创建 SDK 接入新项目，或扫描已有项目自动发现 API 能力。</p>
            </div>
            <div class="empty-actions">
              <el-button type="primary" :icon="Plus" @click="openSdkDialog">创建 SDK 接入</el-button>
              <el-button :icon="Refresh" @click="openScanCreateDialog">扫描已有项目</el-button>
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

    <el-dialog v-model="sdkDialogVisible" title="创建 SDK 接入" width="660px">
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
        <el-form-item label="Context Path">
          <el-input v-model="sdkForm.contextPath" placeholder="/api" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="App Key">
              <el-input v-model="sdkForm.appKey" placeholder="留空由后端策略处理" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="App Secret">
              <el-input v-model="sdkForm.appSecret" show-password placeholder="留空由后端策略处理" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="sdkDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSdkProject">保存并生成接入配置</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scanDialogVisible" :title="isScanEditMode ? `编辑项目 - ${scanForm.name}` : '扫描已有项目'" width="720px">
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
              <el-input v-model="scanForm.baseUrl" placeholder="http://localhost:8602" />
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
          <div v-if="scanForm.projectKind === 'REGISTERED'" class="form-hint">SDK 注册项目可不配置扫描路径。</div>
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
      <template #footer>
        <el-button @click="scanDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveScanProject">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="guideDrawerVisible" title="业务系统接入指南（EAF SDK）" size="560px" destroy-on-close>
      <div class="guide-drawer">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="接入思路"
          description="在本页创建 SDK 接入后，业务系统引入 ai-spring-boot-starter，配置注册中心地址与项目信息；应用启动后会自动注册项目、上报心跳、扫描 Controller 能力并同步到平台。"
        />

        <h3 class="guide-h3">1. Maven 依赖</h3>
        <div class="guide-code-wrap">
          <el-button class="guide-copy" size="small" text type="primary" @click="copyText(mavenSnippet)">复制</el-button>
          <pre class="guide-pre">{{ mavenSnippet }}</pre>
        </div>

        <h3 class="guide-h3">2. Spring Boot 配置</h3>
        <div class="guide-code-wrap">
          <el-button class="guide-copy" size="small" text type="primary" @click="copyText(yamlSnippet)">复制</el-button>
          <pre class="guide-pre">{{ yamlSnippet }}</pre>
        </div>

        <h3 class="guide-h3">3. 能力声明（可选）</h3>
        <div class="guide-code-wrap">
          <el-button class="guide-copy" size="small" text type="primary" @click="copyText(javaSnippet)">复制</el-button>
          <pre class="guide-pre">{{ javaSnippet }}</pre>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, Close } from '@element-plus/icons-vue'
import {
  createScanProject,
  deleteScanProject,
  getScanProjectOperationBlockers,
  getScanProjects,
  triggerRescan,
  triggerScan,
  updateScanProject,
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
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'

const router = useRouter()
const projectStore = useProjectStore()
const { theme } = useTheme()

const ACCESS_BANNER_STORAGE_KEY = 'eaf-registry-access-banner-dismissed'

function readAccessBannerDismissed(): boolean {
  if (typeof sessionStorage === 'undefined') return false
  return sessionStorage.getItem(ACCESS_BANNER_STORAGE_KEY) === '1'
}

const accessBannerVisible = ref(!readAccessBannerDismissed())

function dismissAccessBanner() {
  accessBannerVisible.value = false
  try {
    sessionStorage.setItem(ACCESS_BANNER_STORAGE_KEY, '1')
  } catch {
    // ignore private mode / quota
  }
}

const loading = ref(false)
const saving = ref(false)
const scanLoadingId = ref<number | null>(null)
const projects = ref<ScanProject[]>([])
const activeTab = ref('all')
const keyword = ref('')
const kindFilter = ref('')
const statusFilter = ref('')
const ownerFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const sdkDialogVisible = ref(false)
const scanDialogVisible = ref(false)
const guideDrawerVisible = ref(false)
const editingScanId = ref<number | null>(null)

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
const isScanEditMode = computed(() => editingScanId.value !== null)

const ownerOptions = computed(() => {
  return Array.from(new Set(projects.value.map((project) => project.owner).filter(Boolean))) as string[]
})

const filteredProjects = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return projects.value.filter((project) => {
    const kind = project.projectKind || 'SCAN'
    const matchesTab =
      activeTab.value === 'all' ||
      (activeTab.value === 'sdk' && (kind === 'REGISTERED' || kind === 'HYBRID')) ||
      (activeTab.value === 'api' && project.toolCount > 0) ||
      (activeTab.value === 'scan' && kind !== 'REGISTERED')
    const matchesContext = !projectStore.currentProjectId || project.id === projectStore.currentProjectId
    const matchesKeyword =
      !text ||
      [project.name, project.projectCode, project.owner, project.baseUrl, project.environment, project.description]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(text))

    return (
      matchesContext &&
      matchesTab &&
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
    { label: '已注册项目数', value: total, caption: '统一项目目录', delta: ownerCount ? `${ownerCount} 位负责人` : '', icon: 'P', tone: 'purple' },
    { label: '已接入 API 数', value: apiCount, caption: '来自 SDK 与扫描', delta: apiCount ? '已同步目录' : '', icon: 'API', tone: 'blue' },
    { label: '已生成 SDK 数', value: sdkCount, caption: 'SDK / 混合接入', delta: sdkCount ? '可生成接入配置' : '', icon: 'SDK', tone: 'green' },
    {
      label: '最近扫描情况',
      value: `${scannedCount} / ${failedCount}`,
      caption: '扫描项目 / 异常项目',
      delta: failedCount ? `异常 ${failedCount}` : '稳定',
      icon: '!',
      tone: 'orange',
    },
  ]
})

onMounted(loadProjects)

watch([activeTab, keyword, kindFilter, statusFilter, ownerFilter, () => projectStore.currentProjectId, pageSize], () => {
  currentPage.value = 1
})

const mavenSnippet = `<!-- 与 Enterprise Agent Framework 根 pom 版本一致，或改为你们私服坐标 -->
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>ai-spring-boot-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>`

const yamlSnippet = `eaf:
  registry:
    enabled: true
    url: http://localhost:8603
    app-key: your-app-key
    app-secret: your-app-secret
  project:
    code: your-project-code
    name: 你的业务系统名称
    base-url: http://localhost:8080
    environment: dev
  capability:
    scan-controller: true
    sync-on-startup: true`

const javaSnippet = `@RestController
@RequestMapping("/api/orders")
public class OrderApi {
    @AiCapability(name = "queryOrder", title = "查询订单")
    @GetMapping("/{orderNo}")
    public OrderDTO get(@AiParam("订单号") @PathVariable String orderNo) {
        return orderService.get(orderNo);
    }
}`

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

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
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
  }
}

function openSdkDialog() {
  resetSdkForm()
  sdkDialogVisible.value = true
}

function openScanCreateDialog() {
  editingScanId.value = null
  applyScanForm(createEmptyScanForm())
  scanDialogVisible.value = true
}

function openScanEditDialog(project: ScanProject) {
  editingScanId.value = project.id
  applyScanForm(project)
  scanDialogVisible.value = true
}

async function saveSdkProject() {
  if (!sdkForm.name.trim() || !sdkForm.projectCode.trim() || !sdkForm.baseUrl.trim()) {
    ElMessage.warning('请填写项目名称、项目编码和 Base URL')
    return
  }
  saving.value = true
  try {
    await registerRegistryProject({
      ...sdkForm,
      appKey: sdkForm.appKey || undefined,
      appSecret: sdkForm.appSecret || undefined,
    })
    ElMessage.success('SDK 接入项目已创建')
    sdkDialogVisible.value = false
    await loadProjects()
    guideDrawerVisible.value = true
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
    if (isScanEditMode.value && editingScanId.value !== null) {
      await updateScanProject(editingScanId.value, payload)
      ElMessage.success('项目已更新')
    } else {
      await createScanProject(payload)
      ElMessage.success('扫描项目已创建')
    }
    scanDialogVisible.value = false
    await loadProjects()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleScan(project: ScanProject) {
  scanLoadingId.value = project.id
  try {
    const { data: blockers } = await getScanProjectOperationBlockers(project.id)
    if (blockers.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    const request = project.toolCount > 0 ? triggerRescan(project.id) : triggerScan(project.id)
    const { data } = await request
    ElMessage.success(`${project.toolCount > 0 ? '重新扫描' : '扫描'}完成，发现 ${data.toolCount} 个接口`)
    await loadProjects()
  } catch (error) {
    const blockers = parseScanProjectBlockersFromError(error)
    if (blockers?.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '操作被阻止', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    ElMessage.error((error as Error).message || '扫描失败')
    await loadProjects()
  } finally {
    scanLoadingId.value = null
  }
}

async function handleDelete(project: ScanProject) {
  try {
    const { data: blockers } = await getScanProjectOperationBlockers(project.id)
    if (blockers.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '无法删除', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    await ElMessageBox.confirm(`确认删除项目 ${project.name} 吗？关联扫描工具也会一并删除。`, '删除确认', {
      type: 'warning',
    })
    await deleteScanProject(project.id)
    ElMessage.success('项目已删除')
    await loadProjects()
  } catch (error) {
    if ((error as Error).message === 'cancel') return
    const blockers = parseScanProjectBlockersFromError(error)
    if (blockers?.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '无法删除', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    ElMessage.error((error as Error).message || '删除失败')
  }
}

function handleRowCommand(project: ScanProject, command: string) {
  if (command === 'edit') openScanEditDialog(project)
  if (command === 'sdk') guideDrawerVisible.value = true
  if (command === 'delete') handleDelete(project)
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

function goApiConfig(project: ScanProject) {
  router.push(`/scan-project/${project.id}`)
}
</script>

<style scoped lang="scss">
.registry-project-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 100%;
  padding: 24px 28px 32px;
  background:
    radial-gradient(circle at 14% 8%, rgba(99, 102, 241, 0.08), transparent 26%),
    linear-gradient(180deg, #f8f9ff 0%, #f6f7fb 42%, #f8fafc 100%);
}

.page-hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: center;
  max-width: 1180px;
  width: 100%;
  margin: 0 auto;

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
.banner-links,
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.primary-action {
  --el-button-bg-color: #5b3df5;
  --el-button-border-color: #5b3df5;
  --el-button-hover-bg-color: #4f34df;
  --el-button-hover-border-color: #4f34df;
  min-width: 142px;
  border-radius: 8px;
  box-shadow: 0 10px 20px rgba(91, 61, 245, 0.22);
}

.secondary-action {
  min-width: 112px;
  border-radius: 8px;
  color: #344054;
  background: #fff;
  border-color: #d9deea;
}

.access-banner {
  position: relative;
  display: flex;
  align-items: center;
  gap: 16px;
  max-width: 1180px;
  width: 100%;
  margin: 0 auto;
  padding: 18px 38px 18px 22px;
  border: 1px solid #eaecf5;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 12px 32px rgba(17, 24, 39, 0.04);
}

.banner-close {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1px solid #e4e7ee;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.95);
  color: #667085;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    color 0.15s ease,
    background 0.15s ease,
    box-shadow 0.15s ease;

  &:hover {
    border-color: #c7d2fe;
    color: #5b3df5;
    background: #f5f3ff;
    box-shadow: 0 1px 6px rgba(91, 61, 245, 0.1);
  }

  &:focus-visible {
    outline: 2px solid rgba(91, 61, 245, 0.35);
    outline-offset: 2px;
  }
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
  background: linear-gradient(135deg, #ede9fe, #eff6ff);
}

.empty-illustration-img {
  max-width: 100%;
  max-height: 100%;
  width: auto;
  height: auto;
  object-fit: contain;
  display: block;
}

.banner-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 48px;
  height: 48px;
  border-radius: 14px;
  overflow: hidden;
  background: linear-gradient(135deg, #ede9fe, #eff6ff);
}

.banner-icon-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.banner-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;

  strong {
    color: #101828;
    font-size: 15px;
  }

  span {
    color: #667085;
    font-size: 13px;
  }
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  max-width: 1180px;
  width: 100%;
  margin: 0 auto;
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
  border-radius: 50%;
  font-size: 13px;
  font-weight: 800;

  &.purple {
    color: #6d28d9;
    background: #f3e8ff;
  }

  &.blue {
    color: #2563eb;
    background: #dbeafe;
  }

  &.green {
    color: #16a34a;
    background: #dcfce7;
  }

  &.orange {
    color: #ea580c;
    background: #ffedd5;
  }
}

.project-card {
  max-width: 1180px;
  width: 100%;
  margin: 0 auto;
  border: 1px solid #eaecf5;
  border-radius: 12px;
  box-shadow: 0 14px 34px rgba(17, 24, 39, 0.045);

  :deep(.el-card__body) {
    padding: 0;
  }
}

.project-tabs {
  padding: 0 22px;

  :deep(.el-tabs__header) {
    margin: 0;
  }

  :deep(.el-tabs__item) {
    height: 50px;
    color: #667085;
    font-weight: 600;
  }

  :deep(.el-tabs__item.is-active) {
    color: #5b3df5;
  }

  :deep(.el-tabs__active-bar) {
    background-color: #5b3df5;
  }
}

.toolbar {
  padding: 14px 22px 16px;
  border-bottom: 1px solid #eef1f7;
  background: #fff;

  .search-input {
    width: 260px;
  }

  .el-select {
    width: 142px;
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
  color: #5b3df5;
  background: #f4f2ff;
  border-bottom: 1px solid #e7e3ff;
  font-size: 13px;
}

.project-table {
  width: 100%;

  :deep(th.el-table__cell) {
    height: 44px;
    color: #475467;
    background: #f8f9fc;
    font-size: 12px;
    font-weight: 700;
  }

  :deep(td.el-table__cell) {
    height: 58px;
    color: #344054;
    border-bottom-color: #f0f2f7;
  }

  :deep(.el-table__row:hover > td.el-table__cell) {
    background: #fbfbff;
  }

  /* 固定操作列不透明，避免与下方行叠字 */
  :deep(.el-table-fixed-column--right),
  :deep(.el-table-fixed-column--left) {
    background-color: #fff !important;
  }

  :deep(th.el-table-fixed-column--right),
  :deep(th.el-table-fixed-column--left) {
    background-color: #f8f9fc !important;
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
    background-color: #fbfbff !important;
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

.project-avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: #fff;
  font-weight: 800;

  &.sdk {
    background: linear-gradient(135deg, #7c3aed, #4f46e5);
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
  color: #2563eb;

  i {
    background: #3b82f6;
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
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  margin: 20px;
  padding: 28px 32px;
  border: 1px dashed rgba(99, 102, 241, 0.28);
  border-radius: 18px;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.06), rgba(59, 130, 246, 0.03));

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

  :deep(.el-pagination.is-background .el-pager li.is-active) {
    background-color: #5b3df5;
  }
}

.guide-drawer {
  padding-right: 8px;
}

.guide-h3 {
  margin: 20px 0 8px;
  font-size: 15px;
  font-weight: 600;
}

.guide-code-wrap {
  position: relative;
}

.guide-copy {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
}

.guide-pre {
  margin: 0;
  padding: 12px;
  max-height: 280px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.registry-project-page.is-dark {
  background:
    radial-gradient(circle at 14% 8%, rgba(99, 102, 241, 0.18), transparent 28%),
    linear-gradient(180deg, #0a0a0f 0%, #10101a 48%, #0a0a0f 100%);

  .page-hero {
    h1 {
      color: #e2e8f0;
    }

    p {
      color: #94a3b8;
    }
  }

  .secondary-action {
    color: #cbd5e1;
    background: rgba(255, 255, 255, 0.04);
    border-color: rgba(255, 255, 255, 0.1);

    &:hover {
      color: #e2e8f0;
      background: rgba(99, 102, 241, 0.12);
      border-color: rgba(99, 102, 241, 0.35);
    }
  }

  .primary-action {
    box-shadow: 0 10px 22px rgba(99, 102, 241, 0.28);
  }

  .banner-links {
    :deep(.el-button.is-link) {
      color: #a5b4fc;

      &:hover {
        color: #e0e7ff;
      }
    }
  }

  .empty-actions {
    :deep(.el-button--default) {
      color: #cbd5e1;
      background: rgba(255, 255, 255, 0.04);
      border-color: rgba(255, 255, 255, 0.1);

      &:hover {
        color: #e2e8f0;
        background: rgba(99, 102, 241, 0.12);
        border-color: rgba(99, 102, 241, 0.35);
      }
    }
  }

  .access-banner,
  .metric-card,
  .project-card {
    border-color: rgba(255, 255, 255, 0.07);
    background: rgba(255, 255, 255, 0.035);
    box-shadow: 0 14px 34px rgba(0, 0, 0, 0.22);
    backdrop-filter: blur(12px);
  }

  .access-banner {
    background:
      radial-gradient(circle at 0% 0%, rgba(99, 102, 241, 0.13), transparent 34%),
      rgba(255, 255, 255, 0.035);
  }

  .banner-close {
    color: #94a3b8;
    border-color: rgba(255, 255, 255, 0.1);
    background: rgba(15, 23, 42, 0.92);

    &:hover {
      color: #c7d2fe;
      border-color: rgba(129, 140, 248, 0.45);
      background: rgba(99, 102, 241, 0.18);
      box-shadow: 0 1px 8px rgba(99, 102, 241, 0.16);
    }
  }

  .banner-icon,
  .empty-illustration {
    background: linear-gradient(135deg, rgba(99, 102, 241, 0.22), rgba(34, 211, 238, 0.08));
  }

  .banner-copy {
    strong {
      color: #e2e8f0;
    }

    span {
      color: #94a3b8;
    }
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
    &.purple {
      color: #c4b5fd;
      background: rgba(124, 58, 237, 0.18);
    }

    &.blue {
      color: #93c5fd;
      background: rgba(37, 99, 235, 0.18);
    }

    &.green {
      color: #86efac;
      background: rgba(22, 163, 74, 0.16);
    }

    &.orange {
      color: #fdba74;
      background: rgba(234, 88, 12, 0.16);
    }
  }

  .project-tabs {
    :deep(.el-tabs__header) {
      background: transparent;
    }

    :deep(.el-tabs__nav-wrap) {
      background: transparent;
    }

    :deep(.el-tabs__content) {
      background: transparent;
    }

    :deep(.el-tab-pane) {
      background: transparent;
    }

    :deep(.el-tabs__item) {
      color: #94a3b8;
    }

    :deep(.el-tabs__item.is-active),
    :deep(.el-tabs__item:hover) {
      color: #c7d2fe;
    }

    :deep(.el-tabs__active-bar) {
      background: linear-gradient(90deg, #6366f1, #8b5cf6);
    }

    :deep(.el-tabs__nav-wrap::after) {
      background-color: rgba(255, 255, 255, 0.06);
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
        background: rgba(99, 102, 241, 0.12);
        border-color: rgba(99, 102, 241, 0.35);
      }
    }
  }

  .context-filter {
    color: #c7d2fe;
    background: rgba(99, 102, 241, 0.12);
    border-bottom-color: rgba(99, 102, 241, 0.22);

    :deep(.el-button.is-link) {
      color: #a5b4fc;

      &:hover {
        color: #e0e7ff;
      }
    }
  }

  .owner-cell span {
    color: #cbd5e1;
  }

  .owner-cell :deep(.el-avatar) {
    border: 1px solid rgba(255, 255, 255, 0.1);
    background: rgba(99, 102, 241, 0.22);
    color: #e2e8f0;
  }

  .project-table {
    --el-table-bg-color: transparent;
    --el-table-tr-bg-color: transparent;
    --el-table-header-bg-color: rgba(255, 255, 255, 0.045);
    --el-table-row-hover-bg-color: rgba(99, 102, 241, 0.08);
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
      background: rgba(99, 102, 241, 0.08) !important;
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
      color: #a5b4fc;

      &:hover {
        color: #e0e7ff;
      }

      &.is-disabled {
        color: rgba(148, 163, 184, 0.45);
      }
    }

    /* 「更多」为默认 link；触发器外层可能是 span，勿用子选择器 */
    :deep(.el-dropdown .el-button.is-link:not(.el-button--primary):not(.is-disabled)) {
      color: #94a3b8;

      &:hover {
        color: #c7d2fe;
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

  .muted,
  .form-hint {
    color: #94a3b8;
  }

  .empty-state {
    border-color: rgba(99, 102, 241, 0.22);
    background: linear-gradient(135deg, rgba(99, 102, 241, 0.1), rgba(34, 211, 238, 0.04));

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
      color: #c7d2fe;
      background: rgba(99, 102, 241, 0.12);
    }

    :deep(.el-pagination.is-background .el-pager li.is-active) {
      color: #fff;
      background: linear-gradient(135deg, #6366f1, #8b5cf6);
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

  .guide-h3 {
    color: #e2e8f0;
  }

  .guide-drawer {
    :deep(.el-alert) {
      background: rgba(255, 255, 255, 0.04);
      border-color: rgba(255, 255, 255, 0.08);
    }

    :deep(.el-alert__title) {
      color: #e2e8f0;
    }

    :deep(.el-alert__description) {
      color: #94a3b8;
    }
  }

  .guide-pre {
    color: #cbd5e1;
    border-color: rgba(255, 255, 255, 0.08);
    background: rgba(0, 0, 0, 0.24);
  }
}

@media (max-width: 1200px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .page-hero,
  .access-banner {
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
    .search-input {
      width: 100%;
      min-width: 240px;
      flex: 1 1 260px;
    }

    .el-select {
      width: calc(50% - 8px);
      min-width: 150px;
      flex: 1 1 150px;
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

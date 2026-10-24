<template>
  <div class="registry-project-page project-workbench-page workbench-page--list">
    <CollapsibleHeaderRegion :collapsed="isProjectHeaderCollapsed">
      <PageHeader
        variant="overview"
        domain="project"
        presentation="plain"
        action-presentation="inline"
        title="项目管理"
        description="管理项目接入、SDK 注册与 API 扫描。"
        :collapsed="isProjectHeaderCollapsed"
      >
        <template #actions>
          <LiquidGlassButton v-if="canCreateProject" type="primary" :icon="Plus" source="wallpaper" @click="openAccessDialog('sdk')">接入项目</LiquidGlassButton>
        </template>
      </PageHeader>

      <template #summary>
        <MetricStrip :items="metrics" class="glass-workspace-adaptive" source="wallpaper" aria-label="项目接入概况" />
      </template>
    </CollapsibleHeaderRegion>

    <section class="project-card workbench-list-surface" aria-label="项目列表">
      <LiquidGlassTable
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        class="project-list-panel"
        :loading="loading"
        :total="filteredProjects.length"
        :page-sizes="[5, 10, 20, 50]"
        pagination-label="项目分页"
      >
        <template #toolbar>
          <div class="toolbar">
            <LiquidGlassInput
              v-model="keyword"
              class="search-input"
              clearable
              aria-label="搜索项目"
              :prefix-icon="Search"
              placeholder="搜索项目名称、描述、负责人"
              @keyup.enter="handleSearch"
            />
            <LiquidGlassSelect v-model="kindFilter" dropdown-title="接入方式" clearable placeholder="接入方式" aria-label="接入方式">
              <el-option label="SDK 接入" value="REGISTERED" />
              <el-option label="API / 扫描接入" value="SCAN" />
              <el-option label="混合接入" value="HYBRID" />
            </LiquidGlassSelect>
            <LiquidGlassSelect v-model="statusFilter" dropdown-title="项目状态" clearable placeholder="项目状态" aria-label="项目状态">
              <el-option label="已创建" value="created" />
              <el-option label="扫描中" value="scanning" />
              <el-option label="已接入" value="scanned" />
              <el-option label="异常" value="failed" />
            </LiquidGlassSelect>
            <div class="toolbar-actions">
              <LiquidGlassButton
                :icon="RefreshRight"
                circle
                aria-label="重置"
                title="重置"
                @click="resetFilters"
              />
              <LiquidGlassButton type="primary" :icon="Search" :loading="loading" @click="handleSearch">搜索</LiquidGlassButton>
            </div>
          </div>
        </template>

        <div class="project-table-shell">
          <el-table
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
            <el-table-column label="接入方式" width="112">
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

          <div v-if="!loading && filteredProjects.length === 0" class="empty-state" role="status">
            <div class="empty-main">
              <div class="empty-illustration" aria-hidden="true">
                <img class="empty-illustration-img" src="/智能化.svg" alt="" />
              </div>
              <div class="empty-body">
                <div class="empty-copy">
                  <h3>{{ hasActiveFilters ? '没有找到匹配项目' : '还没有接入任何 AI 项目' }}</h3>
                  <p>{{ hasActiveFilters ? '试试其他关键词，或清除筛选查看全部项目。' : '通过 SDK 注册或 API 扫描接入你的第一个项目。' }}</p>
                </div>
                <div class="empty-actions">
                  <el-button v-if="hasActiveFilters" @click="resetFilters">清除筛选</el-button>
                  <el-button v-else-if="canCreateProject" type="primary" :icon="Plus" @click="openAccessDialog('sdk')">接入项目</el-button>
                </div>
              </div>
            </div>
          </div>
        </div>

      </LiquidGlassTable>
    </section>

    <AppDialog
      v-model="accessDialogVisible"
      title="接入项目"
      description="选择接入方式，配置项目与业务系统的连接信息。"
      class="project-access-dialog"
      width="760px"
      destroy-on-close
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <LiquidGlassTabs v-model="accessDialogTab" class="access-dialog-tabs">
        <el-tab-pane label="SDK 接入" name="sdk">
          <el-form :model="sdkForm" label-position="top" :disabled="saving" novalidate class="project-access-form">
            <div class="app-overlay-form-grid">
              <el-form-item label="项目名称" required>
                <el-input v-model="sdkForm.name" placeholder="如：智能客服平台" />
              </el-form-item>
              <el-form-item label="项目编码" required>
                <el-input v-model="sdkForm.projectCode" placeholder="如：customer-service" />
              </el-form-item>
              <el-form-item label="环境">
                <el-input v-model="sdkForm.environment" placeholder="dev / test / prod" />
              </el-form-item>
              <el-form-item label="负责人">
                <el-input v-model="sdkForm.owner" placeholder="负责人姓名" />
              </el-form-item>
              <el-form-item label="可见性">
                <LiquidGlassSelect v-model="sdkForm.visibility" dropdown-title="项目可见性" aria-label="项目可见性">
                  <el-option v-for="opt in VISIBILITY_SELECT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                </LiquidGlassSelect>
              </el-form-item>
              <el-form-item label="服务地址（Base URL）" required>
                <el-input v-model="sdkForm.baseUrl" placeholder="http://localhost:8080" />
              </el-form-item>
            </div>
            <LiquidGlassNotice
              title="生成配置，启动后自动注册"
              type="success"
              description="无需填写 App Key 或 App Secret。接入令牌仅限当前项目，72 小时内可成功使用一次。将 Starter 配置交给业务系统，首次启动注册成功后，项目会出现在列表中，长期凭据由 Starter 自动保存。"
              source="backdrop"
            />
            <LiquidGlassNotice
              v-if="enrollmentError"
              :title="enrollmentError"
              type="error"
              source="backdrop"
            />
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="扫描接入" name="scan">
          <el-form label-position="top" :disabled="saving" class="project-access-form">
            <div class="app-overlay-form-grid">
              <el-form-item label="项目名称" required class="app-overlay-form-grid__full">
                <el-input v-model="scanForm.name" placeholder="如 legacy-crm" />
              </el-form-item>
              <el-form-item label="项目编码">
                <el-input v-model="scanForm.projectCode" placeholder="如 order-service" />
              </el-form-item>
              <el-form-item label="接入方式">
                <LiquidGlassSelect v-model="scanForm.projectKind" dropdown-title="接入方式" aria-label="扫描项目接入方式">
                  <el-option v-for="opt in PROJECT_KIND_SELECT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                </LiquidGlassSelect>
              </el-form-item>
              <el-form-item label="环境">
                <el-input v-model="scanForm.environment" placeholder="dev / test / prod" />
              </el-form-item>
              <el-form-item label="负责人">
                <el-input v-model="scanForm.owner" placeholder="负责人姓名" />
              </el-form-item>
              <el-form-item label="服务地址（Base URL）" required>
                <el-input v-model="scanForm.baseUrl" placeholder="http://localhost:18602" />
              </el-form-item>
              <el-form-item label="可见性">
                <LiquidGlassSelect v-model="scanForm.visibility" dropdown-title="项目可见性" aria-label="扫描项目可见性">
                  <el-option v-for="opt in VISIBILITY_SELECT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                </LiquidGlassSelect>
              </el-form-item>
              <el-form-item label="扫描路径" class="app-overlay-form-grid__full" :required="scanForm.projectKind !== 'REGISTERED'">
                <el-input v-model="scanForm.scanPath" placeholder="服务器上的绝对路径" />
                <div v-if="scanForm.projectKind === 'REGISTERED'" class="form-hint">SDK 接入项目可不配置扫描路径。</div>
              </el-form-item>
              <el-form-item label="扫描方式" class="app-overlay-form-grid__full" required>
                <el-radio-group v-model="scanForm.scanType">
                  <el-radio value="openapi">OpenAPI</el-radio>
                  <el-radio value="controller">Controller</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="scanForm.scanType === 'openapi'" label="规范文件" class="app-overlay-form-grid__full">
                <el-input v-model="scanForm.specFile" placeholder="可选，相对 scanPath；留空自动发现" />
              </el-form-item>
            </div>
          </el-form>
        </el-tab-pane>
      </LiquidGlassTabs>
      <template #footer>
        <LiquidGlassButton :disabled="saving" @click="accessDialogVisible = false">取消</LiquidGlassButton>
        <LiquidGlassButton
          v-if="accessDialogTab === 'sdk'"
          type="primary"
          :loading="saving"
          @click="saveSdkProject"
        >
          生成 72 小时接入令牌
        </LiquidGlassButton>
        <LiquidGlassButton
          v-else
          type="primary"
          :loading="saving"
          @click="saveScanProject"
        >
          保存项目
        </LiquidGlassButton>
      </template>
    </AppDialog>

    <AppDialog
      v-model="enrollmentDialogVisible"
      title="一次性 SDK 接入令牌"
      description="请将完整配置交给业务系统，添加 Starter 依赖并启动。首次注册成功后项目才会出现在列表中。令牌仅在本弹窗保留，72 小时内只能成功使用一次；请通过受控密钥渠道传递，不要提交到代码仓库。"
      width="720px"
      destroy-on-close
      @close="clearEnrollment"
    >
      <el-form label-width="120px" novalidate class="enrollment-dialog-form">
        <el-form-item label="项目编码">
          <el-input :model-value="issuedEnrollment?.projectCode" readonly />
        </el-form-item>
        <el-form-item label="失效时间">
          <el-input :model-value="issuedEnrollment?.expiresAt" readonly />
        </el-form-item>
        <el-form-item label="Starter 配置">
          <el-input
            type="textarea"
            :rows="12"
            resize="none"
            :model-value="visibleEnrollmentSnippet"
            readonly
            class="enrollment-config-textarea"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <LiquidGlassButton @click="enrollmentTokenVisible = !enrollmentTokenVisible">
          {{ enrollmentTokenVisible ? '隐藏接入令牌' : '显示接入令牌' }}
        </LiquidGlassButton>
        <LiquidGlassButton type="primary" @click="copyEnrollmentSnippet">复制完整 Starter 配置</LiquidGlassButton>
        <LiquidGlassButton @click="enrollmentDialogVisible = false">关闭</LiquidGlassButton>
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
  RefreshRight,
  Search,
} from '@element-plus/icons-vue'
import CollapsibleHeaderRegion from '@/components/common/CollapsibleHeaderRegion.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { LiquidGlassButton, LiquidGlassInput, LiquidGlassSelect, LiquidGlassTable, LiquidGlassNotice, LiquidGlassTabs } from '@/components/common/liquid-glass'
import PageHeader from '@/components/common/PageHeader.vue'
import { projectCapabilityWorkspaceLocation } from '@/views/capability/businessCapabilityRoutes'
import { useCollapsiblePageHeader } from '@/composables/useCollapsiblePageHeader'
import {
  createScanProject,
  getScanProjects,
  type ScanProjectListQuery,
} from '@/api/scanProject'
import { issueRegistryEnrollment } from '@/api/registry'
import type { ScanProject, ScanProjectUpsertRequest } from '@/types/scanProject'
import type { RegistryEnrollmentToken, RegistryProjectRegisterRequest } from '@/types/registry'
import {
  PROJECT_KIND_SELECT_OPTIONS,
  VISIBILITY_SELECT_OPTIONS,
  formatProjectKindLabel,
  formatScanStatusLabel,
} from '@/utils/projectLabels'
import { usePlatformCan } from '@/auth/usePlatformCan'
import { PLATFORM_PERMISSION_WRITE } from '@/auth/platformAccess'
const { can } = usePlatformCan()
const canCreateProject = computed(() => can(PLATFORM_PERMISSION_WRITE))

const router = useRouter()

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
const enrollmentDialogVisible = ref(false)
const issuedEnrollment = ref<RegistryEnrollmentToken | null>(null)
const issuedProject = ref<RegistryProjectRegisterRequest | null>(null)
const enrollmentTokenVisible = ref(false)
const enrollmentError = ref('')

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
  const tableEl = root?.querySelector('.project-table') as HTMLElement | undefined
  const footerEl = root?.querySelector('.table-footer') as HTMLElement | undefined
  if (!tableEl) {
    projectTableMaxHeight.value = Math.max(280, window.innerHeight - 420)
    return
  }
  const tableTop = tableEl.getBoundingClientRect().top
  const viewportPad = 14
  // The footer follows the rows. Using its current top would lock a short list's
  // height after a search; reserve only its height when computing available room.
  const footerHeight = footerEl?.offsetHeight || 0
  const bottomLimit = window.innerHeight - viewportPad - footerHeight
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
})

const scanForm = reactive<ScanProjectUpsertRequest>(createEmptyScanForm())

const filteredProjects = computed(() => projects.value)
const hasActiveFilters = computed(() => Boolean(keyword.value.trim() || kindFilter.value || statusFilter.value))

const enrollmentSnippet = computed(() => {
  if (!issuedEnrollment.value || !issuedProject.value) return ''
  const project = issuedProject.value
  return [
    'reachai:',
    '  registry:',
    '    enabled: true',
    `    url: \${REACHAI_REGISTRY_URL:${registryPublicUrl()}}`,
    `    enrollment-token: ${JSON.stringify(issuedEnrollment.value.enrollmentToken)}`,
    '  project:',
    `    code: ${JSON.stringify(issuedEnrollment.value.projectCode)}`,
    `    name: ${JSON.stringify(project.name)}`,
    `    environment: ${JSON.stringify(project.environment)}`,
    `    owner: ${JSON.stringify(project.owner)}`,
    `    visibility: ${JSON.stringify(project.visibility)}`,
    `    base-url: ${JSON.stringify(project.baseUrl)}`,
    '    # 填写 ReachAI 服务端可访问的业务地址；localhost 仅适用于同机联调。',
    `    context-path: ${JSON.stringify(project.contextPath)}`,
    '  capability:',
    '    scan-beans: true',
    '    scan-mode: ANNOTATED_ONLY',
    '    # ANNOTATED_ONLY 只同步 @ReachCapability 业务方法；要把 Controller 接口同步为 HTTP API，',
    '    # 改为 ALL_CONTROLLERS 并用 scan-packages 限定包名。启动只注册项目与实例，同步在项目内手动触发。',
  ].join('\n')
})

const visibleEnrollmentSnippet = computed(() => {
  if (enrollmentTokenVisible.value || !issuedEnrollment.value) return enrollmentSnippet.value
  return enrollmentSnippet.value.replace(
    `enrollment-token: ${JSON.stringify(issuedEnrollment.value.enrollmentToken)}`,
    'enrollment-token: "******"',
  )
})

function registryPublicUrl() {
  // 业务系统要访问的是用户当前打开的 ReachAI 公网入口，而不是开发机的 Control 端口
  return typeof window === 'undefined' ? 'http://localhost:18603' : window.location.origin
}

function clearEnrollment() {
  issuedEnrollment.value = null
  issuedProject.value = null
  enrollmentTokenVisible.value = false
}

async function copyEnrollmentSnippet() {
  if (!enrollmentSnippet.value) return
  try {
    await navigator.clipboard.writeText(enrollmentSnippet.value)
    ElMessage.success('完整 Starter 配置已复制，请通过受控渠道传递')
  } catch {
    ElMessage.warning('复制失败，请显示接入令牌后手动复制配置')
  }
}

const pagedProjects = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredProjects.value.slice(start, start + pageSize.value)
})

const metrics = computed<MetricStripItem[]>(() => {
  const total = projects.value.length
  const apiCount = projects.value.reduce((sum, project) => sum + apiCountOf(project), 0)
  const sdkCount = projects.value.filter((project) => ['REGISTERED', 'HYBRID'].includes(project.projectKind || '')).length
  const failedCount = projects.value.filter((project) => project.status === 'failed').length
  const scannedCount = projects.value.filter((project) => project.lastScannedAt).length
  return [
    { key: 'registered', label: '注册项目', value: total, iconKey: 'project', tone: 'brand' },
    { key: 'api', label: '已接入 API', value: apiCount, iconKey: 'api', tone: 'brand' },
    { key: 'sdk', label: 'SDK 接入项目', value: sdkCount, iconKey: 'sdk', tone: 'brand' },
    {
      key: 'scanned',
      label: '已扫描 / 异常',
      value: `${scannedCount} / ${failedCount}`,
      hint: failedCount ? `异常 ${failedCount}` : undefined,
      iconKey: 'scan',
      tone: failedCount ? 'danger' : 'brand',
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
  enrollmentError.value = ''
  resetSdkForm()
  applyScanForm(createEmptyScanForm())
  accessDialogTab.value = tab
  accessDialogVisible.value = true
}

async function saveSdkProject() {
  if (saving.value) return
  if (!sdkForm.name.trim() || !sdkForm.projectCode.trim() || !sdkForm.baseUrl.trim()) {
    ElMessage.warning('请填写项目名称、项目编码和 Base URL')
    return
  }
  const project: RegistryProjectRegisterRequest = {
    projectCode: sdkForm.projectCode.trim(),
    name: sdkForm.name.trim(),
    environment: sdkForm.environment?.trim() || 'dev',
    owner: sdkForm.owner?.trim() || '',
    visibility: sdkForm.visibility || 'PRIVATE',
    baseUrl: sdkForm.baseUrl.trim(),
    contextPath: sdkForm.contextPath?.trim() || '',
  }
  enrollmentError.value = ''
  saving.value = true
  try {
    const { data } = await issueRegistryEnrollment(project.projectCode)
    issuedProject.value = project
    issuedEnrollment.value = data
    enrollmentTokenVisible.value = false
    ElMessage.success('已生成 72 小时一次性接入令牌')
    accessDialogVisible.value = false
    enrollmentDialogVisible.value = true
  } catch {
    enrollmentError.value = '接入令牌生成失败，填写的信息已保留，请重试。'
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
  router.push(projectCapabilityWorkspaceLocation(project))
}
</script>

<style scoped lang="scss">
.registry-project-page {
  min-width: 0;
  background: transparent;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.project-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(200px, 1fr) repeat(2, minmax(136px, 170px)) auto;
  align-items: center;
  gap: var(--layout-control-gap);
}

.toolbar .search-input,
.toolbar .el-select {
  width: 100%;
  min-width: 0;
}

.toolbar :deep(.el-input__wrapper),
.toolbar :deep(.el-select__wrapper) {
  min-height: var(--control-height);
  padding-inline: 12px;
  border-radius: var(--radius-pill);
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: var(--layout-control-gap);
}

.toolbar-actions .el-button {
  min-height: var(--control-height);
  margin: 0;
  border-radius: var(--radius-pill);
}

.project-table-shell {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: transparent;
}

.project-table :deep(.el-table__empty-block) {
  display: none;
}

.project-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.project-name-cell strong,
.project-name-cell span {
  display: block;
}

.project-name-cell strong {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 21px;
}

.project-name-cell span {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 18px;
}

.project-name-cell-btn {
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;

  &:hover strong {
    color: var(--brand-on-surface);
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 3px;
  }
}

.project-avatar {
  display: grid;
  place-items: center;
  flex: 0 0 32px;
  height: 32px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  color: var(--brand-on-surface);
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
  font-size: 14px;
  font-weight: 600;
}

.muted,
.form-hint {
  color: var(--text-muted);
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

  i {
    width: 6px;
    height: 6px;
    border-radius: var(--radius-pill);
    background: currentColor;
  }
}

.status-scanned,
.status-created {
  color: var(--status-success);
}

.status-scanning {
  color: var(--brand-on-surface);
}

.status-failed {
  color: var(--status-danger);
}

.owner-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.owner-cell span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.owner-cell :deep(.el-avatar) {
  color: var(--text-muted);
  background: var(--surface-hover);
  font-size: 11px;
}

.empty-state {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  padding: 28px var(--layout-panel-padding) 36px;
}

.empty-main {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 28px;
}

.empty-illustration {
  flex: 0 0 160px;
  width: 160px;
}

.empty-illustration-img {
  display: block;
  width: 100%;
  height: 128px;
  object-fit: contain;
}

.empty-body {
  min-width: 0;
}

.empty-copy h3 {
  margin: 0 0 8px;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 600;
}

.empty-copy p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.empty-actions {
  margin-top: 16px;
}

@media (max-width: 1200px) {
  .toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr)) auto;
  }

  .toolbar .search-input {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .toolbar-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }

  .empty-main {
    flex-direction: column;
    gap: 16px;
    text-align: center;
  }

  .empty-illustration {
    flex-basis: auto;
  }
}

.access-dialog-tabs {
  margin-block-end: 0;
}

.project-access-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 6px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.project-access-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.project-access-form .liquid-glass-select {
  width: 100%;
}

.project-access-form :deep(.el-radio-group) {
  flex-wrap: wrap;
}

.enrollment-config-textarea :deep(.el-textarea__inner) {
  font-family: var(--font-family-mono, monospace);
  font-size: 12px;
  line-height: 1.6;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  color: var(--text-primary);
  background: var(--surface-glass-inset);
  box-shadow: 0 0 0 1px var(--border-glass-edge) inset;
}
</style>

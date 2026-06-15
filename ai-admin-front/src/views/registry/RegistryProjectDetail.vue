<template>
  <div class="registry-detail-page" :class="{ 'is-dark-detail': theme === 'dark' }">
    <section class="project-hero">
      <div class="hero-corner-actions">
        <el-tooltip content="设为当前项目" placement="top">
          <el-button
            class="primary-icon-action"
            circle
            :icon="Star"
            :disabled="!project"
            aria-label="设为当前项目"
            @click="setCurrentProject"
          />
        </el-tooltip>
        <el-tooltip content="刷新" placement="top">
          <el-button
            circle
            :icon="Refresh"
            aria-label="刷新"
            @click="refresh"
          />
        </el-tooltip>
        <el-tooltip content="编辑项目" placement="top">
          <el-button
            circle
            :icon="EditPen"
            :disabled="!project?.id"
            aria-label="编辑项目"
            @click="openEditDialog"
          />
        </el-tooltip>
        <el-tooltip content="删除项目" placement="top">
          <el-button
            class="danger-icon-action"
            circle
            :icon="Delete"
            :disabled="!project?.id"
            :loading="deleteLoading"
            aria-label="删除项目"
            @click="handleDeleteProject"
          />
        </el-tooltip>
      </div>

      <el-button class="back-btn" link :icon="ArrowLeft" @click="router.back()">返回</el-button>

      <div class="hero-main">
        <div class="project-mark">
          <el-icon><Box /></el-icon>
        </div>

        <div class="project-title-block">
          <div class="title-row">
            <h1>{{ project?.name || projectCode }}</h1>
            <el-tag class="code-tag" effect="plain">{{ project?.projectCode || projectCode }}</el-tag>
            <el-tag class="env-tag" effect="plain">{{ project?.environment || 'dev' }}</el-tag>
          </div>
          <div class="project-meta">
            <span>
              <el-icon><Setting /></el-icon>
              状态：
              <i class="online-dot" />
              <b>{{ formatProjectKindLabel(project?.projectKind || 'REGISTERED') }}</b>
            </span>
            <span>
              <el-icon><Lock /></el-icon>
              可见性：<b>{{ formatVisibilityLabel(project?.visibility || 'PRIVATE') }}</b>
            </span>
            <span>
              <el-icon><User /></el-icon>
              负责人：<b>{{ project?.owner || '-' }}</b>
            </span>
          </div>
        </div>
      </div>
    </section>

    <el-card class="detail-card health-card" shadow="never">
      <div class="health-summary">
        <div v-for="item in healthMetrics" :key="item.label" class="health-item" :class="item.tone">
          <div class="health-icon">
            <el-icon><component :is="item.icon" /></el-icon>
          </div>
          <div>
            <div class="health-label">{{ item.label }}</div>
            <strong>{{ item.value }}</strong>
            <small>{{ item.desc }}</small>
          </div>
        </div>
      </div>
    </el-card>

    <section class="workbench-grid">
      <el-card v-for="group in workbenchGroups" :key="group.title" class="detail-card workbench-card" shadow="never">
        <template #header>
          <div class="section-title">
            <span class="title-mark" />
            <span>{{ group.title }}</span>
          </div>
        </template>

        <div class="task-list">
          <button
            v-for="item in group.items"
            :key="item.title"
            class="task-entry"
            type="button"
            :disabled="item.disabled"
            @click="item.action"
          >
            <span class="task-icon" :class="item.tone">
              <el-icon><component :is="item.icon" /></el-icon>
            </span>
            <span class="task-content">
              <span class="task-topline">
                <strong>{{ item.title }}</strong>
              </span>
              <small>{{ item.desc }}</small>
            </span>
            <el-icon class="task-arrow"><ArrowRight /></el-icon>
          </button>
        </div>
      </el-card>
    </section>

    <el-card class="detail-card instance-card" shadow="never">
      <template #header>
        <div class="table-header">
          <div class="section-title">
            <span class="title-mark" />
            <span>实例心跳（{{ instances.length }}）</span>
          </div>
          <div class="header-actions">
            <el-button
              :icon="Delete"
              :disabled="offlineInstanceCount === 0"
              :loading="purgingOffline"
              @click="purgeOfflineInstances"
            >
              清理离线（{{ offlineInstanceCount }}）
            </el-button>
            <el-button :icon="Refresh" @click="loadInstances">刷新实例</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loadingInstances" :data="instances" row-key="id" class="instance-table">
        <el-table-column prop="instanceId" label="实例 ID" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="instance-id">{{ row.instanceId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="150">
          <template #default="{ row }">
            <span class="status-pill" :class="{ offline: row.status !== 'ONLINE', disabled: row.status === 'DISABLED' }">
              <i />
              {{ formatInstanceStatusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="运行时能力" min-width="280">
          <template #default="{ row }">
            <div class="runtime-tags">
              <el-tag size="small" effect="plain">{{ formatRuntimePlacementLabel(runtimePlacement(row)) }}</el-tag>
              <el-tag v-for="rt in runtimeTypes(row)" :key="rt" size="small" type="success" effect="plain">
                {{ formatRuntimeTypeLabel(rt) }}
              </el-tag>
              <el-tag
                v-for="feat in formatRuntimeFeatureLabels(runtimeMeta(row))"
                :key="feat"
                size="small"
                type="info"
                effect="plain"
              >
                {{ feat }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="host" label="主机" min-width="160">
          <template #default="{ row }">{{ row.host || '-' }}</template>
        </el-table-column>
        <el-table-column prop="port" label="端口" width="120">
          <template #default="{ row }">{{ row.port || '-' }}</template>
        </el-table-column>
        <el-table-column prop="appVersion" label="应用版本" width="150">
          <template #default="{ row }">{{ row.appVersion || '-' }}</template>
        </el-table-column>
        <el-table-column prop="sdkVersion" label="SDK 版本" width="150">
          <template #default="{ row }">{{ row.sdkVersion || '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastHeartbeatAt" label="最近心跳" min-width="190">
          <template #default="{ row }">{{ formatHeartbeatDisplay(row.lastHeartbeatAt) }}</template>
        </el-table-column>
        <el-table-column label="治理" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'DISABLED'"
              size="small"
              @click="setInstanceStatus(row, 'OFFLINE')"
            >
              解除禁用
            </el-button>
            <el-button
              v-else
              size="small"
              type="danger"
              plain
              @click="setInstanceStatus(row, 'DISABLED')"
            >
              禁用
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
        <span>共 {{ instances.length }} 条</span>
        <el-select model-value="10" size="small" class="page-size-select">
          <el-option label="10 条/页" value="10" />
        </el-select>
        <el-pagination background layout="prev, pager, next" :total="instances.length || 1" :page-size="10" />
      </div>
    </el-card>

    <el-dialog v-model="editDialogVisible" title="编辑项目" width="720px" destroy-on-close>
      <el-form label-width="120px">
        <el-form-item label="项目名称" required>
          <el-input v-model="editForm.name" placeholder="项目名称" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="项目编码" :required="isEditingSdkProject">
              <el-input v-model="editForm.projectCode" :placeholder="isEditingSdkProject ? '如：customer-service' : '如 order-service'" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="接入方式">
              <el-select v-model="editForm.projectKind" style="width: 100%" :disabled="editAccessLockedToSdk">
                <el-option v-for="opt in projectKindOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="环境">
              <el-input v-model="editForm.environment" placeholder="dev / test / prod" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="负责人">
              <el-input v-model="editForm.owner" placeholder="负责人" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item :label="isEditingSdkProject ? 'Base URL' : '项目域名'" required>
              <el-input v-model="editForm.baseUrl" placeholder="http://localhost:8080" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="可见性">
              <el-select v-model="editForm.visibility" style="width: 100%">
                <el-option v-for="opt in visibilityOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <template v-if="isEditingSdkProject">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="App Key">
                <el-input v-model="editCredentialForm.appKey" placeholder="留空则不更新凭据" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="App Secret">
                <el-input v-model="editCredentialForm.appSecret" show-password placeholder="留空则不更新凭据" />
              </el-form-item>
            </el-col>
          </el-row>
        </template>
        <template v-else>
          <el-form-item label="Context Path">
            <el-input v-model="editForm.contextPath" placeholder="/api" />
          </el-form-item>
          <el-form-item label="扫描路径" :required="editForm.projectKind !== 'REGISTERED'">
            <el-input v-model="editForm.scanPath" placeholder="服务器上的绝对路径或 OpenAPI 所在目录" />
            <div v-if="editForm.projectKind === 'REGISTERED'" class="form-hint">SDK 接入项目可不配置扫描路径。</div>
          </el-form-item>
          <el-form-item label="扫描方式" required>
            <el-select v-model="editForm.scanType" style="width: 100%">
              <el-option label="OpenAPI" value="openapi" />
              <el-option label="Controller" value="controller" />
              <el-option label="自动（SDK）" value="auto" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="editForm.scanType === 'openapi'" label="规范文件">
            <el-input v-model="editForm.specFile" placeholder="可选，相对 scanPath；留空自动发现" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEditProject">保存</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  Box,
  Clock,
  Collection,
  Delete,
  EditPen,
  Grid,
  Link,
  Lock,
  Monitor,
  Operation,
  Refresh,
  Setting,
  Star,
  Tools,
  User,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteScanProject,
  getScanProjectDetail,
  getScanProjectOperationBlockers,
  getScanProjects,
  updateScanProject,
  updateScanProjectRegistryCredential,
} from '@/api/scanProject'
import {
  listRegistryProjectInstances,
  purgeRegistryProjectOfflineInstances,
  updateRegistryProjectInstanceStatus,
} from '@/api/registry'
import {
  listPageActionCatalog,
  listPageRegistry,
  type PageActionRegistryView,
  type PageRegistryView,
} from '@/api/embedOps'
import type { ProjectKind, ProjectVisibility } from '@/types/registry'
import type { ScanProject, ScanProjectUpsertRequest } from '@/types/scanProject'
import type { ProjectInstance } from '@/types/registry'
import { useProjectStore } from '@/store/project'
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'
import {
  formatInstanceStatusLabel,
  formatRuntimeFeatureLabels,
  formatRuntimePlacementLabel,
  formatRuntimeTypeLabel,
} from '@/utils/registryLabels'
import { useTheme } from '@/composables/useTheme'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const { theme } = useTheme()

const projectCode = computed(() => String(route.params.projectCode || ''))
const project = ref<ScanProject | null>(null)
const instances = ref<ProjectInstance[]>([])
const pageRegistry = ref<PageRegistryView[]>([])
const pageActions = ref<PageActionRegistryView[]>([])
const loadingInstances = ref(false)
const loadingPageCatalog = ref(false)
const purgingOffline = ref(false)
const offlineInstanceCount = computed(() =>
  instances.value.filter((item) => item.status === 'OFFLINE' || item.status === 'STALE').length,
)
const editDialogVisible = ref(false)
const editSaving = ref(false)
const deleteLoading = ref(false)
const editAccessLockedToSdk = ref(false)

const projectKindOptions: { value: ProjectKind; label: string }[] = [
  { value: 'SCAN', label: '扫描接入' },
  { value: 'REGISTERED', label: 'SDK 接入' },
  { value: 'HYBRID', label: '混合接入' },
]

const visibilityOptions: { value: ProjectVisibility; label: string }[] = [
  { value: 'PRIVATE', label: '私有' },
  { value: 'PROJECT', label: '项目内' },
  { value: 'SHARED', label: '共享' },
  { value: 'PUBLIC', label: '公开' },
]

const projectKindLabelMap = Object.fromEntries(projectKindOptions.map((item) => [item.value, item.label]))
const visibilityLabelMap = Object.fromEntries(visibilityOptions.map((item) => [item.value, item.label]))

function formatProjectKindLabel(kind: string): string {
  return projectKindLabelMap[kind] ?? kind
}

function formatVisibilityLabel(visibility: string): string {
  return visibilityLabelMap[visibility] ?? visibility
}

/** 年/月/日 时分秒（本地时区） */
function formatHeartbeatDisplay(value?: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function emptyEditForm(): ScanProjectUpsertRequest {
  return {
    name: '',
    projectCode: '',
    projectKind: 'REGISTERED',
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

const editForm = reactive<ScanProjectUpsertRequest>(emptyEditForm())
const editCredentialForm = reactive({
  appKey: '',
  appSecret: '',
})

const isEditingSdkProject = computed(() => editForm.projectKind === 'REGISTERED')

const isSdkBackedProject = computed(() => {
  const kind = project.value?.projectKind || 'REGISTERED'
  return kind === 'REGISTERED' || kind === 'HYBRID'
})

const latestHeartbeat = computed(() => {
  const raw = instances.value[0]?.lastHeartbeatAt || project.value?.lastScannedAt
  return formatHeartbeatDisplay(raw ?? null)
})

const activePageActionCount = computed(() => pageActions.value.filter((item) => item.status === 'ACTIVE').length)
const removedPageActionCount = computed(() => pageActions.value.filter((item) => item.status === 'REMOVED').length)
const lastPageActionSeenAt = computed(() => {
  const raw = pageActions.value[0]?.lastSeenAt || pageRegistry.value[0]?.lastSeenAt
  return formatHeartbeatDisplay(raw ?? null)
})

const healthMetrics = computed(() => [
  {
    label: '实例心跳',
    value: `${instances.value.filter((item) => item.status === 'ONLINE').length} 在线`,
    desc: `最近心跳 ${latestHeartbeat.value}`,
    icon: Monitor,
    tone: instances.value.some((item) => item.status === 'ONLINE') ? 'good' : 'attention',
  },
  {
    label: '能力资产',
    value: `${project.value?.toolCount ?? 0} 能力`,
    desc: (project.value?.toolCount ?? 0) > 0 ? '项目能力资产已形成目录' : '建议检查后端接口管理或能力上报',
    icon: Collection,
    tone: (project.value?.toolCount ?? 0) > 0 ? 'good' : 'attention',
  },
  {
    label: '前端页面管理',
    value: `${pageRegistry.value.length} 页面 / ${pageActions.value.length} 动作`,
    desc: `${activePageActionCount.value} ACTIVE / ${removedPageActionCount.value} REMOVED`,
    icon: Link,
    tone: activePageActionCount.value > 0 ? 'good' : 'neutral',
  },
  {
    label: '最近上报',
    value: lastPageActionSeenAt.value,
    desc: '页面、动作、实例的最近观测时间',
    icon: Clock,
    tone: lastPageActionSeenAt.value !== '-' ? 'good' : 'neutral',
  },
])

const workbenchGroups = computed(() => [
  {
    title: '接入与上报',
    items: [
      ...(isSdkBackedProject.value
        ? [{
            title: 'SDK 接入向导',
            desc: '从后端 Starter、网关路由、业务服务校验、前端 embed token 到最终自检逐步完成接入。',
            icon: Star,
            tone: 'green',
            disabled: !project.value?.id,
            action: goSdkAccessWizard,
          }]
        : []),
      {
        title: '创建页面助手',
        desc: '从后端接口资产 + 前端页面动作生成 Agent Studio 草稿。',
        icon: Star,
        tone: 'violet',
        disabled: !project.value?.id,
        action: goPageAssistantWizard,
      },
    ],
  },
  {
    title: '项目资源',
    items: [
      {
        title: '后端接口管理',
        desc: '管理扫描接口、模块列表和接口图谱，处理 Tool 关联与语义文档。',
        icon: Grid,
        tone: 'blue',
        disabled: !project.value?.id,
        action: goScanProjectDetail,
      },
      {
        title: '前端页面管理',
        desc: '管理业务页面、可执行动作、嵌入授权和会话审计。',
        icon: Collection,
        tone: 'orange',
        disabled: !project.value,
        action: goPageActionGovernance,
      },
      {
        title: '工具管理',
        desc: '查看项目下可被 Agent Runtime 调用的 Tool 清单。',
        icon: Tools,
        tone: 'blue',
        disabled: !project.value?.id,
        action: () => goCapability('/tool'),
      },
    ],
  },
  {
    title: '编排与发布',
    items: [
      {
        title: '能力变更评审',
        desc: '发布前处理能力快照 diff、字段变化、apply / ignore。',
        icon: Lock,
        tone: 'green',
        disabled: !project.value?.id,
        action: goCapabilitySync,
      },
      {
        title: 'Workflow 编排',
        desc: '查看并编辑本项目下的可执行 Workflow，进入 Studio、版本和发布链路。',
        icon: Operation,
        tone: 'blue',
        disabled: !project.value?.projectCode && !projectCode.value,
        action: goWorkflowList,
      },
      {
        title: 'Agent管理',
        desc: '管理版本、发布状态、Trace、页面动作闭环和权限决策。',
        icon: User,
        tone: 'orange',
        disabled: !project.value?.id,
        action: () => goCapability('/agent'),
      },
    ],
  },
])

onMounted(refresh)

async function refresh() {
  const { data } = await getScanProjects()
  const found =
    data.find((item) => item.projectCode === projectCode.value || String(item.id) === projectCode.value) || null
  projectStore.projects = data
  if (found?.id) {
    try {
      const { data: detail } = await getScanProjectDetail(found.id)
      project.value = detail
    } catch {
      project.value = found
    }
  } else {
    project.value = found
  }
  await loadInstances()
  await loadPageCatalog()
}

async function loadInstances() {
  if (!projectCode.value) return
  loadingInstances.value = true
  try {
    const { data } = await listRegistryProjectInstances(projectCode.value)
    instances.value = data
  } finally {
    loadingInstances.value = false
  }
}

async function loadPageCatalog() {
  const code = project.value?.projectCode || projectCode.value
  if (!code) return
  loadingPageCatalog.value = true
  try {
    const [pages, actions] = await Promise.all([
      listPageRegistry({ projectCode: code, limit: 200 }),
      listPageActionCatalog({ projectCode: code, limit: 500 }),
    ])
    pageRegistry.value = pages.data
    pageActions.value = actions.data
  } finally {
    loadingPageCatalog.value = false
  }
}

function runtimeMeta(instance: ProjectInstance): Record<string, any> {
  if (!instance.metadataJson) return {}
  try {
    return JSON.parse(instance.metadataJson)
  } catch {
    return {}
  }
}

function runtimePlacement(instance: ProjectInstance) {
  return runtimeMeta(instance).runtimePlacement || 'EMBEDDED'
}

function runtimeTypes(instance: ProjectInstance): string[] {
  const raw = runtimeMeta(instance).runtimeTypes
  if (Array.isArray(raw)) return raw.filter(Boolean).map(String)
  return raw ? [String(raw)] : ['SPRING_BOOT_EMBEDDED']
}

async function purgeOfflineInstances() {
  if (!projectCode.value || offlineInstanceCount.value === 0) return
  try {
    await ElMessageBox.confirm(
      `将删除 ${offlineInstanceCount.value} 条离线/心跳超时状态的实例心跳记录。是否继续？`,
      '清理离线实例',
      { type: 'warning', confirmButtonText: '清理', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  purgingOffline.value = true
  try {
    const { data } = await purgeRegistryProjectOfflineInstances(projectCode.value, 0)
    ElMessage.success(`已清理 ${data.removed} 条离线实例`)
    await loadInstances()
  } catch (error) {
    ElMessage.error((error as Error).message || '清理失败')
  } finally {
    purgingOffline.value = false
  }
}

async function setInstanceStatus(instance: ProjectInstance, status: ProjectInstance['status']) {
  try {
    await updateRegistryProjectInstanceStatus(projectCode.value, {
      instanceId: instance.instanceId,
      status,
    })
    ElMessage.success(status === 'DISABLED' ? '实例已禁用' : '实例已解除禁用')
    await loadInstances()
  } catch (error) {
    ElMessage.error((error as Error).message || '实例状态更新失败')
  }
}

async function ensureScanOperationAllowed(): Promise<boolean> {
  if (!project.value?.id) return false
  try {
    const { data } = await getScanProjectOperationBlockers(project.value.id)
    if (!data.blocked) return true
    await ElMessageBox.alert(formatScanProjectBlockersMessage(data), '操作被阻止', {
      type: 'warning',
      confirmButtonText: '知道了',
    })
    return false
  } catch {
    ElMessage.error('检查引用关系失败')
    return false
  }
}

function openEditDialog() {
  const p = project.value
  if (!p?.id) return
  const projectKind = p.projectKind || 'REGISTERED'
  editAccessLockedToSdk.value = projectKind === 'REGISTERED'
  editForm.name = p.name
  editForm.projectCode = p.projectCode ?? ''
  editForm.projectKind = projectKind
  editForm.environment = p.environment || 'dev'
  editForm.owner = p.owner ?? ''
  editForm.visibility = p.visibility || 'PRIVATE'
  editForm.baseUrl = p.baseUrl
  editForm.contextPath = p.contextPath || ''
  editForm.scanPath = p.scanPath || ''
  editForm.scanType = projectKind === 'REGISTERED' ? 'auto' : p.scanType || 'openapi'
  editForm.specFile = p.specFile ?? ''
  editCredentialForm.appKey = p.registryAppKey || ''
  editCredentialForm.appSecret = ''
  editDialogVisible.value = true
}

async function saveEditProject() {
  const p = project.value
  if (!p?.id) return
  if (!editForm.name.trim() || !editForm.baseUrl.trim()) {
    ElMessage.warning(isEditingSdkProject.value ? '请填写项目名称与 Base URL' : '请填写项目名称与项目域名')
    return
  }
  if (isEditingSdkProject.value && !editForm.projectCode?.trim()) {
    ElMessage.warning('请填写项目编码')
    return
  }
  if (!isEditingSdkProject.value && editForm.projectKind !== 'REGISTERED' && !editForm.scanPath.trim()) {
    ElMessage.warning('非纯 SDK 项目请填写扫描路径')
    return
  }
  const credentialAppKey = editCredentialForm.appKey.trim()
  const credentialAppSecret = editCredentialForm.appSecret.trim()
  if (isEditingSdkProject.value && (credentialAppKey || credentialAppSecret) && (!credentialAppKey || !credentialAppSecret)) {
    ElMessage.warning('更新接入凭据时请同时填写 App Key 和 App Secret')
    return
  }
  editSaving.value = true
  try {
    const payload: ScanProjectUpsertRequest = {
      ...editForm,
      projectKind: editAccessLockedToSdk.value ? 'REGISTERED' : editForm.projectKind,
      scanType: isEditingSdkProject.value ? 'auto' : editForm.scanType,
      scanPath: isEditingSdkProject.value ? '' : editForm.scanPath,
      specFile: !isEditingSdkProject.value && editForm.scanType === 'openapi' ? editForm.specFile || null : null,
      contextPath: isEditingSdkProject.value ? '' : editForm.contextPath || '',
      owner: editForm.owner || '',
    }
    const { data } = await updateScanProject(p.id, payload)
    if (isEditingSdkProject.value && credentialAppKey && credentialAppSecret) {
      await updateScanProjectRegistryCredential(p.id, {
        appKey: credentialAppKey,
        appSecret: credentialAppSecret,
      })
    }
    ElMessage.success('项目已更新')
    editDialogVisible.value = false
    const routeCode = projectCode.value
    const newCode = (data.projectCode || '').trim()
    if (newCode && newCode !== routeCode) {
      await router.replace(`/registry/projects/${encodeURIComponent(newCode)}`)
    }
    await refresh()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    editSaving.value = false
  }
}

async function handleDeleteProject() {
  const p = project.value
  if (!p?.id) return
  if (!(await ensureScanOperationAllowed())) return
  try {
    await ElMessageBox.confirm(
      `确认删除项目「${p.name}」吗？将删除关联扫描行、挂到本项目的全局 Tool / 粗粒度能力、模块与语义数据等（若仍存在引用则被阻止）。`,
      '删除确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  deleteLoading.value = true
  try {
    await deleteScanProject(p.id)
    ElMessage.success('已删除')
    if (projectStore.currentProjectId === p.id) {
      projectStore.setCurrentProject(null)
    }
    await router.push('/registry/projects')
  } catch (error) {
    const blockers = parseScanProjectBlockersFromError(error)
    if (blockers?.blocked) {
      await ElMessageBox.alert(formatScanProjectBlockersMessage(blockers), '无法删除', {
        type: 'warning',
        confirmButtonText: '知道了',
      })
      return
    }
    ElMessage.error((error as Error).message || '删除失败')
  } finally {
    deleteLoading.value = false
  }
}

function setCurrentProject() {
  if (!project.value) return
  projectStore.setCurrentProject(project.value.id)
  ElMessage.success(`已切换到项目：${project.value.name}`)
}

function goCapability(path: string) {
  if (project.value) {
    projectStore.setCurrentProject(project.value.id)
    router.push({ path, query: { projectId: project.value.id } })
  }
}

function goScanProjectDetail() {
  if (!project.value?.id) return
  projectStore.setCurrentProject(project.value.id)
  router.push({ name: 'ScanProjectDetail', params: { id: String(project.value.id) } })
}

function goCapabilitySync() {
  if (!project.value?.id) return
  projectStore.setCurrentProject(project.value.id)
  router.push({ name: 'CapabilitySyncDebug' })
}

function goWorkflowList() {
  const code = project.value?.projectCode || projectCode.value
  if (!code) return
  if (project.value?.id) {
    projectStore.setCurrentProject(project.value.id)
  }
  router.push({ name: 'WorkflowList', query: { projectCode: code } })
}

function goPageActionGovernance() {
  router.push({
    name: 'EmbedOpsMonitor',
    params: { projectCode: project.value?.projectCode || projectCode.value },
  })
}

function goPageAssistantWizard() {
  router.push({
    name: 'PageAssistantWizard',
    params: { projectCode: project.value?.projectCode || projectCode.value },
  })
}

function goSdkAccessWizard() {
  router.push({
    name: 'SdkAccessWizard',
    params: { projectCode: project.value?.projectCode || projectCode.value },
  })
}
</script>

<style scoped lang="scss">
.registry-detail-page {
  min-height: calc(100vh - 56px);
  padding: 24px 32px 36px;
  background: var(--brand-page-bg);
  background-size: 28px 28px, 28px 28px, auto, auto, auto, auto;
  color: #111827;
}

.project-hero {
  position: relative;
  margin-bottom: 14px;
}

.hero-corner-actions {
  position: absolute;
  top: 14px;
  right: 16px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 6px;

  :deep(.el-button) {
    width: 32px;
    height: 32px;
    margin: 0;
    border-color: rgb(var(--brand-hover-rgb) / 0.28);
    background: rgba(255, 255, 255, 0.64);
    color: #27364f;
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.82),
      0 10px 24px rgb(var(--brand-primary-rgb) / 0.1);
    backdrop-filter: blur(14px);
  }

  .primary-icon-action {
    color: var(--brand-active);
    border-color: rgb(var(--brand-primary-rgb) / 0.34);
    background:
      linear-gradient(135deg, rgba(255, 255, 255, 0.74), rgb(var(--brand-selected-rgb) / 0.64));
  }

  .danger-icon-action {
    color: #d92d20;
    border-color: #fda29b;

    &:hover {
      color: #b42318;
      border-color: #f97066;
      background: #fff5f4;
    }
  }
}

.back-btn {
  margin-bottom: 10px;
  padding: 0;
  color: #344054;
  font-size: 14px;
}

.hero-main {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 16px;
  padding-right: 148px;
}

.project-mark {
  width: 56px;
  height: 56px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: linear-gradient(180deg, #f7f5ff 0%, #eef0ff 100%);
  border: 1px solid #d8d4ff;
  box-shadow: 0 12px 30px rgba(87, 71, 255, 0.12);
  color: var(--brand-active);

  .el-icon {
    font-size: 30px;
  }
}

.project-title-block {
  min-width: 0;
}

.title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;

  h1 {
    margin: 0;
    font-size: 26px;
    line-height: 1.15;
    font-weight: 750;
    letter-spacing: 0;
    color: #101828;
  }
}

.code-tag,
.env-tag {
  height: 32px;
  padding: 0 14px;
  border-radius: 6px;
  font-size: 14px;
}

.code-tag {
  color: #344054;
  border-color: #d7dce5;
  background: #fff;
}

.env-tag {
  color: #067647;
  border-color: #a8e6c1;
  background: #ecfdf3;
}

.project-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 10px;
  color: #475467;
  font-size: 14px;

  span {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    white-space: nowrap;
  }

  b {
    color: #101828;
    font-weight: 500;
  }

  .el-icon {
    color: #667085;
  }
}

.online-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #17b26a;
  box-shadow: 0 0 0 3px rgba(23, 178, 106, 0.12);
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(480px, 0.42fr) minmax(520px, 0.58fr);
  gap: 18px;
  margin-bottom: 18px;
}

.detail-card {
  border-radius: 8px;
  border: 1px solid #e4e7ee;
  box-shadow: 0 10px 30px rgba(16, 24, 40, 0.04);

  :deep(.el-card__header) {
    padding: 17px 24px;
    border-bottom: 1px solid #edf0f5;
  }

  :deep(.el-card__body) {
    padding: 0;
  }
}

.health-card,
.workbench-grid,
.page-action-summary-card {
  margin-bottom: 14px;
}

.health-card :deep(.el-card__body),
.page-action-summary-card :deep(.el-card__body) {
  padding: 10px 16px;
}

.health-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
}

.health-item {
  min-height: 66px;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  padding: 8px 14px;
  border-right: 1px solid rgb(var(--brand-hover-rgb) / 0.16);
  background: transparent;

  &:last-child {
    border-right: 0;
  }

  strong {
    display: block;
    margin-bottom: 2px;
    color: #101828;
    font-size: 18px;
    line-height: 1.2;
    font-weight: 750;
  }

  small {
    color: #667085;
    font-size: 12px;
    line-height: 1.4;
  }
}

.health-icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  background: var(--brand-selected-bg);
  color: var(--brand-active);

  .el-icon {
    font-size: 18px;
  }
}

.health-label {
  margin-bottom: 6px;
  color: #667085;
  font-size: 12px;
}

.workbench-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}

.workbench-card :deep(.el-card__body) {
  padding: 0;
}

.task-list {
  display: grid;
  gap: 10px;
  padding: 14px;
}

.task-entry {
  min-width: 0;
  min-height: 86px;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid #e7ebf3;
  border-radius: 7px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;

  &:hover:not(:disabled) {
    border-color: var(--brand-selected-bg);
    box-shadow: 0 12px 26px rgb(var(--brand-active-rgb) / 0.08);
    transform: translateY(-1px);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.56;
  }
}

.task-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  color: #fff;

  &.blue {
    background: var(--brand-primary-gradient);
  }

  &.green {
    background: linear-gradient(135deg, #16a34a, #15803d);
  }

  &.purple,
  &.violet {
    background: linear-gradient(135deg, var(--brand-hover), var(--brand-active));
  }

  &.orange {
    background: linear-gradient(135deg, #f97316, #ea580c);
  }

  .el-icon {
    font-size: 22px;
  }
}

.task-content {
  min-width: 0;
  display: grid;
  gap: 6px;

  small {
    color: #667085;
    font-size: 12px;
    line-height: 1.45;
  }
}

.task-topline {
  min-width: 0;
  display: flex;
  align-items: center;

  strong {
    min-width: 0;
    color: #101828;
    font-size: 14px;
    font-weight: 750;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.task-arrow {
  color: #98a2b3;
}

.summary-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.summary-metric {
  min-height: 74px;
  padding: 12px 14px;
  border: 1px solid #e7ebf3;
  border-radius: 7px;
  background: #fbfcff;

  strong {
    display: block;
    margin-bottom: 6px;
    color: #101828;
    font-size: 22px;
    line-height: 1.15;
  }

  span {
    color: #667085;
    font-size: 12px;
  }
}

.section-title,
.config-header,
.table-header {
  display: flex;
  align-items: center;
}

.section-title {
  gap: 10px;
  font-size: 17px;
  font-weight: 700;
  color: #101828;
}

.title-mark {
  width: 4px;
  height: 18px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--brand-primary) 0%, var(--brand-hover) 100%);
  box-shadow: 5px 0 0 rgb(var(--brand-active-rgb) / 0.18);
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.overview-item {
  min-height: 88px;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  padding: 16px 24px;
  border-right: 1px solid #edf0f5;
  border-bottom: 1px solid #edf0f5;

  &:nth-child(2n) {
    border-right: none;
  }

  &:nth-last-child(-n + 2) {
    border-bottom: none;
  }
}

.item-icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  background: #f4f6fa;
  color: #344054;
}

.item-label {
  margin-bottom: 6px;
  color: #667085;
  font-size: 13px;
}

.item-value {
  color: #101828;
  font-size: 16px;
  line-height: 1.35;
  word-break: break-all;
}

.link-value {
  color: var(--brand-active);
  text-decoration: none;
}

.config-header,
.table-header {
  justify-content: space-between;
  gap: 16px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-icon {
  color: #98a2b3;
  font-size: 15px;
}

.code-panel {
  height: 318px;
  overflow: auto;
  padding: 12px 0;
  background: #fbfcff;
  font-family: "JetBrains Mono", "Fira Code", Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
}

.config-warning {
  margin: 0 12px 12px;
  font-family: inherit;
}

.code-line {
  display: grid;
  grid-template-columns: 54px minmax(0, 1fr);
  min-height: 22px;

  code {
    padding: 0 18px 0 12px;
    color: #101828;
    background: transparent;
    white-space: pre;
  }
}

.line-no {
  padding-right: 14px;
  color: #667085;
  text-align: right;
  border-right: 1px solid #d8dee8;
  user-select: none;
}

:deep(.yaml-key) {
  color: var(--brand-active);
  font-weight: 700;
}

:deep(.yaml-bool) {
  color: #e11d48;
  font-weight: 700;
}

:deep(.yaml-url) {
  color: #475467;
}

:global(.sdk-config-dialog .el-dialog__body) {
  padding-top: 8px;
}

:global(.sdk-config-layout) {
  display: grid;
  gap: 14px;
}

:global(.sdk-credential-form) {
  padding: 16px 16px 2px;
  border: 1px solid #e7ebf3;
  border-radius: 8px;
  background: #fbfcff;
}

:global(.sdk-code-shell) {
  overflow: hidden;
  border-radius: 8px;
  border: 1px solid #111827;
  background: #05070d;
}

:global(.sdk-code-toolbar) {
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
  background: #0b1020;
  color: #cbd5e1;
  font-size: 13px;
  font-weight: 700;
}

:global(.sdk-code-panel) {
  max-height: 430px;
  min-height: 320px;
  margin: 0;
  overflow: auto;
  padding: 18px 20px;
  background: #05070d;
  color: #e5e7eb;
  font-family: "JetBrains Mono", "Fira Code", Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  tab-size: 2;
}

:global(.sdk-code-panel code) {
  display: block;
  min-width: max-content;
  white-space: pre;
}

.quick-card {
  margin-bottom: 18px;
}

.page-catalog-card {
  margin-bottom: 18px;
}

.quick-card :deep(.el-card__body) {
  padding: 18px 24px;
}

.page-catalog-card :deep(.el-card__body) {
  padding: 0;
}

.page-catalog-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.48fr) minmax(0, 0.52fr);
}

.page-catalog-grid > .el-table:first-child {
  border-right: 1px solid #edf0f5;
}

.catalog-main {
  font-weight: 600;
  color: #101828;
}

.catalog-sub {
  margin-top: 3px;
  color: #667085;
  font-size: 12px;
}

.debug-action-title {
  display: grid;
  gap: 4px;

  small {
    color: #667085;
  }
}

.debug-result-lines {
  display: grid;
  gap: 4px;
  margin-top: 6px;
  font-size: 12px;
}

.debug-event-alert {
  margin-top: 12px;
}

.config-card :deep(.el-segmented) {
  --el-segmented-item-selected-bg-color: #ffffff;
  --el-segmented-item-selected-color: var(--brand-active);
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 18px;
}

.quick-entry {
  min-width: 0;
  height: 82px;
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid #e4e7ee;
  border-radius: 6px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;

  &:hover:not(:disabled) {
    border-color: var(--brand-selected-bg);
    box-shadow: 0 12px 26px rgb(var(--brand-active-rgb) / 0.08);
    transform: translateY(-1px);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
}

.quick-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  color: #fff;

  .el-icon {
    font-size: 24px;
  }
}

.quick-entry.purple .quick-icon {
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-primary));
}

.quick-entry.green .quick-icon {
  background: linear-gradient(135deg, #16a34a, #15803d);
}

.quick-entry.blue .quick-icon {
  background: var(--brand-primary-gradient);
}

.quick-entry.violet .quick-icon {
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-active));
}

.quick-entry.orange .quick-icon {
  background: linear-gradient(135deg, #f97316, #ea580c);
}

.quick-copy {
  min-width: 0;

  strong,
  small {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: #101828;
    font-size: 15px;
    font-weight: 700;
  }

  small {
    margin-top: 5px;
    color: #667085;
    font-size: 12px;
  }
}

.quick-arrow {
  color: #98a2b3;
}

.instance-card :deep(.el-card__body) {
  padding: 0;
}

.instance-table {
  :deep(th.el-table__cell) {
    height: 46px;
    background: #f7f8fb;
    color: #667085;
    font-weight: 700;
  }

  :deep(td.el-table__cell) {
    height: 52px;
    color: #101828;
  }
}

.instance-id {
  color: var(--brand-active);
  font-weight: 500;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 26px;
  padding: 0 12px;
  border-radius: 6px;
  background: #ecfdf3;
  color: #067647;
  border: 1px solid #abefc6;
  font-size: 13px;
  font-weight: 600;

  i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #12b76a;
  }

  &.offline {
    background: #f2f4f7;
    color: #667085;
    border-color: #e4e7ec;

    i {
      background: #98a2b3;
    }
  }

  &.disabled {
    background: #fff1f3;
    color: #c01048;
    border-color: #fecdd6;

    i {
      background: #f43f5e;
    }
  }
}

.runtime-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.table-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 14px;
  padding: 14px 24px;
  border-top: 1px solid #edf0f5;
  color: #475467;
  font-size: 14px;
}

.page-size-select {
  width: 108px;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

@media (max-width: 1360px) {
  .content-grid {
    grid-template-columns: 1fr;
  }

  .hero-main {
    grid-template-columns: auto minmax(0, 1fr);
    padding-right: 140px;
  }

  .project-mark {
    width: 52px;
    height: 52px;
  }

  .hero-corner-actions {
    top: 14px;
    right: 14px;
  }

  .quick-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workbench-grid,
  .summary-metrics {
    grid-template-columns: 1fr;
  }

  .page-catalog-grid {
    grid-template-columns: 1fr;
  }

  .page-catalog-grid > .el-table:first-child {
    border-right: none;
    border-bottom: 1px solid #edf0f5;
  }
}

@media (max-width: 980px) {
  .hero-main {
    grid-template-columns: 1fr;
    padding-right: 0;
    padding-top: 34px;
  }

  .project-mark {
    display: none;
  }

  .health-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .health-item:nth-child(2) {
    border-right: 0;
  }
}

@media (max-width: 640px) {
  .health-summary {
    grid-template-columns: 1fr;
  }

  .health-item {
    border-right: 0;
    border-bottom: 1px solid rgb(var(--brand-hover-rgb) / 0.16);

    &:last-child {
      border-bottom: 0;
    }
  }
}

.registry-detail-page.is-dark-detail {
  background:
    radial-gradient(circle at 28% -8%, rgb(var(--brand-primary-rgb) / 0.18), transparent 34%),
    #0b1020;
  color: #e5e7eb;

  .back-btn {
    color: #cbd5e1;

    &:hover {
      color: #ffffff;
    }
  }

  .project-mark {
    background: linear-gradient(180deg, rgb(var(--brand-primary-rgb) / 0.22), rgba(30, 41, 59, 0.8));
    border-color: rgb(var(--brand-hover-rgb) / 0.45);
    color: var(--brand-selected-bg);
    box-shadow: 0 16px 36px rgba(0, 0, 0, 0.28);
  }

  .title-row h1,
  .project-meta b,
  .section-title,
  .item-value,
  .quick-copy strong {
    color: #f8fafc;
  }

  .code-tag {
    color: var(--brand-selected-bg);
    border-color: rgba(148, 163, 184, 0.35);
    background: rgba(15, 23, 42, 0.82);
  }

  .env-tag {
    color: #86efac;
    border-color: rgba(34, 197, 94, 0.34);
    background: rgba(20, 83, 45, 0.26);
  }

  .project-meta {
    color: #94a3b8;

    .el-icon {
      color: #94a3b8;
    }
  }

  .detail-card {
    background: rgba(15, 23, 42, 0.88);
    border-color: rgba(148, 163, 184, 0.18);
    box-shadow: 0 18px 44px rgba(0, 0, 0, 0.24);

    :deep(.el-card__header) {
      border-bottom-color: rgba(148, 163, 184, 0.14);
      background: rgba(15, 23, 42, 0.48);
    }
  }

  .overview-item {
    border-right-color: rgba(148, 163, 184, 0.14);
    border-bottom-color: rgba(148, 163, 184, 0.14);
  }

  .item-icon {
    color: #cbd5e1;
    background: rgba(148, 163, 184, 0.1);
  }

  .item-label,
  .quick-copy small,
  .table-footer,
  .line-no {
    color: #94a3b8;
  }

  .link-value {
    color: var(--brand-disabled);
  }

  .info-icon,
  .quick-arrow {
    color: #64748b;
  }

  .code-panel {
    background: #0a0f1e;
  }

  .code-line {
    code {
      color: var(--brand-selected-bg);
    }
  }

  .line-no {
    border-right-color: rgba(148, 163, 184, 0.22);
  }

  :deep(.yaml-key) {
    color: var(--brand-disabled);
  }

  :deep(.yaml-bool) {
    color: #fda4af;
  }

  :deep(.yaml-url) {
    color: #cbd5e1;
  }

  .quick-entry {
    background: rgba(15, 23, 42, 0.72);
    border-color: rgba(148, 163, 184, 0.18);

    &:hover:not(:disabled) {
      border-color: rgb(var(--brand-hover-rgb) / 0.62);
      box-shadow: 0 16px 34px rgba(0, 0, 0, 0.24);
    }
  }

  .health-item,
  .task-entry,
  .summary-metric {
    background: rgba(15, 23, 42, 0.72);
    border-color: rgba(148, 163, 184, 0.18);
  }

  .health-item.good {
    background: rgba(20, 83, 45, 0.24);
    border-color: rgba(34, 197, 94, 0.32);
  }

  .health-item.attention {
    background: rgba(120, 53, 15, 0.2);
    border-color: rgba(245, 158, 11, 0.32);
  }

  .health-icon {
    background: rgb(var(--brand-primary-rgb) / 0.2);
    color: var(--brand-selected-bg);
  }

  .health-item strong,
  .task-topline strong,
  .summary-metric strong {
    color: #f8fafc;
  }

  .health-label,
  .health-item small,
  .task-content small,
  .summary-metric span {
    color: #94a3b8;
  }

  .instance-table {
    :deep(.el-table),
    :deep(.el-table__inner-wrapper),
    :deep(.el-table__body-wrapper),
    :deep(.el-table__header-wrapper) {
      background: transparent;
    }

    :deep(th.el-table__cell) {
      background: rgba(30, 41, 59, 0.76);
      color: #cbd5e1;
      border-bottom-color: rgba(148, 163, 184, 0.16);
    }

    :deep(td.el-table__cell) {
      background: rgba(15, 23, 42, 0.58);
      color: #e2e8f0;
      border-bottom-color: rgba(148, 163, 184, 0.12);
    }

    :deep(.el-table__row:hover > td.el-table__cell) {
      background: rgba(30, 41, 59, 0.92);
    }
  }

  .instance-id {
    color: var(--brand-disabled);
  }

  .status-pill {
    background: rgba(20, 83, 45, 0.3);
    color: #86efac;
    border-color: rgba(34, 197, 94, 0.38);

    &.offline {
      background: rgba(51, 65, 85, 0.52);
      color: #cbd5e1;
      border-color: rgba(148, 163, 184, 0.22);
    }

    &.disabled {
      background: rgba(136, 19, 55, 0.32);
      color: #fda4af;
      border-color: rgba(244, 63, 94, 0.4);
    }
  }

  .table-footer {
    border-top-color: rgba(148, 163, 184, 0.14);
  }
}

/* Purple glass alignment for the registry project detail page. */
:global(.main-layout.registry-shell:has(.registry-detail-page) .topbar) {
  border-bottom-color: rgba(255, 255, 255, 0.48) !important;
  background:
    var(--brand-topbar-bg) !important;
  color: #f8fbff !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.5), 0 16px 34px rgb(var(--brand-primary-rgb) / 0.18) !important;
  backdrop-filter: blur(24px) saturate(1.18) !important;
}

:global(.main-layout.registry-shell:has(.registry-detail-page) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.registry-detail-page) .breadcrumb-area .el-breadcrumb__separator) {
  color: rgba(255, 255, 255, 0.84) !important;
}

:global(.main-layout.registry-shell:has(.registry-detail-page) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #ffffff !important;
}

:global(.main-layout.registry-shell:has(.registry-detail-page) .topbar-btn) {
  border-color: rgba(255, 255, 255, 0.7) !important;
  background: rgba(255, 255, 255, 0.48) !important;
  color: var(--brand-active) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 8px 20px rgb(var(--brand-primary-rgb) / 0.14) !important;
}

.registry-detail-page {
  background:
    linear-gradient(rgba(255, 255, 255, 0.2) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.18) 1px, transparent 1px),
    radial-gradient(circle at 14% 4%, rgb(var(--brand-selected-rgb) / 0.26), transparent 32%),
    radial-gradient(circle at 80% 0%, rgb(var(--brand-hover-rgb) / 0.16), transparent 34%),
    #edf8f2 !important;
  background-size: 28px 28px, 28px 28px, auto, auto, auto;
}

.project-hero,
.detail-card {
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.5) !important;
  border-radius: 8px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.7), rgb(var(--brand-selected-rgb) / 0.28)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.72),
    0 14px 34px rgb(var(--brand-primary-rgb) / 0.09) !important;
  backdrop-filter: blur(18px) saturate(1.02);
}

.project-hero {
  padding: 14px 22px 16px;
}

.project-mark {
  border-color: rgb(var(--brand-hover-rgb) / 0.28) !important;
  background:
    linear-gradient(145deg, rgba(238, 242, 255, 0.96), rgb(var(--brand-selected-rgb) / 0.72)) !important;
  color: var(--brand-active) !important;
  box-shadow: 0 14px 32px rgb(var(--brand-primary-rgb) / 0.18) !important;
}

.title-row h1 {
  color: #11183a !important;
}

.project-meta,
.project-meta .el-icon,
.back-btn,
.health-label,
.health-item small,
.task-content small,
.table-footer {
  color: #4f5f81 !important;
}

.project-meta b,
.health-item strong,
.task-topline strong,
.section-title {
  color: #11183a !important;
}

.code-tag {
  border-color: rgb(var(--brand-hover-rgb) / 0.22) !important;
  background: rgb(var(--brand-selected-rgb) / 0.68) !important;
  color: var(--brand-active) !important;
}

.env-tag {
  border-color: rgb(var(--brand-primary-rgb) / 0.22) !important;
  background: rgb(var(--brand-selected-rgb) / 0.7) !important;
  color: var(--brand-active) !important;
}

.detail-card {
  :deep(.el-card__header) {
    border-bottom-color: rgb(var(--brand-selected-rgb) / 0.36) !important;
    background: rgba(255, 255, 255, 0.26) !important;
  }
}

.health-item,
.task-entry,
.summary-metric {
  border-color: rgb(var(--brand-hover-rgb) / 0.24) !important;
  background: rgba(255, 255, 255, 0.52) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(14px);
}

.health-card {
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.66), rgb(var(--brand-selected-rgb) / 0.22)) !important;
}

.health-item {
  border-color: rgb(var(--brand-hover-rgb) / 0.16) !important;
  background: transparent !important;
  box-shadow: none;
  backdrop-filter: none;
}

.health-item.good {
  border-color: rgba(34, 197, 94, 0.18) !important;
}

.health-item.attention {
  border-color: rgba(245, 158, 11, 0.2) !important;
}

.health-icon,
.task-icon {
  border: 1px solid rgb(var(--brand-hover-rgb) / 0.24);
  background: rgb(var(--brand-selected-rgb) / 0.72) !important;
  color: var(--brand-primary) !important;
  box-shadow: 0 10px 24px rgb(var(--brand-primary-rgb) / 0.14);
}

.task-icon.blue,
.task-icon.green,
.task-icon.purple,
.task-icon.violet,
.task-icon.orange {
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-hover)) !important;
  color: #fff !important;
}

.task-entry:hover:not(:disabled) {
  border-color: rgb(var(--brand-hover-rgb) / 0.44) !important;
  box-shadow: 0 16px 34px rgb(var(--brand-primary-rgb) / 0.16) !important;
}

.title-mark {
  background: linear-gradient(180deg, var(--brand-primary) 0%, var(--brand-hover) 100%) !important;
  box-shadow: 0 0 18px rgb(var(--brand-hover-rgb) / 0.32);
}

.instance-table {
  :deep(th.el-table__cell) {
    background: rgb(var(--brand-selected-rgb) / 0.7) !important;
    color: var(--brand-active) !important;
    border-bottom-color: rgb(var(--brand-selected-rgb) / 0.36) !important;
  }

  :deep(td.el-table__cell) {
    background: rgba(255, 255, 255, 0.4) !important;
    border-bottom-color: rgb(var(--brand-selected-rgb) / 0.24) !important;
  }

  :deep(.el-table__row:hover > td.el-table__cell) {
    background: rgb(var(--brand-selected-rgb) / 0.56) !important;
  }
}

.instance-id,
.link-value {
  color: var(--brand-active) !important;
}

.registry-detail-page.is-dark-detail {
  background:
    radial-gradient(circle at 28% -8%, rgb(var(--brand-hover-rgb) / 0.22), transparent 34%),
    radial-gradient(circle at 80% 8%, rgb(var(--brand-primary-rgb) / 0.16), transparent 30%),
    #0b1020 !important;

  .project-hero,
  .detail-card {
    border-color: rgb(var(--brand-hover-rgb) / 0.2) !important;
    background: linear-gradient(145deg, rgba(15, 23, 42, 0.78), rgb(var(--brand-primary-rgb) / 0.24)) !important;
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.08),
      0 24px 54px rgba(0, 0, 0, 0.3) !important;
  }

  .title-row h1,
  .project-meta b,
  .health-item strong,
  .task-topline strong,
  .section-title {
    color: #f8fafc !important;
  }

  .project-meta,
  .project-meta .el-icon,
  .back-btn,
  .health-label,
  .health-item small,
  .task-content small,
  .table-footer {
    color: #aeb8d4 !important;
  }

  .health-item,
  .task-entry,
  .summary-metric {
    border-color: rgb(var(--brand-hover-rgb) / 0.2) !important;
    background: rgba(15, 23, 42, 0.62) !important;
  }

  .instance-table {
    :deep(th.el-table__cell) {
      background: rgb(var(--brand-primary-rgb) / 0.36) !important;
      color: var(--brand-selected-bg) !important;
    }

    :deep(td.el-table__cell) {
      background: rgba(15, 23, 42, 0.54) !important;
    }
  }
}

</style>

<style lang="scss">
.sdk-config-dialog .el-dialog__body {
  padding-top: 8px;
}

.sdk-config-dialog .sdk-config-layout {
  display: grid;
  gap: 14px;
}

.sdk-config-dialog .sdk-credential-form {
  padding: 16px 16px 2px;
  border: 1px solid #e7ebf3;
  border-radius: 8px;
  background: #fbfcff;
}

.sdk-config-dialog .sdk-code-shell {
  overflow: hidden;
  border-radius: 8px;
  border: 1px solid #111827;
  background: #05070d;
}

.sdk-config-dialog .sdk-code-toolbar {
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
  background: #0b1020;
  color: #cbd5e1;
  font-size: 13px;
  font-weight: 700;
}

.sdk-config-dialog .sdk-code-panel {
  max-height: 430px;
  min-height: 320px;
  margin: 0;
  overflow: auto;
  padding: 18px 20px;
  background: #05070d;
  color: #e5e7eb;
  font-family: "JetBrains Mono", "Fira Code", Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  tab-size: 2;
}

.sdk-config-dialog .sdk-code-panel code {
  display: block;
  min-width: max-content;
  white-space: pre;
}

[data-theme="dark"] .registry-detail-page {
  background:
    radial-gradient(circle at 28% -8%, rgb(var(--brand-primary-rgb) / 0.18), transparent 34%),
    #0b1020;
  color: #e5e7eb;

  .back-btn {
    color: #cbd5e1;

    &:hover {
      color: #ffffff;
    }
  }

  .project-mark {
    background: linear-gradient(180deg, rgb(var(--brand-primary-rgb) / 0.22), rgba(30, 41, 59, 0.8));
    border-color: rgb(var(--brand-hover-rgb) / 0.45);
    color: var(--brand-selected-bg);
    box-shadow: 0 16px 36px rgba(0, 0, 0, 0.28);
  }

  .title-row h1,
  .project-meta b,
  .section-title,
  .item-value,
  .quick-copy strong {
    color: #f8fafc;
  }

  .code-tag {
    color: var(--brand-selected-bg);
    border-color: rgba(148, 163, 184, 0.35);
    background: rgba(15, 23, 42, 0.82);
  }

  .env-tag {
    color: #86efac;
    border-color: rgba(34, 197, 94, 0.34);
    background: rgba(20, 83, 45, 0.26);
  }

  .project-meta {
    color: #94a3b8;

    .el-icon {
      color: #94a3b8;
    }
  }

  .detail-card {
    background: rgba(15, 23, 42, 0.88);
    border-color: rgba(148, 163, 184, 0.18);
    box-shadow: 0 18px 44px rgba(0, 0, 0, 0.24);

    .el-card__header {
      border-bottom-color: rgba(148, 163, 184, 0.14);
      background: rgba(15, 23, 42, 0.48);
    }
  }

  .overview-item {
    border-right-color: rgba(148, 163, 184, 0.14);
    border-bottom-color: rgba(148, 163, 184, 0.14);
  }

  .item-icon {
    color: #cbd5e1;
    background: rgba(148, 163, 184, 0.1);
  }

  .item-label,
  .quick-copy small,
  .table-footer,
  .line-no {
    color: #94a3b8;
  }

  .link-value {
    color: var(--brand-disabled);
  }

  .info-icon,
  .quick-arrow {
    color: #64748b;
  }

  .code-panel {
    background: #0a0f1e;
  }

  .code-line {
    code {
      color: var(--brand-selected-bg);
    }
  }

  .line-no {
    border-right-color: rgba(148, 163, 184, 0.22);
  }

  .yaml-key {
    color: var(--brand-disabled);
  }

  .yaml-bool {
    color: #fda4af;
  }

  .yaml-url {
    color: #cbd5e1;
  }

  .quick-entry {
    background: rgba(15, 23, 42, 0.72);
    border-color: rgba(148, 163, 184, 0.18);

    &:hover:not(:disabled) {
      border-color: rgb(var(--brand-hover-rgb) / 0.62);
      box-shadow: 0 16px 34px rgba(0, 0, 0, 0.24);
    }
  }

  .health-item,
  .task-entry,
  .summary-metric {
    background: rgba(15, 23, 42, 0.72);
    border-color: rgba(148, 163, 184, 0.18);
  }

  .health-item.good {
    background: rgba(20, 83, 45, 0.24);
    border-color: rgba(34, 197, 94, 0.32);
  }

  .health-item.attention {
    background: rgba(120, 53, 15, 0.2);
    border-color: rgba(245, 158, 11, 0.32);
  }

  .health-icon {
    background: rgb(var(--brand-primary-rgb) / 0.2);
    color: var(--brand-selected-bg);
  }

  .health-item strong,
  .task-topline strong,
  .summary-metric strong {
    color: #f8fafc;
  }

  .health-label,
  .health-item small,
  .task-content small,
  .summary-metric span {
    color: #94a3b8;
  }

  .instance-table {
    .el-table,
    .el-table__inner-wrapper,
    .el-table__body-wrapper,
    .el-table__header-wrapper {
      background: transparent;
    }

    th.el-table__cell {
      background: rgba(30, 41, 59, 0.76);
      color: #cbd5e1;
      border-bottom-color: rgba(148, 163, 184, 0.16);
    }

    td.el-table__cell {
      background: rgba(15, 23, 42, 0.58);
      color: #e2e8f0;
      border-bottom-color: rgba(148, 163, 184, 0.12);
    }

    .el-table__row:hover > td.el-table__cell {
      background: rgba(30, 41, 59, 0.92);
    }
  }

  .instance-id {
    color: var(--brand-disabled);
  }

  .status-pill {
    background: rgba(20, 83, 45, 0.3);
    color: #86efac;
    border-color: rgba(34, 197, 94, 0.38);

    &.offline {
      background: rgba(51, 65, 85, 0.52);
      color: #cbd5e1;
      border-color: rgba(148, 163, 184, 0.22);
    }
  }

  .table-footer {
    border-top-color: rgba(148, 163, 184, 0.14);
  }
}
</style>

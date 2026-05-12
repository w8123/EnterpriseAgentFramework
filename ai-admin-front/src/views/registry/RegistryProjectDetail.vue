<template>
  <div class="registry-detail-page" :class="{ 'is-dark-detail': theme === 'dark' }">
    <section class="project-hero">
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
            <span>
              <el-icon><Grid /></el-icon>
              能力数：<b>{{ project?.toolCount ?? 0 }}</b>
            </span>
            <span>
              <el-icon><Clock /></el-icon>
              最近心跳：<b>{{ latestHeartbeat }}</b>
            </span>
          </div>
        </div>

        <div class="hero-actions">
          <el-button type="primary" :icon="Star" :disabled="!project" @click="setCurrentProject">
            设为当前项目
          </el-button>
          <el-button :icon="Refresh" @click="refresh">刷新</el-button>
          <el-button :icon="EditPen" :disabled="!project?.id" @click="openEditDialog">编辑项目</el-button>
          <el-button
            class="danger-action"
            :icon="Delete"
            :disabled="!project?.id"
            :loading="deleteLoading"
            @click="handleDeleteProject"
          >
            删除项目
          </el-button>
        </div>
      </div>
    </section>

    <section class="content-grid">
      <el-card class="detail-card overview-card" shadow="never">
        <template #header>
          <div class="section-title">
            <span class="title-mark" />
            <span>项目概览</span>
          </div>
        </template>

        <div class="overview-grid">
          <div v-for="item in overviewItems" :key="item.label" class="overview-item">
            <div class="item-icon">
              <el-icon><component :is="item.icon" /></el-icon>
            </div>
            <div>
              <div class="item-label">{{ item.label }}</div>
              <a v-if="item.isLink && item.value !== '-'" class="item-value link-value" :href="item.value" target="_blank">
                {{ item.value }}
              </a>
              <div v-else class="item-value">{{ item.value }}</div>
            </div>
          </div>
        </div>
      </el-card>

      <el-card class="detail-card config-card" shadow="never">
        <template #header>
          <div class="config-header">
            <div class="section-title">
              <span class="title-mark" />
              <span>SDK / 业务系统配置示例</span>
              <el-tooltip content="业务系统接入注册中心时使用的 YAML 示例" placement="top">
                <el-icon class="info-icon"><InfoFilled /></el-icon>
              </el-tooltip>
            </div>
            <el-button :icon="DocumentCopy" @click="copyConfigSnippet">复制</el-button>
          </div>
        </template>

        <div class="code-panel">
          <div v-for="(line, index) in configLines" :key="`${index}-${line}`" class="code-line">
            <span class="line-no">{{ index + 1 }}</span>
            <code v-html="highlightYaml(line)" />
          </div>
        </div>
      </el-card>
    </section>

    <el-card class="detail-card quick-card" shadow="never">
      <template #header>
        <div class="section-title">
          <span class="title-mark" />
          <span>快捷入口</span>
        </div>
      </template>

      <div class="quick-grid">
        <button
          v-for="entry in quickEntries"
          :key="entry.title"
          class="quick-entry"
          type="button"
          :class="entry.tone"
          :disabled="entry.disabled"
          @click="entry.action"
        >
          <span class="quick-icon">
            <el-icon><component :is="entry.icon" /></el-icon>
          </span>
          <span class="quick-copy">
            <strong>{{ entry.title }}</strong>
            <small>{{ entry.desc }}</small>
          </span>
          <el-icon class="quick-arrow"><ArrowRight /></el-icon>
        </button>
      </div>
    </el-card>

    <el-card class="detail-card instance-card" shadow="never">
      <template #header>
        <div class="table-header">
          <div class="section-title">
            <span class="title-mark" />
            <span>实例心跳（{{ instances.length }}）</span>
          </div>
          <el-button :icon="Refresh" @click="loadInstances">刷新实例</el-button>
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
            <span class="status-pill" :class="{ offline: row.status !== 'ONLINE' }">
              <i />
              {{ row.status }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="host" label="Host" min-width="160">
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
            <el-form-item label="项目编码">
              <el-input v-model="editForm.projectCode" placeholder="如 order-service" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="接入方式">
              <el-select v-model="editForm.projectKind" style="width: 100%">
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
            <el-form-item label="项目域名" required>
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
        <el-form-item label="Context Path">
          <el-input v-model="editForm.contextPath" placeholder="/api" />
        </el-form-item>
        <el-form-item label="扫描路径" :required="editForm.projectKind !== 'REGISTERED'">
          <el-input v-model="editForm.scanPath" placeholder="服务器上的绝对路径或 OpenAPI 所在目录" />
          <div v-if="editForm.projectKind === 'REGISTERED'" class="form-hint">SDK 注册项目可不配置扫描路径。</div>
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
  DocumentCopy,
  EditPen,
  Grid,
  InfoFilled,
  Link,
  Lock,
  Monitor,
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
} from '@/api/scanProject'
import { listRegistryProjectInstances } from '@/api/registry'
import type { ProjectKind, ProjectVisibility } from '@/types/registry'
import type { ScanProject, ScanProjectUpsertRequest } from '@/types/scanProject'
import type { ProjectInstance } from '@/types/registry'
import { useProjectStore } from '@/store/project'
import {
  formatScanProjectBlockersMessage,
  parseScanProjectBlockersFromError,
} from '@/utils/scanProjectBlockers'
import { useTheme } from '@/composables/useTheme'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const { theme } = useTheme()

const projectCode = computed(() => String(route.params.projectCode || ''))
const project = ref<ScanProject | null>(null)
const instances = ref<ProjectInstance[]>([])
const loadingInstances = ref(false)
const editDialogVisible = ref(false)
const editSaving = ref(false)
const deleteLoading = ref(false)

const projectKindOptions: { value: ProjectKind; label: string }[] = [
  { value: 'SCAN', label: '扫描接入' },
  { value: 'REGISTERED', label: 'SDK 注册' },
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

/** 与 ai-agent-service 实际监听地址一致，由 VITE_AI_AGENT_SERVICE_URL 注入，默认本地 18603 */
const agentServiceBaseUrl = computed(() => {
  const raw = import.meta.env.VITE_AI_AGENT_SERVICE_URL?.trim()
  const fallback = 'http://localhost:18603'
  if (!raw) return fallback
  return raw.replace(/\/$/, '')
})

const exampleBusinessBaseUrl = computed(() => {
  const raw = import.meta.env.VITE_EXAMPLE_BUSINESS_BASE_URL?.trim()
  if (raw) return raw.replace(/\/$/, '')
  return 'http://127.0.0.1:8611'
})

const configSnippet = computed(() => `eaf:
  registry:
    enabled: true
    url: ${agentServiceBaseUrl.value}
  project:
    code: ${project.value?.projectCode || projectCode.value}
    name: ${project.value?.name || ''}
    base-url: ${displayBaseUrl.value}
    context-path: ${project.value?.contextPath || ''}
    environment: ${project.value?.environment || 'dev'}
    owner: ${project.value?.owner || 'your-team'}
    visibility: ${project.value?.visibility || 'PRIVATE'}
  capability:
    scan-controller: true
    sync-on-startup: true`)

const configLines = computed(() => configSnippet.value.split('\n'))

const latestHeartbeat = computed(() => {
  const raw = instances.value[0]?.lastHeartbeatAt || project.value?.lastScannedAt
  return formatHeartbeatDisplay(raw ?? null)
})

const displayBaseUrl = computed(() => normalizeHttpUrl(project.value?.baseUrl || exampleBusinessBaseUrl.value))

const overviewItems = computed(() => [
  { label: '项目编码', value: project.value?.projectCode || projectCode.value || '-', icon: Link },
  { label: '项目形态', value: formatProjectKindLabel(project.value?.projectKind || 'REGISTERED'), icon: Setting },
  { label: '环境', value: project.value?.environment || '-', icon: Monitor },
  { label: '可见性', value: formatVisibilityLabel(project.value?.visibility || 'PRIVATE'), icon: Lock },
  { label: '负责人', value: project.value?.owner || '-', icon: User },
  { label: '能力数', value: String(project.value?.toolCount ?? 0), icon: Grid },
  { label: '根地址', value: project.value?.baseUrl ? displayBaseUrl.value : '-', icon: Collection, isLink: true },
  { label: 'Context Path', value: project.value?.contextPath || '-', icon: Box },
])

const quickEntries = computed(() => [
  {
    title: 'API 目录',
    desc: '管理 API 与 Tool 的关联关系',
    icon: Link,
    tone: 'purple',
    disabled: !project.value?.id,
    action: goApiCatalog,
  },
  {
    title: '能力变更评审',
    desc: '发起与查看能力变更评审',
    icon: Lock,
    tone: 'green',
    disabled: !project.value?.id,
    action: goCapabilitySync,
  },
  {
    title: '查看 Tool',
    desc: '管理项目下的 Tool 列表',
    icon: Tools,
    tone: 'blue',
    disabled: !project.value?.id,
    action: () => goCapability('/tool'),
  },
  {
      title: '查看能力',
      desc: '管理项目下的粗粒度能力列表',
    icon: Collection,
    tone: 'violet',
    disabled: !project.value?.id,
    action: () => goCapability('/capability'),
  },
  {
    title: '查看 Agent',
    desc: '管理项目下的 Agent 列表',
    icon: User,
    tone: 'orange',
    disabled: !project.value?.id,
    action: () => goCapability('/agent'),
  },
])

function highlightYaml(line: string) {
  const escaped = line
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped
    .replace(/^(\s*)([A-Za-z0-9_-]+:)/, '$1<span class="yaml-key">$2</span>')
    .replace(/\b(true|false)\b/g, '<span class="yaml-bool">$1</span>')
    .replace(/(http:\/\/[^\s]+)/g, '<span class="yaml-url">$1</span>')
}

function normalizeHttpUrl(value: string) {
  return value.replace(/^http:(?!\/\/)/, 'http://').replace(/^https:(?!\/\/)/, 'https://')
}

async function copyConfigSnippet() {
  try {
    await navigator.clipboard.writeText(configSnippet.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

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
  editForm.name = p.name
  editForm.projectCode = p.projectCode ?? ''
  editForm.projectKind = p.projectKind || 'REGISTERED'
  editForm.environment = p.environment || 'dev'
  editForm.owner = p.owner ?? ''
  editForm.visibility = p.visibility || 'PRIVATE'
  editForm.baseUrl = p.baseUrl
  editForm.contextPath = p.contextPath || ''
  editForm.scanPath = p.scanPath || ''
  editForm.scanType = p.scanType || 'openapi'
  editForm.specFile = p.specFile ?? ''
  editDialogVisible.value = true
}

async function saveEditProject() {
  const p = project.value
  if (!p?.id) return
  if (!editForm.name.trim() || !editForm.baseUrl.trim()) {
    ElMessage.warning('请填写项目名称与项目域名')
    return
  }
  if (editForm.projectKind !== 'REGISTERED' && !editForm.scanPath.trim()) {
    ElMessage.warning('非纯 SDK 项目请填写扫描路径')
    return
  }
  editSaving.value = true
  try {
    const payload: ScanProjectUpsertRequest = {
      ...editForm,
      specFile: editForm.scanType === 'openapi' ? editForm.specFile || null : null,
      contextPath: editForm.contextPath || '',
      owner: editForm.owner || '',
    }
    const { data } = await updateScanProject(p.id, payload)
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

function goApiCatalog() {
  if (!project.value?.id) return
  projectStore.setCurrentProject(project.value.id)
  router.push({ name: 'ScanProjectDetail', params: { id: String(project.value.id) } })
}

function goCapabilitySync() {
  if (!project.value?.id) return
  projectStore.setCurrentProject(project.value.id)
  router.push({ name: 'CapabilitySyncDebug' })
}
</script>

<style scoped lang="scss">
.registry-detail-page {
  min-height: calc(100vh - 56px);
  padding: 24px 32px 36px;
  background:
    radial-gradient(circle at 28% -8%, rgba(91, 61, 245, 0.08), transparent 32%),
    #f7f8fc;
  color: #111827;
}

.project-hero {
  margin-bottom: 22px;
}

.back-btn {
  margin-bottom: 18px;
  padding: 0;
  color: #344054;
  font-size: 14px;
}

.hero-main {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 20px;
}

.project-mark {
  width: 78px;
  height: 78px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: linear-gradient(180deg, #f7f5ff 0%, #eef0ff 100%);
  border: 1px solid #d8d4ff;
  box-shadow: 0 12px 30px rgba(87, 71, 255, 0.12);
  color: #4f46e5;

  .el-icon {
    font-size: 38px;
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
    font-size: 32px;
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
  gap: 18px;
  margin-top: 14px;
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

.hero-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: flex-end;

  .el-button {
    height: 40px;
    border-radius: 6px;
    font-weight: 600;
  }

  .danger-action {
    color: #d92d20;
    border-color: #fda29b;
    background: #fff;

    &:hover {
      color: #b42318;
      border-color: #f97066;
      background: #fff5f4;
    }
  }
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
  background: linear-gradient(180deg, #3b82f6 0%, #6d28d9 100%);
  box-shadow: 5px 0 0 rgba(79, 70, 229, 0.18);
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
  color: #4f46e5;
  text-decoration: none;
}

.config-header,
.table-header {
  justify-content: space-between;
  gap: 16px;
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
  color: #1d4ed8;
  font-weight: 700;
}

:deep(.yaml-bool) {
  color: #e11d48;
  font-weight: 700;
}

:deep(.yaml-url) {
  color: #475467;
}

.quick-card {
  margin-bottom: 18px;
}

.quick-card :deep(.el-card__body) {
  padding: 18px 24px;
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
    border-color: #c7d2fe;
    box-shadow: 0 12px 26px rgba(79, 70, 229, 0.08);
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
  background: linear-gradient(135deg, #7c3aed, #6366f1);
}

.quick-entry.green .quick-icon {
  background: linear-gradient(135deg, #16a34a, #15803d);
}

.quick-entry.blue .quick-icon {
  background: linear-gradient(135deg, #2563eb, #1d4ed8);
}

.quick-entry.violet .quick-icon {
  background: linear-gradient(135deg, #7c3aed, #4f46e5);
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
  color: #4f46e5;
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
  .hero-main,
  .content-grid {
    grid-template-columns: 1fr;
  }

  .project-mark {
    display: none;
  }

  .hero-actions {
    justify-content: flex-start;
  }

  .quick-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.registry-detail-page.is-dark-detail {
  background:
    radial-gradient(circle at 28% -8%, rgba(99, 102, 241, 0.18), transparent 34%),
    #0b1020;
  color: #e5e7eb;

  .back-btn {
    color: #cbd5e1;

    &:hover {
      color: #ffffff;
    }
  }

  .project-mark {
    background: linear-gradient(180deg, rgba(99, 102, 241, 0.22), rgba(30, 41, 59, 0.8));
    border-color: rgba(129, 140, 248, 0.45);
    color: #c4b5fd;
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
    color: #dbeafe;
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

  .hero-actions {
    .el-button:not(.el-button--primary):not(.danger-action) {
      background: rgba(15, 23, 42, 0.86);
      border-color: rgba(148, 163, 184, 0.25);
      color: #cbd5e1;

      &:hover {
        background: rgba(30, 41, 59, 0.96);
        border-color: rgba(129, 140, 248, 0.55);
        color: #ffffff;
      }
    }

    .danger-action {
      color: #fca5a5;
      border-color: rgba(248, 113, 113, 0.44);
      background: rgba(127, 29, 29, 0.18);

      &:hover {
        color: #fecaca;
        border-color: rgba(248, 113, 113, 0.72);
        background: rgba(127, 29, 29, 0.32);
      }
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
    color: #a5b4fc;
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
      color: #dbeafe;
    }
  }

  .line-no {
    border-right-color: rgba(148, 163, 184, 0.22);
  }

  :deep(.yaml-key) {
    color: #93c5fd;
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
      border-color: rgba(129, 140, 248, 0.62);
      box-shadow: 0 16px 34px rgba(0, 0, 0, 0.24);
    }
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
    color: #a5b4fc;
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

<style lang="scss">
[data-theme="dark"] .registry-detail-page {
  background:
    radial-gradient(circle at 28% -8%, rgba(99, 102, 241, 0.18), transparent 34%),
    #0b1020;
  color: #e5e7eb;

  .back-btn {
    color: #cbd5e1;

    &:hover {
      color: #ffffff;
    }
  }

  .project-mark {
    background: linear-gradient(180deg, rgba(99, 102, 241, 0.22), rgba(30, 41, 59, 0.8));
    border-color: rgba(129, 140, 248, 0.45);
    color: #c4b5fd;
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
    color: #dbeafe;
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

  .hero-actions {
    .el-button:not(.el-button--primary):not(.danger-action) {
      background: rgba(15, 23, 42, 0.86);
      border-color: rgba(148, 163, 184, 0.25);
      color: #cbd5e1;

      &:hover {
        background: rgba(30, 41, 59, 0.96);
        border-color: rgba(129, 140, 248, 0.55);
        color: #ffffff;
      }
    }

    .danger-action {
      color: #fca5a5;
      border-color: rgba(248, 113, 113, 0.44);
      background: rgba(127, 29, 29, 0.18);

      &:hover {
        color: #fecaca;
        border-color: rgba(248, 113, 113, 0.72);
        background: rgba(127, 29, 29, 0.32);
      }
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
    color: #a5b4fc;
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
      color: #dbeafe;
    }
  }

  .line-no {
    border-right-color: rgba(148, 163, 184, 0.22);
  }

  .yaml-key {
    color: #93c5fd;
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
      border-color: rgba(129, 140, 248, 0.62);
      box-shadow: 0 16px 34px rgba(0, 0, 0, 0.24);
    }
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
    color: #a5b4fc;
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

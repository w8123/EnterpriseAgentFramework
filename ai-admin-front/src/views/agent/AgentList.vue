<template>
  <div class="agent-page page-container">
    <section class="agent-header">
      <div>
        <div class="eyebrow">
          <el-icon><Cpu /></el-icon>
          Agent Operations
        </div>
        <h1>智能体管理</h1>
      </div>

      <div class="header-stats">
        <div class="stat-item">
          <span>全部</span>
          <strong>{{ agents.length }}</strong>
        </div>
        <div class="stat-item">
          <span>启用</span>
          <strong>{{ enabledCount }}</strong>
        </div>
        <div class="stat-item">
          <span>流程型</span>
          <strong>{{ workflowAgentCount }}</strong>
        </div>
        <div class="stat-item">
          <span>待发布</span>
          <strong>{{ pendingPublishCount }}</strong>
        </div>
      </div>

      <div class="header-actions">
        <ViewToggle v-model="viewMode" />
        <el-button :icon="Connection" @click="router.push('/runops')">运行中心</el-button>
        <el-button type="primary" :icon="Plus" @click="handleCreate">新建智能体</el-button>
      </div>
    </section>

    <section class="agent-shell">
      <div class="filter-panel">
        <el-input
          v-model="filterKeyword"
          :prefix-icon="Search"
          placeholder="搜索名称、描述、keySlug 或 ID"
          clearable
          class="filter-control is-keyword"
        />
        <el-select v-model="filterRuntime" placeholder="运行时" clearable class="filter-control">
          <el-option
            v-for="runtime in runtimeFilterOptions"
            :key="runtime.value"
            :label="runtime.label"
            :value="runtime.value"
          />
        </el-select>
        <el-select v-model="filterEnabled" placeholder="状态" clearable class="filter-control is-narrow">
          <el-option label="已启用" :value="true" />
          <el-option label="已停用" :value="false" />
        </el-select>
        <el-select
          v-model="filterProjectId"
          placeholder="所属项目"
          clearable
          filterable
          class="filter-control is-wide"
        >
          <el-option
            v-for="project in scanProjects"
            :key="project.id"
            :label="projectOptionLabel(project)"
            :value="project.id"
          />
        </el-select>
        <el-button :icon="Search" @click="fetchData">查询</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="fetchData">刷新</el-button>
        <span class="filter-count">当前筛选 {{ filteredAgents.length }} / {{ agents.length }}</span>
      </div>

      <div v-if="viewMode === 'card'" v-loading="loading" class="agent-card-grid">
        <article
          v-for="agent in filteredAgents"
          :key="agent.id"
          class="agent-card"
          @click="handleEdit(agent.id)"
        >
          <div class="agent-card-top">
            <div class="agent-avatar" :class="runtimeAvatarClass(agent)">
              <el-icon><component :is="runtimeIcon(agent)" /></el-icon>
            </div>
            <div class="agent-title-area">
              <h3>{{ agent.name }}</h3>
              <span>{{ agent.keySlug || agent.id }}</span>
            </div>
            <el-switch
              :model-value="agent.enabled"
              size="small"
              @change="(val: boolean) => handleToggle(agent, val)"
              @click.stop
            />
          </div>

          <p class="agent-description">{{ agent.description || '暂无描述' }}</p>

          <div class="agent-card-meta">
            <el-tag size="small" :type="runtimeTagType(agent)" effect="plain">
              {{ runtimeModeLabel(agent) }}
            </el-tag>
            <el-tag size="small" effect="plain">
              {{ agent.projectCode || projectCodeById(agent.projectId) || '全局' }}
            </el-tag>
            <el-tag size="small" :type="agent.enabled ? 'success' : 'info'" effect="plain">
              {{ agent.enabled ? '启用' : '停用' }}
            </el-tag>
            <template v-if="isSdkManaged(agent)">
              <el-tooltip :content="sdkPublishState(agent).hint" placement="top">
                <el-tag size="small" :type="sdkPublishState(agent).type" effect="dark">
                  {{ sdkPublishState(agent).label }}
                </el-tag>
              </el-tooltip>
            </template>
          </div>

          <div class="agent-card-footer" @click.stop>
            <el-button
              v-for="action in agentListActions(agent)"
              :key="action.id"
              link
              :type="action.buttonType"
              size="small"
              @click="handleAgentAction(agent.id, action.id)"
            >
              {{ action.label }}
            </el-button>
          </div>
        </article>

        <el-empty v-if="!loading && filteredAgents.length === 0" description="暂无符合条件的智能体" />
      </div>

      <el-table v-else :data="filteredAgents" v-loading="loading" class="agent-table" stripe>
        <el-table-column prop="name" label="名称" min-width="210" fixed="left">
          <template #default="{ row }">
            <div class="name-cell">
              <div class="mini-avatar" :class="runtimeAvatarClass(row)">
                <el-icon><component :is="runtimeIcon(row)" /></el-icon>
              </div>
              <div>
                <el-link type="primary" @click="handleEdit(row.id)">{{ row.name }}</el-link>
                <p>{{ row.description || '暂无描述' }}</p>
                <small>{{ row.keySlug || row.id }}</small>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="运行时" width="116">
          <template #default="{ row }">
            <el-tag size="small" :type="runtimeTagType(row)" effect="plain">
              {{ runtimeModeLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="项目" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.projectCode || projectCodeById(row.projectId) || '全局' }}
          </template>
        </el-table-column>

        <el-table-column prop="enabled" label="状态" width="82" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              size="small"
              @change="(val: boolean) => handleToggle(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column label="发布状态" width="126">
          <template #default="{ row }">
            <div v-if="isSdkManaged(row)" class="publish-state-cell">
              <el-tag size="small" :type="sdkPublishState(row).type" effect="dark">
                {{ sdkPublishState(row).label }}
              </el-tag>
              <small>{{ sdkPublishState(row).shortHint }}</small>
            </div>
            <span v-else class="meta-empty">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="updatedAt" label="更新时间" width="138">
          <template #default="{ row }">
            {{ formatDateTime(row.updatedAt) }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="238" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                v-for="action in agentListActions(row)"
                :key="action.id"
                link
                :type="action.buttonType"
                size="small"
                @click.stop="handleAgentAction(row.id, action.id)"
              >
                {{ action.label }}
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Cpu, EditPen, Plus, Refresh, Search, Share } from '@element-plus/icons-vue'
import type { AgentDefinition, AgentMode, AgentVersion } from '@/types/agent'
import { deleteAgent, getAgentList, listAgentVersions, updateAgent } from '@/api/agent'
import { getScanProjects } from '@/api/scanProject'
import type { ScanProject } from '@/types/scanProject'
import ViewToggle from '@/components/ViewToggle.vue'
import { useProjectStore } from '@/store/project'
import { agentListActions, type AgentListActionId } from '@/utils/agentActions'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const agents = ref<AgentDefinition[]>([])
const agentVersions = ref<Record<string, AgentVersion[]>>({})
const scanProjects = ref<ScanProject[]>([])
const loading = ref(false)
const filterKeyword = ref('')
const filterRuntime = ref('')
const filterEnabled = ref<boolean | ''>('')
const filterProjectId = ref<number | undefined>(undefined)
const viewMode = ref<'table' | 'card'>('table')

const enabledCount = computed(() => agents.value.filter((agent) => agent.enabled).length)
const workflowAgentCount = computed(() => agents.value.filter((agent) => modeForAgent(agent) === 'WORKFLOW').length)
const pendingPublishCount = computed(() =>
  agents.value.filter((agent) => isSdkManaged(agent) && sdkPublishState(agent).type !== 'success').length,
)

const runtimeFilterOptions = computed(() => {
  const options = new Map<string, string>()
  agents.value.forEach((agent) => {
    const runtime = agent.runtimeType || 'AGENTSCOPE'
    options.set(runtime, runtimeModeLabel(agent))
  })
  return Array.from(options.entries()).map(([value, label]) => ({ value, label }))
})

const filteredAgents = computed(() => {
  const keyword = filterKeyword.value.trim().toLowerCase()
  return agents.value.filter((agent) => {
    if (filterRuntime.value && (agent.runtimeType || 'AGENTSCOPE') !== filterRuntime.value) return false
    if (filterEnabled.value !== '' && agent.enabled !== filterEnabled.value) return false
    if (!keyword) return true
    const haystack = [
      agent.name,
      agent.description,
      agent.keySlug,
      agent.id,
    ].filter(Boolean).join(' ').toLowerCase()
    return haystack.includes(keyword)
  })
})

function modeForAgent(agent: AgentDefinition): AgentMode {
  if (agent.agentMode) return agent.agentMode
  if (agent.runtimeType === 'LANGGRAPH4J') return 'WORKFLOW'
  if (agent.runtimeType === 'CURSOR_CODE_AGENT') return 'CODE'
  if (agent.runtimeType === 'OPENAI_AGENTS') return 'EXTERNAL'
  return 'AUTONOMOUS'
}

function runtimeModeLabel(agent: AgentDefinition) {
  const mode = modeForAgent(agent)
  if (mode === 'WORKFLOW') return '流程工作流'
  if (mode === 'CODE') return '代码工程'
  if (mode === 'EXTERNAL') return '外部托管'
  return '自主对话'
}

function runtimeTagType(agent: AgentDefinition): '' | 'success' | 'warning' | 'info' | 'danger' {
  const mode = modeForAgent(agent)
  if (mode === 'WORKFLOW') return 'warning'
  if (mode === 'CODE') return 'info'
  if (mode === 'EXTERNAL') return 'danger'
  return 'success'
}

function runtimeIcon(agent: AgentDefinition) {
  const mode = modeForAgent(agent)
  if (mode === 'WORKFLOW') return Connection
  if (mode === 'CODE') return EditPen
  if (mode === 'EXTERNAL') return Share
  return Cpu
}

function runtimeAvatarClass(agent: AgentDefinition) {
  return {
    workflow: modeForAgent(agent) === 'WORKFLOW',
    code: modeForAgent(agent) === 'CODE',
    external: modeForAgent(agent) === 'EXTERNAL',
  }
}

function sdkGraphMeta(agent: AgentDefinition): Record<string, unknown> {
  const raw = agent.extra?.sdkGraph
  return raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}
}

function isSdkManaged(agent: AgentDefinition) {
  const meta = sdkGraphMeta(agent)
  return meta.managedBy === 'SDK' || meta.source === 'SDK'
}

function sdkLastSyncedAt(agent: AgentDefinition) {
  const value = sdkGraphMeta(agent).lastSyncedAt
  return typeof value === 'string' ? value : ''
}

function latestPublishedVersion(agent: AgentDefinition) {
  const versions = agentVersions.value[agent.id] || []
  return [...versions]
    .filter((version) => version.status === 'ACTIVE' || version.status === 'RETIRED')
    .sort((a, b) => dateMs(b.publishedAt || b.createTime) - dateMs(a.publishedAt || a.createTime))[0] || null
}

function sdkPublishState(agent: AgentDefinition) {
  const latest = latestPublishedVersion(agent)
  const syncAt = sdkLastSyncedAt(agent)
  if (!latest) {
    return {
      type: 'danger' as const,
      label: '未发布',
      shortHint: '需要发布',
      hint: `SDK 草稿已同步${syncAt ? `于 ${syncAt}` : ''}，还没有发布版本。`,
    }
  }
  const currentHash = String(sdkGraphMeta(agent).sourceHash || '')
  const publishedHash = sdkSourceHashFromVersion(latest)
  const hashDiffers = currentHash && publishedHash && currentHash !== publishedHash
  const timeDiffers = !currentHash || !publishedHash
    ? dateMs(syncAt) > dateMs(latest.publishedAt || latest.createTime) + 1000
    : false
  if (hashDiffers || timeDiffers) {
    return {
      type: 'warning' as const,
      label: '待发布',
      shortHint: latest.version,
      hint: `SDK 草稿晚于最新发布版本 ${latest.version}，重新发布后生产入口才会生效。`,
    }
  }
  return {
    type: 'success' as const,
    label: '已同步',
    shortHint: latest.version,
    hint: `最新发布版本 ${latest.version} 已包含当前 SDK 图。`,
  }
}

function sdkSourceHashFromVersion(version: AgentVersion) {
  const snapshot = parseVersionSnapshot(version)
  const value = snapshot?.extra?.sdkGraph
  if (!value || typeof value !== 'object') return ''
  return String((value as Record<string, unknown>).sourceHash || '')
}

function parseVersionSnapshot(version: AgentVersion): AgentDefinition | null {
  if (!version.snapshotJson) return null
  try {
    return JSON.parse(version.snapshotJson) as AgentDefinition
  } catch {
    return null
  }
}

function dateMs(value?: string | null) {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed)) return value
  return new Date(parsed).toLocaleString()
}

function projectOptionLabel(project?: ScanProject | null) {
  if (!project) return ''
  const code = project.projectCode ? ` / ${project.projectCode}` : ''
  const env = project.environment ? ` · ${project.environment}` : ''
  return `${project.name}${code}${env}`
}

function projectCodeById(projectId?: number | null) {
  if (projectId == null) return null
  return scanProjects.value.find((project) => project.id === projectId)?.projectCode || null
}

function syncProjectFilter() {
  const queryProjectId = Number(route.query.projectId)
  if (Number.isFinite(queryProjectId) && queryProjectId > 0) {
    filterProjectId.value = queryProjectId
    projectStore.setCurrentProject(queryProjectId)
    return
  }
  filterProjectId.value = projectStore.currentProjectId ?? undefined
}

async function loadScanProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
    projectStore.projects = scanProjects.value
  } catch {
    scanProjects.value = []
  }
}

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getAgentList(
      filterProjectId.value !== undefined ? { projectId: filterProjectId.value } : undefined,
    )
    const list = Array.isArray(data) ? data : []
    agents.value = list
    await loadAgentVersions(list)
  } catch {
    agents.value = []
    agentVersions.value = {}
  } finally {
    loading.value = false
  }
}

async function loadAgentVersions(list: AgentDefinition[]) {
  const sdkAgents = list.filter((agent) => isSdkManaged(agent))
  if (!sdkAgents.length) {
    agentVersions.value = {}
    return
  }
  const pairs = await Promise.all(sdkAgents.map(async (agent) => {
    try {
      const { data } = await listAgentVersions(agent.id)
      return [agent.id, Array.isArray(data) ? data : []] as const
    } catch {
      return [agent.id, []] as const
    }
  }))
  agentVersions.value = Object.fromEntries(pairs)
}

function handleCreate() {
  router.push({
    path: '/agent/new/edit',
    query: filterProjectId.value !== undefined ? { projectId: filterProjectId.value } : {},
  })
}

function handleEdit(id: string) { router.push(`/agent/${id}/edit`) }
function handleDebug(id: string) { router.push(`/agent/${id}/debug`) }
function handleStudio(id: string) { router.push(`/agent/${id}/studio`) }
function handleVersions(id: string) { router.push(`/agent/${id}/versions`) }

function handleAgentAction(id: string, actionId: AgentListActionId) {
  if (actionId === 'edit') return handleEdit(id)
  if (actionId === 'debug') return handleDebug(id)
  if (actionId === 'studio') return handleStudio(id)
  if (actionId === 'versions') return handleVersions(id)
  return handleDelete(id)
}

async function handleToggle(agent: AgentDefinition, enabled: boolean) {
  try {
    await updateAgent(agent.id, { enabled })
    agent.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleDelete(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该智能体？', '删除智能体', { type: 'warning' })
    await deleteAgent(id)
    ElMessage.success('删除成功')
    fetchData()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(async () => {
  await loadScanProjects()
  syncProjectFilter()
  fetchData()
})

watch(
  () => projectStore.currentProjectId,
  () => {
    syncProjectFilter()
    fetchData()
  },
)
</script>

<style scoped lang="scss">
.agent-page {
  padding: 14px 16px 24px;
}

.agent-header,
.agent-shell {
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 10px;
  background: var(--bg-card);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.08);
}

.agent-header {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto auto;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  padding: 16px 18px;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  color: #818cf8;
  font-size: 12px;
  font-weight: 700;
}

h1,
h2 {
  margin: 0;
  color: var(--text-primary);
  letter-spacing: 0;
}

h1 {
  font-size: 24px;
  line-height: 32px;
}

h2 {
  font-size: 17px;
  line-height: 24px;
}

p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
}

.header-stats {
  display: grid;
  grid-template-columns: repeat(4, 86px);
  gap: 8px;
}

.stat-item {
  min-height: 58px;
  padding: 10px 12px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 8px;
  background: rgba(148, 163, 184, 0.06);

  span {
    display: block;
    color: var(--text-secondary);
    font-size: 12px;
  }

  strong {
    display: block;
    margin-top: 6px;
    color: var(--text-primary);
    font-size: 22px;
    line-height: 1;
  }
}

.header-actions,
.filter-panel,
.row-actions,
.agent-card-footer {
  display: flex;
  align-items: center;
}

.header-actions {
  gap: 10px;
}

.agent-shell {
  overflow: hidden;
}

.filter-panel {
  gap: 10px;
  flex-wrap: wrap;
  padding: 12px 16px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(148, 163, 184, 0.04);
}

.filter-control {
  width: 150px;

  &.is-keyword {
    width: 280px;
  }

  &.is-narrow {
    width: 112px;
  }

  &.is-wide {
    width: 210px;
  }
}

.filter-count {
  margin-left: auto;
  color: var(--text-secondary);
  font-size: 13px;
  white-space: nowrap;
}

.agent-table {
  :deep(th.el-table__cell) {
    height: 46px;
    font-weight: 700;
  }

  :deep(td.el-table__cell) {
    height: 58px;
  }
}

.name-cell,
.agent-card-top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mini-avatar,
.agent-avatar {
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #10b981, #6366f1);
  flex-shrink: 0;

  &.workflow {
    background: linear-gradient(135deg, #f59e0b, #8b5cf6);
  }

  &.code {
    background: linear-gradient(135deg, #0ea5e9, #6366f1);
  }

  &.external {
    background: linear-gradient(135deg, #ef4444, #8b5cf6);
  }
}

.mini-avatar {
  width: 32px;
  height: 32px;
  border-radius: 7px;
}

.agent-avatar {
  width: 44px;
  height: 44px;
  border-radius: 9px;
}

.name-cell > div:last-child,
.agent-title-area {
  min-width: 0;
  flex: 1;
}

.name-cell {
  p,
  small {
    display: block;
    max-width: 300px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    margin-top: 3px;
    color: var(--text-muted);
    font-size: 12px;
  }
}

.agent-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
  padding: 20px;
}

.agent-card {
  min-height: 220px;
  display: flex;
  flex-direction: column;
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 8px;
  background: var(--bg-card);
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;

  &:hover {
    border-color: rgba(129, 140, 248, 0.56);
    box-shadow: 0 14px 28px rgba(0, 0, 0, 0.14);
    transform: translateY(-1px);
  }
}

.agent-title-area {
  h3 {
    margin: 0;
    color: var(--text-primary);
    font-size: 16px;
    line-height: 22px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    display: block;
    margin-top: 4px;
    color: var(--text-muted);
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.agent-description {
  min-height: 42px;
  margin: 14px 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.agent-card-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.agent-card-footer {
  justify-content: flex-end;
  gap: 8px;
  margin-top: auto;
  padding-top: 14px;
  border-top: 1px solid rgba(148, 163, 184, 0.14);
}

.row-actions {
  gap: 2px;
}

.publish-state-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;

  small {
    max-width: 130px;
    color: var(--text-muted);
    font-size: 12px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.meta-empty {
  color: var(--text-muted);
  font-size: 12px;
}

@media (max-width: 1280px) {
  .agent-header {
    grid-template-columns: 1fr;
  }

  .header-stats {
    grid-template-columns: repeat(4, minmax(86px, 1fr));
  }

  .header-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 760px) {
  .agent-page {
    padding: 16px;
  }

  .header-stats {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }

  .filter-control,
  .filter-control.is-keyword,
  .filter-control.is-wide,
  .filter-control.is-narrow {
    width: 100%;
  }
}
</style>

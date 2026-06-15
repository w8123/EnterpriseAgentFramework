<template>
  <div class="agent-page page-container">
    <section class="agent-header">
      <div>
        <div class="eyebrow">
          <el-icon><Cpu /></el-icon>
          Agent Entry
        </div>
        <h1>智能体管理</h1>
        <p>Agent 是项目入口；Workflow 负责图编排与发布。</p>
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
          placeholder="搜索名称、keySlug 或 ID"
          clearable
          class="filter-control is-keyword"
        />
        <el-select v-model="filterAgentKind" placeholder="入口类型" clearable class="filter-control">
          <el-option
            v-for="item in agentKindOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
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
            <div class="agent-avatar">
              <el-icon><Cpu /></el-icon>
            </div>
            <div class="agent-title-area">
              <h3>{{ agent.name }}</h3>
              <span>{{ agent.keySlug || agent.id }}</span>
            </div>
            <el-switch
              :model-value="agent.enabled !== false"
              size="small"
              @change="(val: boolean) => handleToggle(agent, val)"
              @click.stop
            />
          </div>

          <div class="agent-card-meta">
            <el-tag size="small" effect="plain">{{ agentKindLabel(agent.agentKind) }}</el-tag>
            <el-tag size="small" effect="plain">
              {{ agent.projectCode || projectCodeById(agent.projectId) || '全局' }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ visibilityLabel(agent.visibility) }}</el-tag>
            <el-tag size="small" :type="agent.enabled !== false ? 'success' : 'info'" effect="plain">
              {{ agent.enabled !== false ? '启用' : '停用' }}
            </el-tag>
          </div>

          <div class="agent-card-footer" @click.stop>
            <el-button
              v-for="action in agentListActions(agent)"
              :key="action.id"
              link
              :type="action.buttonType"
              size="small"
              @click="handleAgentAction(agent, action.id)"
            >
              {{ action.label }}
            </el-button>
          </div>
        </article>

        <el-empty v-if="!loading && filteredAgents.length === 0" description="暂无符合条件的智能体" />
      </div>

      <el-table v-else :data="filteredAgents" v-loading="loading" class="agent-table" stripe>
        <el-table-column prop="name" label="名称" min-width="180" fixed="left">
          <template #default="{ row }">
            <el-link type="primary" @click="handleEdit(row.id)">{{ row.name }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="keySlug" label="keySlug" min-width="160" show-overflow-tooltip />
        <el-table-column label="入口类型" width="120">
          <template #default="{ row }">{{ agentKindLabel(row.agentKind) }}</template>
        </el-table-column>
        <el-table-column label="项目" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.projectCode || projectCodeById(row.projectId) || '全局' }}
          </template>
        </el-table-column>
        <el-table-column label="可见性" width="90">
          <template #default="{ row }">{{ visibilityLabel(row.visibility) }}</template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="82" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled !== false"
              size="small"
              @change="(val: boolean) => handleToggle(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                v-for="action in agentListActions(row)"
                :key="action.id"
                link
                :type="action.buttonType"
                size="small"
                @click.stop="handleAgentAction(row, action.id)"
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
import { Connection, Cpu, Plus, Refresh, Search } from '@element-plus/icons-vue'
import type { AgentEntry, AgentEntryKind } from '@/types/agent'
import { deleteAgentEntry, listAgentEntries, updateAgentEntry } from '@/api/workflow'
import { getScanProjects } from '@/api/scanProject'
import type { ScanProject } from '@/types/scanProject'
import ViewToggle from '@/components/ViewToggle.vue'
import { useProjectStore } from '@/store/project'
import { agentListActions, type AgentListActionId } from '@/utils/agentActions'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()

const agents = ref<AgentEntry[]>([])
const scanProjects = ref<ScanProject[]>([])
const loading = ref(false)
const filterKeyword = ref('')
const filterAgentKind = ref('')
const filterEnabled = ref<boolean | ''>('')
const filterProjectId = ref<number | undefined>(undefined)
const viewMode = ref<'table' | 'card'>('table')

const agentKindOptions = [
  { value: 'PROJECT_ENTRY', label: '项目入口' },
  { value: 'GLOBAL_EMBED', label: '全局嵌入' },
  { value: 'PAGE_ENTRY', label: '页面入口' },
]

const enabledCount = computed(() => agents.value.filter((agent) => agent.enabled !== false).length)

const filteredAgents = computed(() => {
  const keyword = filterKeyword.value.trim().toLowerCase()
  return agents.value.filter((agent) => {
    if (filterAgentKind.value && (agent.agentKind || 'PROJECT_ENTRY') !== filterAgentKind.value) return false
    if (filterEnabled.value !== '' && (agent.enabled !== false) !== filterEnabled.value) return false
    if (!keyword) return true
    const haystack = [agent.name, agent.keySlug, agent.id].filter(Boolean).join(' ').toLowerCase()
    return haystack.includes(keyword)
  })
})

function agentKindLabel(kind?: AgentEntryKind | null) {
  return agentKindOptions.find((item) => item.value === kind)?.label || kind || '项目入口'
}

function visibilityLabel(visibility?: string | null) {
  if (visibility === 'PRIVATE') return '私有'
  if (visibility === 'PUBLIC') return '公开'
  return '项目'
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
    const { data } = await listAgentEntries(
      filterProjectId.value !== undefined ? { projectId: filterProjectId.value } : undefined,
    )
    agents.value = Array.isArray(data) ? data : []
  } catch {
    agents.value = []
  } finally {
    loading.value = false
  }
}

function handleCreate() {
  router.push({
    path: '/agent/new/edit',
    query: filterProjectId.value !== undefined ? { projectId: filterProjectId.value } : {},
  })
}

function handleEdit(id: string) {
  router.push(`/agent/${id}/edit`)
}

function handleBindings(id: string) {
  router.push(`/agents/${id}/bindings`)
}

function handleStudio(id: string) {
  router.push(`/agent/${id}/studio`)
}

function handleAgentAction(agent: AgentEntry, actionId: AgentListActionId) {
  if (actionId === 'edit') return handleEdit(agent.id)
  if (actionId === 'bindings') return handleBindings(agent.id)
  if (actionId === 'studio') return handleStudio(agent.id)
  return handleDelete(agent.id)
}

async function handleToggle(agent: AgentEntry, enabled: boolean) {
  try {
    await updateAgentEntry(agent.id, { enabled })
    agent.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch {
    ElMessage.error('操作失败')
  }
}

async function handleDelete(id: string) {
  try {
    await ElMessageBox.confirm('确认删除该智能体入口？', '删除智能体', { type: 'warning' })
    await deleteAgentEntry(id)
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
  background:
    radial-gradient(circle at 16% 0%, rgb(var(--brand-selected-rgb) / 0.3), transparent 28%),
    radial-gradient(circle at 84% 16%, rgb(var(--brand-primary-rgb) / 0.08), transparent 30%);
}

.agent-header,
.agent-shell {
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.38);
  border-radius: 10px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.78), rgb(var(--brand-selected-rgb) / 0.18));
  box-shadow: 0 10px 28px rgb(var(--brand-primary-rgb) / 0.08);
  backdrop-filter: blur(16px);
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
  color: var(--brand-primary);
  font-size: 12px;
  font-weight: 700;
}

h1 {
  margin: 0;
  color: var(--text-primary);
  font-size: 24px;
  line-height: 32px;
}

p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
}

.header-stats {
  display: grid;
  grid-template-columns: repeat(2, 86px);
  gap: 8px;
}

.stat-item {
  min-height: 58px;
  padding: 10px 12px;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.44);
  border-radius: 8px;
  background: rgb(var(--brand-selected-rgb) / 0.12);

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
  border-bottom: 1px solid rgb(var(--brand-selected-rgb) / 0.38);
  background: rgb(var(--brand-selected-rgb) / 0.12);
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
    background: rgb(var(--brand-selected-rgb) / 0.34);
    color: var(--brand-active);
    font-weight: 700;
  }

  :deep(td.el-table__cell) {
    height: 52px;
  }
}

.agent-card-top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.agent-avatar {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 9px;
  color: #fff;
  background: linear-gradient(135deg, #10b981, var(--brand-primary));
  flex-shrink: 0;
}

.agent-title-area {
  min-width: 0;
  flex: 1;

  h3 {
    margin: 0;
    color: var(--text-primary);
    font-size: 16px;
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

.agent-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
  padding: 20px;
}

.agent-card {
  min-height: 180px;
  display: flex;
  flex-direction: column;
  padding: 16px;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.38);
  border-radius: 8px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.72), rgb(var(--brand-selected-rgb) / 0.14));
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;

  &:hover {
    border-color: rgb(var(--brand-hover-rgb) / 0.48);
    box-shadow: 0 14px 28px rgb(var(--brand-primary-rgb) / 0.12);
    transform: translateY(-1px);
  }
}

.agent-card-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
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

@media (max-width: 1280px) {
  .agent-header {
    grid-template-columns: 1fr;
  }

  .header-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 760px) {
  .filter-control,
  .filter-control.is-keyword,
  .filter-control.is-wide,
  .filter-control.is-narrow {
    width: 100%;
  }
}
</style>

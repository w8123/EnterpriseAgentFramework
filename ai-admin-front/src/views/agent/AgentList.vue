<template>
  <div class="agent-page page-container">
    <section class="agent-hero">
      <div class="hero-copy">
        <div class="eyebrow">
          <el-icon><Cpu /></el-icon>
          Agent Operations
        </div>
        <h1>Agent 管理</h1>
        <p>集中管理业务智能体、触发方式、能力白名单、运行状态和最近调用链路。</p>
      </div>

      <div class="hero-stats">
        <div class="stat-item">
          <span class="stat-label">全部 Agent</span>
          <strong>{{ agents.length }}</strong>
        </div>
        <div class="stat-item">
          <span class="stat-label">已启用</span>
          <strong>{{ enabledCount }}</strong>
        </div>
        <div class="stat-item">
          <span class="stat-label">项目级</span>
          <strong>{{ projectAgentCount }}</strong>
        </div>
        <div class="stat-item">
          <span class="stat-label">Pipeline</span>
          <strong>{{ pipelineCount }}</strong>
        </div>
      </div>

      <div class="hero-actions">
        <ViewToggle v-model="viewMode" />
        <el-button type="primary" :icon="Plus" @click="handleCreate">新建 Agent</el-button>
      </div>
    </section>

    <el-card shadow="never" class="agent-shell">
      <div class="shell-header">
        <el-tabs v-model="activeView" class="top-tabs">
          <el-tab-pane label="Agent 列表" name="agents" />
          <el-tab-pane label="最近 Trace" name="traces" />
        </el-tabs>

        <div class="shell-hint" v-if="activeView === 'agents'">
          当前筛选 {{ filteredAgents.length }} / {{ agents.length }}
        </div>
      </div>

      <template v-if="activeView === 'agents'">
        <div class="filter-panel">
          <el-select v-model="filterIntent" placeholder="意图类型" clearable class="filter-control">
            <el-option
              v-for="t in allIntentTypes"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
          <el-select v-model="filterTrigger" placeholder="触发方式" clearable class="filter-control">
            <el-option
              v-for="m in TRIGGER_MODES"
              :key="m.value"
              :label="m.label"
              :value="m.value"
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
        </div>

        <div v-if="viewMode === 'card'" v-loading="loading" class="agent-card-grid">
          <article
            v-for="agent in filteredAgents"
            :key="agent.id"
            class="agent-card"
            @click="handleEdit(agent.id)"
          >
            <div class="agent-card-top">
              <div class="agent-avatar" :class="{ pipeline: agent.type === 'pipeline' }">
                <el-icon><Cpu /></el-icon>
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

            <div class="agent-tags">
              <el-tag size="small" effect="plain">{{ intentLabel(agent.intentType) }}</el-tag>
              <el-tag :type="agent.type === 'pipeline' ? 'warning' : 'info'" size="small" effect="plain">
                {{ agent.type }}
              </el-tag>
              <el-tag size="small" :type="triggerTagType(agent.triggerMode)" effect="plain">
                {{ triggerLabel(agent.triggerMode) }}
              </el-tag>
              <el-tag size="small" effect="plain">{{ agent.visibility || 'PRIVATE' }}</el-tag>
            </div>

            <div class="agent-meta-grid">
              <div>
                <span>模型</span>
                <strong>{{ agent.modelName || '-' }}</strong>
              </div>
              <div>
                <span>项目</span>
                <strong>{{ agent.projectCode || projectCodeById(agent.projectId) || 'GLOBAL' }}</strong>
              </div>
              <div>
                <span>步数</span>
                <strong>{{ agent.maxSteps ?? '-' }}</strong>
              </div>
              <div>
                <span>知识库组</span>
                <strong>{{ agent.knowledgeBaseGroupId || '-' }}</strong>
              </div>
            </div>

            <div class="capability-strip">
              <template v-if="capabilityNames(agent).length">
                <el-tag
                  v-for="name in capabilityNames(agent).slice(0, 4)"
                  :key="name"
                  size="small"
                  class="tool-tag"
                >
                  {{ name }}
                </el-tag>
                <span v-if="capabilityNames(agent).length > 4" class="more-tools">
                  +{{ capabilityNames(agent).length - 4 }}
                </span>
              </template>
              <span v-else class="meta-empty">未配置能力</span>
            </div>

            <div class="agent-card-footer">
              <el-button link type="primary" size="small" @click.stop="handleEdit(agent.id)">编辑</el-button>
              <el-button link type="warning" size="small" @click.stop="handleStudio(agent.id)">画布</el-button>
              <el-button link type="info" size="small" @click.stop="handleVersions(agent.id)">版本</el-button>
              <el-button link type="success" size="small" @click.stop="handleDebug(agent.id)">调试</el-button>
              <el-popconfirm title="确认删除该 Agent？" @confirm="handleDelete(agent.id)">
                <template #reference>
                  <el-button link type="danger" size="small" @click.stop>删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </article>

          <el-empty v-if="!loading && filteredAgents.length === 0" description="暂无符合条件的 Agent" />
        </div>

        <el-table v-else :data="filteredAgents" v-loading="loading" class="agent-table" stripe>
          <el-table-column prop="name" label="名称" min-width="180" fixed="left">
            <template #default="{ row }">
              <div class="name-cell">
                <div class="mini-avatar">
                  <el-icon><Cpu /></el-icon>
                </div>
                <div>
                  <el-link type="primary" @click="handleEdit(row.id)">{{ row.name }}</el-link>
                  <small>{{ row.keySlug || row.id }}</small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="intentType" label="意图类型" width="140">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ intentLabel(row.intentType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="110">
            <template #default="{ row }">
              <el-tag :type="row.type === 'pipeline' ? 'warning' : 'info'" size="small" effect="plain">
                {{ row.type }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="triggerMode" label="触发方式" width="120" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="triggerTagType(row.triggerMode)" effect="plain">
                {{ triggerLabel(row.triggerMode) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="modelName" label="模型" width="140" show-overflow-tooltip />
          <el-table-column label="项目" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.projectCode || projectCodeById(row.projectId) || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="可见性" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.visibility || 'PRIVATE' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="能力" min-width="190">
            <template #default="{ row }">
              <el-tag
                v-for="name in capabilityNames(row).slice(0, 3)"
                :key="name"
                size="small"
                class="tool-tag"
              >
                {{ name }}
              </el-tag>
              <span v-if="capabilityNames(row).length > 3" class="more-tools">
                +{{ capabilityNames(row).length - 3 }}
              </span>
              <span v-if="capabilityNames(row).length === 0" class="meta-empty">-</span>
            </template>
          </el-table-column>
          <el-table-column label="知识库组" width="130">
            <template #default="{ row }">
              <span v-if="row.knowledgeBaseGroupId" class="meta-text">{{ row.knowledgeBaseGroupId }}</span>
              <span v-else class="meta-empty">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="maxSteps" label="步数" width="80" align="center" />
          <el-table-column prop="enabled" label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                size="small"
                @change="(val: boolean) => handleToggle(row, val)"
              />
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="180" />
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click.stop="handleEdit(row.id)">编辑</el-button>
              <el-button link type="warning" size="small" @click.stop="handleStudio(row.id)">画布编排</el-button>
              <el-button link type="info" size="small" @click.stop="handleVersions(row.id)">版本</el-button>
              <el-button link type="success" size="small" @click.stop="handleDebug(row.id)">调试</el-button>
              <el-popconfirm title="确认删除该 Agent？" @confirm="handleDelete(row.id)">
                <template #reference>
                  <el-button link type="danger" size="small" @click.stop>删除</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-else>
        <div class="filter-panel trace-filter">
          <el-input v-model="traceFilter.userId" placeholder="按 userId 过滤" clearable class="filter-control" />
          <el-select v-model="traceFilter.days" class="filter-control is-narrow">
            <el-option :value="1" label="近 1 天" />
            <el-option :value="7" label="近 7 天" />
            <el-option :value="14" label="近 14 天" />
          </el-select>
          <el-button :icon="Search" @click="fetchRecentTraces">查询</el-button>
        </div>
        <el-table :data="recentTraces" v-loading="traceLoading" class="agent-table" stripe>
          <el-table-column prop="traceId" label="traceId" min-width="260" show-overflow-tooltip />
          <el-table-column prop="agentName" label="Agent" width="160" />
          <el-table-column prop="intentType" label="Intent" width="140" />
          <el-table-column prop="callCount" label="调用数" width="90" />
          <el-table-column label="成功率" width="110">
            <template #default="{ row }">
              <span class="success-rate">{{ row.callCount ? ((row.successCount / row.callCount) * 100).toFixed(1) : '0.0' }}%</span>
            </template>
          </el-table-column>
          <el-table-column prop="startedAt" label="开始时间" width="190" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="copyTraceId(row.traceId)">复制 ID</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Cpu, Plus, Search } from '@element-plus/icons-vue'
import type { AgentDefinition } from '@/types/agent'
import { INTENT_TYPES, TRIGGER_MODES } from '@/types/agent'
import { deleteAgent, getAgentList, updateAgent } from '@/api/agent'
import { getScanProjects } from '@/api/scanProject'
import { getRecentTraces } from '@/api/trace'
import type { TraceSummary } from '@/types/trace'
import type { ScanProject } from '@/types/scanProject'
import ViewToggle from '@/components/ViewToggle.vue'
import { useProjectStore } from '@/store/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const agents = ref<AgentDefinition[]>([])
const scanProjects = ref<ScanProject[]>([])
const loading = ref(false)
const filterIntent = ref<string>('')
const filterTrigger = ref<string>('')
const filterEnabled = ref<boolean | ''>('')
const filterProjectId = ref<number | undefined>(undefined)
const activeView = ref<'agents' | 'traces'>('agents')
const recentTraces = ref<TraceSummary[]>([])
const traceLoading = ref(false)
const traceFilter = ref({ userId: '', days: 7 })
const viewMode = ref<'table' | 'card'>('table')

const allIntentTypes = computed(() => {
  const presetValues = new Set<string>(INTENT_TYPES.map((t) => t.value))
  const custom = agents.value
    .map((a) => a.intentType)
    .filter((v) => v && !presetValues.has(v))
    .filter((v, i, arr) => arr.indexOf(v) === i)
    .map((v) => ({ value: v, label: v }))
  return [...INTENT_TYPES, ...custom]
})

const enabledCount = computed(() => agents.value.filter((agent) => agent.enabled).length)
const projectAgentCount = computed(() => agents.value.filter((agent) => agent.projectId || agent.projectCode).length)
const pipelineCount = computed(() => agents.value.filter((agent) => agent.type === 'pipeline').length)

const filteredAgents = computed(() => {
  return agents.value.filter((a) => {
    if (filterIntent.value && a.intentType !== filterIntent.value) return false
    if (filterTrigger.value && a.triggerMode !== filterTrigger.value) return false
    if (filterEnabled.value !== '' && a.enabled !== filterEnabled.value) return false
    return true
  })
})

function intentLabel(type: string) {
  return INTENT_TYPES.find((t) => t.value === type)?.label || type
}

function triggerLabel(mode: string) {
  return TRIGGER_MODES.find((m) => m.value === mode)?.label || mode || '全部'
}

function triggerTagType(mode: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<string, '' | 'success' | 'warning' | 'info' | 'danger'> = {
    all: '',
    chat: 'success',
    api: 'warning',
    event: 'info',
  }
  return map[mode] ?? ''
}

function capabilityNames(agent: AgentDefinition) {
  return [...(agent.tools || []), ...(agent.skills || [])]
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
    agents.value = Array.isArray(data) ? data : []
  } catch {
    agents.value = []
  } finally {
    loading.value = false
  }
}

async function fetchRecentTraces() {
  traceLoading.value = true
  try {
    const { data } = await getRecentTraces({
      userId: traceFilter.value.userId || undefined,
      days: traceFilter.value.days,
      limit: 50,
    })
    recentTraces.value = Array.isArray(data) ? data : []
  } catch {
    recentTraces.value = []
    ElMessage.error('加载 Trace 失败')
  } finally {
    traceLoading.value = false
  }
}

function copyTraceId(traceId: string) {
  if (!traceId) return
  if (typeof window !== 'undefined' && window.navigator?.clipboard) {
    window.navigator.clipboard.writeText(traceId)
    ElMessage.success('traceId 已复制')
  }
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
    await deleteAgent(id)
    ElMessage.success('删除成功')
    fetchData()
  } catch {
    ElMessage.error('删除失败')
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

watch(activeView, (view) => {
  if (view === 'traces' && recentTraces.value.length === 0) {
    fetchRecentTraces()
  }
})
</script>

<style scoped lang="scss">
.agent-page {
  padding: 24px 32px 36px;
}

.agent-hero {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) auto auto;
  align-items: center;
  gap: 24px;
  margin-bottom: 18px;
  padding: 24px;
  border: 1px solid rgba(99, 102, 241, 0.18);
  border-radius: 12px;
  background:
    linear-gradient(135deg, rgba(99, 102, 241, 0.13), rgba(34, 211, 238, 0.05)),
    var(--bg-card);
  box-shadow: 0 16px 42px rgba(0, 0, 0, 0.12);
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: #818cf8;
  font-size: 13px;
  font-weight: 700;
}

.hero-copy {
  h1 {
    margin: 0;
    color: var(--text-primary);
    font-size: 28px;
    line-height: 1.2;
    font-weight: 760;
    letter-spacing: 0;
  }

  p {
    margin: 8px 0 0;
    color: var(--text-secondary);
    font-size: 14px;
  }
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(4, 104px);
  gap: 10px;
}

.stat-item {
  min-height: 74px;
  padding: 14px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);

  .stat-label {
    display: block;
    margin-bottom: 8px;
    color: var(--text-secondary);
    font-size: 12px;
  }

  strong {
    color: var(--text-primary);
    font-size: 24px;
    line-height: 1;
  }
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.agent-shell {
  border-radius: 12px;
  overflow: hidden;
}

.agent-shell :deep(.el-card__body) {
  padding: 0;
}

.shell-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 22px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.14);
}

.top-tabs {
  :deep(.el-tabs__header) {
    margin: 0;
  }

  :deep(.el-tabs__nav-wrap::after) {
    display: none;
  }

  :deep(.el-tabs__item) {
    height: 54px;
    font-weight: 700;
  }
}

.shell-hint {
  color: var(--text-secondary);
  font-size: 13px;
}

.filter-panel {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 18px 22px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(148, 163, 184, 0.04);
}

.filter-control {
  width: 180px;

  &.is-narrow {
    width: 140px;
  }

  &.is-wide {
    width: 240px;
  }
}

.agent-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 18px;
  padding: 22px;
}

.agent-card {
  min-height: 290px;
  display: flex;
  flex-direction: column;
  padding: 18px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 10px;
  background: var(--bg-card);
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;

  &:hover {
    border-color: rgba(129, 140, 248, 0.56);
    box-shadow: 0 18px 36px rgba(0, 0, 0, 0.16);
    transform: translateY(-2px);
  }
}

.agent-card-top,
.name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.agent-avatar,
.mini-avatar {
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
}

.agent-avatar {
  width: 46px;
  height: 46px;
  border-radius: 10px;
  flex-shrink: 0;

  &.pipeline {
    background: linear-gradient(135deg, #f59e0b, #ef4444);
  }
}

.mini-avatar {
  width: 30px;
  height: 30px;
  border-radius: 7px;
  flex-shrink: 0;
}

.agent-title-area,
.name-cell > div:last-child {
  min-width: 0;
  flex: 1;
}

.agent-title-area {
  h3 {
    margin: 0;
    color: var(--text-primary);
    font-size: 16px;
    font-weight: 730;
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

.name-cell {
  small {
    display: block;
    margin-top: 3px;
    color: var(--text-muted);
    font-size: 12px;
  }
}

.agent-description {
  min-height: 40px;
  margin: 14px 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.agent-tags,
.capability-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.agent-meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 16px 0;

  div {
    min-width: 0;
    padding: 10px;
    border-radius: 8px;
    background: rgba(148, 163, 184, 0.07);
  }

  span,
  strong {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    margin-bottom: 5px;
    color: var(--text-muted);
    font-size: 12px;
  }

  strong {
    color: var(--text-primary);
    font-size: 13px;
    font-weight: 650;
  }
}

.capability-strip {
  min-height: 26px;
  margin-top: auto;
}

.agent-card-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid rgba(148, 163, 184, 0.14);
}

.agent-table {
  :deep(th.el-table__cell) {
    height: 46px;
    font-weight: 700;
  }

  :deep(td.el-table__cell) {
    height: 54px;
  }
}

.tool-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}

.more-tools,
.meta-text {
  color: var(--text-secondary);
  font-size: 12px;
}

.meta-empty {
  color: var(--text-muted);
  font-size: 12px;
}

.success-rate {
  color: var(--success-color);
  font-weight: 700;
}

@media (max-width: 1280px) {
  .agent-hero {
    grid-template-columns: 1fr;
  }

  .hero-stats {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }

  .hero-actions {
    justify-content: flex-start;
  }
}
</style>

<template>
  <AppDialog
    v-model="visible"
    title="版本记录"
    width="920px"
    class="workflow-versions-dialog"
    :show-close="true"
    destroy-on-close
    append-to-body
  >
    <template #header>
      <div class="versions-dialog-header">
        <div class="header-left">
          <div class="header-icon-box">
            <el-icon><Tickets /></el-icon>
          </div>
          <div class="header-title-wrap">
            <span class="header-title">版本记录</span>
            <span class="header-subtitle">{{ workflowName || '当前工作流' }}</span>
          </div>
        </div>

        <div class="header-actions">
          <el-tooltip content="刷新数据" placement="top">
            <button class="header-btn" type="button" :disabled="loading" @click="loadData">
              <el-icon :class="{ 'is-loading': loading }"><Refresh /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </div>
    </template>

    <div class="versions-dialog-container" v-loading="loading">
      <!-- 左栏：版本时间线导航 -->
      <aside class="versions-sidebar">
        <div class="sidebar-header">
          <span class="sidebar-title">全部版本</span>
          <span class="sidebar-count">{{ versions.length }}</span>
        </div>

        <div v-if="versions.length === 0 && !loading" class="sidebar-empty">
          暂无发布版本
        </div>

        <div v-else class="version-nav-list">
          <button
            v-for="ver in versions"
            :key="ver.id"
            class="version-nav-item"
            :class="{
              'is-selected': selectedVersion?.id === ver.id,
              'is-active-version': ver.status === 'ACTIVE',
            }"
            type="button"
            @click="selectedVersion = ver"
          >
            <div class="nav-item-top">
              <span class="nav-version-code">{{ ver.version }}</span>
              <span v-if="ver.status === 'ACTIVE'" class="nav-active-pill">当前生效</span>
            </div>
            <div class="nav-item-meta">
              <span class="nav-meta-time">
                <el-icon><Clock /></el-icon>
                {{ formatShortDate(ver.publishedAt) }}
              </span>
              <span class="nav-meta-author">{{ ver.publishedBy || '管理员' }}</span>
            </div>
          </button>
        </div>
      </aside>

      <!-- 右栏：选中的版本详情与关联生态 -->
      <main class="versions-main" v-if="selectedVersion">
        <div class="version-detail-scroll">
          <!-- 顶部版本信息横幅 -->
          <div class="version-banner">
            <div class="banner-info">
              <div class="banner-title-row">
                <h3 class="banner-version-code">{{ selectedVersion.version }}</h3>
                <span v-if="selectedVersion.status === 'ACTIVE'" class="status-chip is-active">
                  <span class="chip-dot"></span>
                  当前生效
                </span>
                <span v-else class="status-chip is-history">历史版本</span>
              </div>
              <div class="banner-meta-row">
                <span>{{ formatFullDate(selectedVersion.publishedAt) }}</span>
                <span class="meta-separator">·</span>
                <span>发布人: {{ selectedVersion.publishedBy || '平台管理员' }}</span>
              </div>
            </div>

            <div class="banner-action">
              <el-button
                v-if="canPublish && selectedVersion.status !== 'ACTIVE'"
                type="primary"
                size="small"
                plain
                :loading="rollingBackId === selectedVersion.id"
                :disabled="rollingBackId !== null"
                @click="handleRollback(selectedVersion)"
              >
                回滚到此版本
              </el-button>
            </div>
          </div>

          <!-- 发布说明 / 变更日志 -->
          <div class="detail-section">
            <div class="section-title-row">
              <span class="section-title">更新说明</span>
            </div>
            <div class="release-note-card">
              {{ selectedVersion.note?.trim() || '本次发布未填写更新说明。' }}
            </div>
          </div>

          <!-- 关联的 Agent 引用列表 -->
          <div class="detail-section">
            <div class="section-title-row">
              <span class="section-title">关联 Agent</span>
              <span class="section-badge">{{ agentList.length }}</span>
            </div>

            <div v-if="agentList.length === 0" class="agents-empty-card">
              暂无已发布的 Agent 使用此 Workflow
            </div>

            <div v-else class="agent-cards-grid">
              <div
                v-for="agent in agentList"
                :key="agent.agentId"
                class="agent-card"
                :class="{ 'is-stale': agent.stale }"
              >
                <router-link
                  :to="`/agent/${encodeURIComponent(agent.agentId)}/edit`"
                  target="_blank"
                  class="agent-card-info"
                  title="在新标签页中打开 Agent 详情"
                >
                  <div class="agent-card-title-row">
                    <span class="agent-name">{{ agent.agentName || agent.keySlug || agent.agentId }}</span>
                    <span v-if="agent.activeConfigVersionNo" class="agent-ver-pill">线上配置 v{{ agent.activeConfigVersionNo }}</span>
                    <el-icon class="agent-jump-icon"><TopRight /></el-icon>
                  </div>
                  <span class="agent-keyslug">{{ agent.keySlug }}</span>
                </router-link>

                <div class="agent-card-status" @click.stop>
                  <span v-if="agent.stale" class="agent-status-tag is-warning">
                    使用旧版本 · 待同步
                  </span>
                  <span v-else class="agent-status-tag is-success">
                    已是最新
                  </span>

                  <el-button
                    v-if="agent.stale && canPublishAgent(agent)"
                    type="primary"
                    size="small"
                    class="sync-agent-btn"
                    :loading="updatingAgentId === agent.agentId"
                    :disabled="updatingAgentId !== null"
                    @click.stop="handleUpdateAgent(agent)"
                  >
                    同步发布
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </main>

      <div v-else class="versions-main-empty">
        <el-empty description="请选择左侧版本查看详情" :image-size="64" />
      </div>
    </div>
  </AppDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppDialog from '@/components/common/AppDialog.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Clock,
  Refresh,
  Tickets,
  TopRight,
} from '@element-plus/icons-vue'
import { getWorkflow, listWorkflowVersions, rollbackWorkflowVersion } from '@/api/workflow'
import {
  listWorkflowAgentReferences,
  publishLatestAgentWorkflowVersions,
  agentWorkflowFreshnessFailure,
} from '@/api/agentWorkflowFreshness'
import type { WorkflowVersion } from '@/types/workflow'
import type { WorkflowAgentReference, WorkflowAgentReferences } from '@/types/agent'
import {
  hasPlatformResourcePermission,
  PLATFORM_PERMISSION_AGENT_PUBLISH,
  PLATFORM_PERMISSION_WORKFLOW_PUBLISH,
} from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'

const props = defineProps<{
  modelValue: boolean
  workflowId: string
  workflowName?: string
  projectCode?: string
  baseRevision?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'rollback-success': [version: WorkflowVersion]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const loading = ref(false)
const versions = ref<WorkflowVersion[]>([])
const selectedVersion = ref<WorkflowVersion | null>(null)
const rollingBackId = ref<number | null>(null)
const agentReferences = ref<WorkflowAgentReferences | null>(null)
const updatingAgentId = ref<string | null>(null)

const canPublish = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_WORKFLOW_PUBLISH,
  'PROJECT',
  null,
  props.projectCode,
))

const agentList = computed(() => {
  return agentReferences.value?.agents || []
})

function canPublishAgent(agent: WorkflowAgentReference) {
  return hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_AGENT_PUBLISH,
    'PROJECT',
    null,
    agent.projectCode,
  )
}

async function loadData() {
  if (!props.workflowId) return
  loading.value = true
  try {
    const [verRes, agentRes] = await Promise.all([
      listWorkflowVersions(props.workflowId),
      listWorkflowAgentReferences(props.workflowId).catch(() => ({ data: null })),
    ])
    versions.value = Array.isArray(verRes.data) ? verRes.data : []
    agentReferences.value = agentRes.data

    if (versions.value.length > 0) {
      const active = versions.value.find((v) => v.status === 'ACTIVE')
      selectedVersion.value = active || versions.value[0]
    } else {
      selectedVersion.value = null
    }
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err)
    ElMessage.error(`加载版本记录失败: ${message}`)
  } finally {
    loading.value = false
  }
}

watch(visible, (isOpen) => {
  if (isOpen) {
    loadData()
  }
}, { immediate: true })

function formatShortDate(dateStr?: string | null) {
  if (!dateStr) return '-'
  try {
    const d = new Date(dateStr)
    if (isNaN(d.getTime())) return dateStr
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    const h = String(d.getHours()).padStart(2, '0')
    const min = String(d.getMinutes()).padStart(2, '0')
    return `${m}-${day} ${h}:${min}`
  } catch {
    return dateStr
  }
}

function formatFullDate(dateStr?: string | null) {
  if (!dateStr) return '-'
  try {
    const d = new Date(dateStr)
    if (isNaN(d.getTime())) return dateStr
    return d.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    })
  } catch {
    return dateStr
  }
}

async function handleRollback(ver: WorkflowVersion) {
  if (!canPublish.value || rollingBackId.value !== null) return
  try {
    await ElMessageBox.confirm(
      `将活动版本切换到 ${ver.version}，当前工作区草稿会保留。确认回滚该版本？`,
      '回滚 Workflow 版本',
      {
        confirmButtonText: '确认回滚',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }

  let baseRev: string | undefined = props.baseRevision || undefined
  if (!baseRev) {
    try {
      const { data: wf } = await getWorkflow(props.workflowId)
      baseRev = wf?.updatedAt || undefined
    } catch {
      // ignore
    }
  }
  if (!baseRev) {
    ElMessage.warning('未能读取草稿修订信息，请刷新版本状态后再重试。')
    return
  }

  rollingBackId.value = ver.id
  try {
    await rollbackWorkflowVersion(props.workflowId, ver.id, baseRev)
    ElMessage.success(`已成功回滚到版本 ${ver.version}`)
    await loadData()
    emit('rollback-success', ver)
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err)
    ElMessage.error(`回滚版本失败: ${message}`)
  } finally {
    rollingBackId.value = null
  }
}

async function handleUpdateAgent(agent: WorkflowAgentReference) {
  if (!agent.activeConfigVersionId || updatingAgentId.value !== null) return
  updatingAgentId.value = agent.agentId
  try {
    await publishLatestAgentWorkflowVersions(
      agent.agentId,
      agent.activeConfigVersionId,
      platformSessionUser.value?.displayName || (platformSessionUser.value?.userId ? String(platformSessionUser.value.userId) : 'admin-ui'),
    )
    ElMessage.success(`Agent「${agent.agentName || agent.keySlug}」已更新并发布至最新 Workflow`)
    const { data } = await listWorkflowAgentReferences(props.workflowId)
    agentReferences.value = data
  } catch (error) {
    ElMessage.error(agentWorkflowFreshnessFailure(error))
  } finally {
    updatingAgentId.value = null
  }
}
</script>

<style>
/* 弹层挂到 body，scoped 样式到不了，这里只调整本弹窗的头部与主体留白。 */
.workflow-versions-dialog.app-dialog .el-dialog__header {
  padding: 14px 20px;
  margin-right: 0;
}

.workflow-versions-dialog.app-dialog .el-dialog__body {
  padding: 0;
}
</style>

<style scoped>
/* 顶部 Header */
.versions-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-icon-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--brand-soft-bg);
  color: var(--brand-on-surface);
  font-size: 18px;
}

.header-title-wrap {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.header-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.active-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 1px 8px;
  border-radius: 20px;
  background: var(--status-success-soft);
  border: 1px solid color-mix(in srgb, var(--status-success) 40%, transparent);
  color: var(--status-success);
  font-size: 11px;
  font-weight: 600;
}

.pulse-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--status-success);
}

.header-subtitle {
  font-size: 11.5px;
  color: var(--text-muted);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-right: 28px;
}

.header-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 7px;
  border: 1px solid var(--border-divider);
  background: var(--surface-glass-inset);
  color: var(--text-muted);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.header-btn:hover {
  background: var(--surface-glass-selected);
  border-color: var(--border-readable);
  color: var(--text-primary);
}

/* 弹窗主体：两栏布局 */
.versions-dialog-container {
  display: flex;
  height: 490px;
  background: transparent;
}

/* 左侧版本列表栏 */
.versions-sidebar {
  width: 270px;
  flex-shrink: 0;
  background: var(--surface-glass-selected);
  border-right: 1px solid var(--border-divider);
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 10px;
  border-bottom: 1px solid var(--border-divider);
}

.sidebar-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
}

.sidebar-count {
  font-size: 11px;
  color: var(--text-disabled);
}

.sidebar-empty {
  padding: 30px 16px;
  text-align: center;
  font-size: 12px;
  color: var(--text-disabled);
}

.version-nav-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.version-nav-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 9px;
  border: 1px solid transparent;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: all 0.15s ease;
}

.version-nav-item:hover {
  background: var(--surface-glass-selected);
}

.version-nav-item.is-selected {
  background: var(--surface-glass-inset);
  border-color: var(--border-readable);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.05);
}

.version-nav-item.is-active-version.is-selected {
  border-color: rgb(var(--brand-primary-rgb) / 0.4);
}

.nav-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.nav-version-code {
  font-size: 13.5px;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  color: var(--text-primary);
}

.nav-active-pill {
  font-size: 10.5px;
  font-weight: 600;
  color: var(--status-success);
  background: var(--status-success-soft);
  padding: 1px 6px;
  border-radius: 4px;
}

.nav-item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--text-disabled);
}

.nav-meta-time {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* 右侧主详情区 */
.versions-main {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 20px 24px;
}

.version-detail-scroll {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 顶部横幅 */
.version-banner {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-divider);
}

.banner-info {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.banner-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.banner-version-code {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  color: var(--text-primary);
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 600;
}

.status-chip.is-active {
  background: var(--status-success-soft);
  color: var(--status-success);
}

.status-chip.is-history {
  background: var(--surface-glass-selected);
  color: var(--text-muted);
}

.chip-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--status-success);
}

.rollout-chip {
  padding: 2px 7px;
  border-radius: 6px;
  background: var(--surface-glass-selected);
  border: 1px solid var(--border-divider);
  color: var(--text-muted);
  font-size: 11px;
}

.banner-meta-row {
  font-size: 12px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 8px;
}

.meta-separator {
  color: var(--text-disabled);
}

/* 详情区块 */
.detail-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.section-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-secondary);
}

.section-badge {
  font-size: 10.5px;
  font-weight: 600;
  color: var(--text-muted);
  background: var(--surface-glass-selected);
  padding: 0 6px;
  border-radius: 10px;
}

.section-hint {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-disabled);
}

.release-note-card {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--surface-glass-selected);
  border: 1px solid var(--border-divider);
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

/* Agent 卡片列表 */
.agents-empty-card {
  padding: 14px;
  border-radius: 9px;
  border: 1px dashed var(--border-divider);
  background: var(--surface-glass-inset);
  color: var(--text-disabled);
  font-size: 12px;
  text-align: center;
}

.agent-cards-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.agent-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-radius: 9px;
  border: 1px solid var(--border-divider);
  background: var(--surface-glass-inset);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.02);
  transition: all 0.15s ease;
}

.agent-card:hover {
  border-color: var(--border-readable);
}

.agent-card-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
  text-decoration: none;
  cursor: pointer;
}

.agent-card-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.agent-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  transition: color 0.15s ease;
}

.agent-card-info:hover .agent-name {
  color: var(--brand-on-surface);
}

.agent-jump-icon {
  font-size: 12px;
  color: var(--text-disabled);
  opacity: 0;
  transition: all 0.15s ease;
  transform: translate(-1px, 1px);
}

.agent-card:hover .agent-jump-icon,
.agent-card-info:hover .agent-jump-icon {
  opacity: 1;
  color: var(--brand-on-surface);
  transform: translate(0, 0);
}

.agent-ver-pill {
  font-size: 10px;
  color: var(--text-muted);
  background: var(--surface-glass-selected);
  padding: 1px 5px;
  border-radius: 4px;
}

.agent-keyslug {
  font-size: 11px;
  color: var(--text-disabled);
}

.agent-card-status {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.agent-status-tag {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 5px;
  font-weight: 500;
}

.agent-status-tag.is-success {
  background: var(--status-success-soft);
  color: var(--status-success);
}

.agent-status-tag.is-warning {
  background: var(--status-warning-soft);
  color: var(--status-warning);
}

.sync-agent-btn {
  font-size: 11.5px;
}

.versions-main-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>

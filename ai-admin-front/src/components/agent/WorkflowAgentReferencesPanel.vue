<template>
  <section class="workflow-agent-references" aria-labelledby="workflow-agent-references-heading">
    <header class="workflow-agent-references__header">
      <div>
        <h2 id="workflow-agent-references-heading">引用此 Workflow 的 Agent</h2>
        <p>Agent 发布时会固定所用的 Workflow 版本；Workflow 发布新版本后，需要重新发布 Agent 才会生效。</p>
      </div>
      <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
    </header>

    <LiquidGlassNotice
      v-if="loadError"
      type="error"
      :title="loadError"
      source="backdrop"
    />
    <LiquidGlassNotice
      v-else-if="staleAgents.length"
      type="warning"
      :title="`${staleAgents.length} 个 Agent 仍在使用旧版本，发布 Agent 后才会用到 ${latestLabel}`"
      source="backdrop"
    />

    <p v-if="!loading && !loadError && !references?.agents.length && !hiddenCount" class="workflow-agent-references__empty">
      暂无已发布的 Agent 使用此 Workflow。
    </p>

    <ul v-if="references?.agents.length" class="workflow-agent-references__list">
      <li
        v-for="agent in references.agents"
        :key="agent.agentId"
        class="workflow-agent-references__row"
        :class="{ 'is-stale': agent.stale }"
        :data-agent-id="agent.agentId"
      >
        <div class="workflow-agent-references__identity">
          <router-link :to="`/agent/${encodeURIComponent(agent.agentId)}/edit`" class="workflow-agent-references__name">
            {{ agent.agentName || agent.keySlug || agent.agentId }}
          </router-link>
          <small>
            {{ agent.keySlug }}<template v-if="agent.activeConfigVersionNo"> · 线上配置 v{{ agent.activeConfigVersionNo }}</template>
            <template v-if="agent.agentEnabled === false"> · 已停用</template>
          </small>
        </div>
        <div class="workflow-agent-references__version">
          <StatusTag
            :label="`使用 ${formatWorkflowVersionLabel(agent.pinnedWorkflowVersion)}`"
            :tone="agent.stale ? 'warning' : 'success'"
          />
          <span v-if="agent.stale" class="workflow-agent-references__hint">
            仍在使用 {{ formatWorkflowVersionLabel(agent.pinnedWorkflowVersion) }}，发布 Agent 后才会用到新版本 {{ formatWorkflowVersionLabel(agent.latestWorkflowVersion) }}
          </span>
          <span v-else class="workflow-agent-references__hint">已是当前发布版本</span>
        </div>
        <div class="workflow-agent-references__actions">
          <el-button
            v-if="agent.stale && canPublish(agent)"
            size="small"
            type="primary"
            :loading="updatingAgentId === agent.agentId"
            :disabled="updatingAgentId !== null"
            @click="updateAgent(agent)"
          >
            更新并发布
          </el-button>
        </div>
      </li>
    </ul>

    <p v-if="hiddenCount" class="workflow-agent-references__hidden">
      另有 {{ hiddenCount }} 个你无权查看的 Agent 也在使用此 Workflow。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import {
  agentWorkflowFreshnessFailure,
  formatWorkflowVersionLabel,
  listWorkflowAgentReferences,
  publishLatestAgentWorkflowVersions,
} from '@/api/agentWorkflowFreshness'
import type { WorkflowAgentReference, WorkflowAgentReferences } from '@/types/agent'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_AGENT_PUBLISH } from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  workflowId: string
  /** Bump to reload, e.g. after this Workflow was published or rolled back. */
  refreshKey?: number
}>()

const emit = defineEmits<{
  (event: 'loaded', value: WorkflowAgentReferences): void
}>()

const loading = ref(false)
const loadError = ref('')
const references = ref<WorkflowAgentReferences | null>(null)
const updatingAgentId = ref<string | null>(null)

const staleAgents = computed(() => (references.value?.agents || []).filter(agent => agent.stale))
const hiddenCount = computed(() => references.value?.hiddenAgentCount || 0)
const latestLabel = computed(() => formatWorkflowVersionLabel(references.value?.latestWorkflowVersion))

function canPublish(agent: WorkflowAgentReference) {
  return hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_AGENT_PUBLISH,
    'PROJECT',
    null,
    agent.projectCode,
  )
}

async function reload() {
  if (!props.workflowId) return
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await listWorkflowAgentReferences(props.workflowId, { errorFeedback: 'local' })
    references.value = { ...data, agents: Array.isArray(data?.agents) ? data.agents : [] }
    emit('loaded', references.value)
  } catch {
    loadError.value = '引用此 Workflow 的 Agent 加载失败，请稍后刷新。'
  } finally {
    loading.value = false
  }
}

async function updateAgent(agent: WorkflowAgentReference) {
  if (updatingAgentId.value) return
  const name = agent.agentName || agent.keySlug || agent.agentId
  try {
    await ElMessageBox.confirm(
      `将基于「${name}」当前线上配置 v${agent.activeConfigVersionNo ?? '-'} 生成新版本，把它使用的 Workflow 更新到当前发布版本后立即发布。`
        + '其他配置和未发布的草稿保持不变。',
      '更新 Workflow 版本并发布 Agent',
      { type: 'warning', confirmButtonText: '更新并发布', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  updatingAgentId.value = agent.agentId
  try {
    const { data } = await publishLatestAgentWorkflowVersions(
      agent.agentId, agent.activeConfigVersionId, 'admin-ui', { errorFeedback: 'local' })
    ElMessage.success(data.published
      ? `「${name}」已发布配置 v${data.config.versionNo}，开始使用最新 Workflow 版本`
      : `「${name}」已在使用最新 Workflow 版本`)
  } catch (error) {
    ElMessage.error(agentWorkflowFreshnessFailure(error))
  } finally {
    updatingAgentId.value = null
    await reload()
  }
}

watch(() => [props.workflowId, props.refreshKey], () => { void reload() })
onMounted(() => { void reload() })

defineExpose({ reload })
</script>

<style scoped>
.workflow-agent-references {
  display: grid;
  gap: 10px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--surface-glass-inset);
}

.workflow-agent-references__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.workflow-agent-references__header h2 {
  margin: 0;
  font-size: 15px;
}

.workflow-agent-references__header p,
.workflow-agent-references__empty,
.workflow-agent-references__hidden {
  margin: 2px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.workflow-agent-references__list {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
}

.workflow-agent-references__row {
  display: grid;
  grid-template-columns: minmax(160px, 1fr) minmax(220px, 2fr) auto;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

.workflow-agent-references__identity {
  display: grid;
  min-width: 0;
}

.workflow-agent-references__identity small,
.workflow-agent-references__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.workflow-agent-references__row.is-stale .workflow-agent-references__hint {
  color: var(--el-color-warning);
}

.workflow-agent-references__name {
  color: var(--brand-active);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.workflow-agent-references__version {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.workflow-agent-references__actions {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 720px) {
  .workflow-agent-references__row {
    grid-template-columns: 1fr;
  }
  .workflow-agent-references__actions {
    justify-content: flex-start;
  }
}
</style>

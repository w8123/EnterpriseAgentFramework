<script setup lang="ts">
import { computed } from 'vue'
import {
  ArrowRight,
  ChatDotRound,
  CircleCheckFilled,
  Connection,
  Cpu,
  Document,
  EditPen,
  Guide,
  Lock,
  VideoPlay,
} from '@element-plus/icons-vue'

import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import type { AgentA2aRemoteBindingConfig, AgentWorkflowToolConfig } from '@/types/agent'
import type { AgentSkillBindingConfig } from '@/types/skill'

type ConfigSection = 'basic' | 'decision' | 'workflows' | 'remote' | 'skills'

const props = defineProps<{
  modelName: string
  systemPrompt: string
  workflowTools: AgentWorkflowToolConfig[]
  remoteAgents: AgentA2aRemoteBindingConfig[]
  skills: AgentSkillBindingConfig[]
  activeVersionNo?: number | null
  draftVersionNo?: number | null
  projectLabel: string
  allowedRoles: string[]
  canDebug: boolean
  canWrite: boolean
}>()

const emit = defineEmits<{
  'edit-section': [section: ConfigSection]
  'edit-prompt': []
  advanced: []
  'add-workflow': []
  'add-remote': []
  'add-skill': []
  debug: []
  'show-versions': []
}>()

const enabledWorkflows = computed(() => props.workflowTools.filter((item) => item.enabled !== false))
const visibleWorkflows = computed(() => enabledWorkflows.value.slice(0, 3))
const enabledRemoteAgents = computed(() => props.remoteAgents.filter((item) => item.enabled !== false))
const enabledSkills = computed(() => props.skills.filter((item) => item.enabled !== false))
const systemPromptSummary = computed(() => {
  const normalized = (props.systemPrompt || '').replace(/\s+/g, ' ').trim()
  if (!normalized) return '尚未填写工作要求'
  return normalized.length > 74 ? `${normalized.slice(0, 74)}…` : normalized
})
const roleLabel = computed(() => props.allowedRoles.length ? props.allowedRoles.join('、') : '不限')
const statusHeadline = computed(() => props.draftVersionNo ? '有配置草稿待发布' : '当前配置已生效')
const statusSubline = computed(() => {
  if (props.draftVersionNo && props.activeVersionNo) return `草稿 v${props.draftVersionNo} · 线上仍为 v${props.activeVersionNo}`
  if (props.draftVersionNo) return `草稿 v${props.draftVersionNo} · 尚未发布`
  if (props.activeVersionNo) return `线上版本 v${props.activeVersionNo}`
  return '尚未发布线上版本'
})
const permissionReady = computed(() => enabledWorkflows.value.every((item) => Boolean(item.permissionKey?.trim())))

function workflowName(item: AgentWorkflowToolConfig) {
  return item.workflowName || item.workflowKeySlug || item.toolName || item.workflowId
}

function workflowDescription(item: AgentWorkflowToolConfig) {
  return item.descriptionOverride || item.description || '使用已发布 Workflow 的默认用途说明'
}

function riskLabel(value?: string) {
  return ({
    READ: '只查询',
    WRITE: '会修改数据',
    PAGE_ACTION: '会操作页面',
    IRREVERSIBLE: '可能无法撤销',
  } as Record<string, string>)[value || ''] || value || '未标注'
}

function riskClass(value?: string) {
  if (value === 'READ') return 'is-read'
  if (value === 'PAGE_ACTION') return 'is-page-action'
  if (value === 'IRREVERSIBLE') return 'is-danger'
  return 'is-write'
}
</script>

<template>
  <div class="agent-overview-layout">
    <div class="agent-overview-main">
      <WorkbenchPanel
        class="overview-panel decision-panel"
        title="对话与决策"
        description="Agent 理解需求和选择流程时使用的核心设置。"
        density="compact"
      >
        <template #actions>
          <el-button link type="primary" :icon="Cpu" @click="emit('advanced')">高级设置</el-button>
          <el-button v-if="props.canWrite" link type="primary" :icon="EditPen" @click="emit('edit-prompt')">编辑</el-button>
        </template>

        <div class="decision-summary-grid">
          <div class="decision-summary-item">
            <span class="summary-icon"><el-icon><Cpu /></el-icon></span>
            <div>
              <small>默认模型</small>
              <strong>{{ props.modelName || '尚未配置' }}</strong>
              <p>用于理解复杂任务并选择合适的处理流程。</p>
            </div>
          </div>
          <button class="decision-summary-item prompt-summary" type="button" @click="emit('edit-prompt')">
            <span class="summary-icon"><el-icon><Document /></el-icon></span>
            <div>
              <small>工作要求</small>
              <strong>{{ systemPromptSummary }}</strong>
              <span class="inline-action">查看完整内容 <el-icon><ArrowRight /></el-icon></span>
            </div>
          </button>
        </div>
      </WorkbenchPanel>

      <WorkbenchPanel
        class="overview-panel workflow-overview-panel"
        title="可调用 Workflow"
        description="Agent 会根据用户需求，从这里选择合适的流程。"
        density="compact"
      >
        <template #actions>
          <el-button link type="primary" @click="emit('edit-section', 'workflows')">
            管理
            <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </template>

        <div v-if="visibleWorkflows.length" class="workflow-card-grid">
          <button
            v-for="(item, index) in visibleWorkflows"
            :key="item.workflowId"
            class="workflow-overview-card"
            type="button"
            @click="emit('edit-section', 'workflows')"
          >
            <span class="workflow-card-heading">
              <span class="workflow-card-icon" :class="`tone-${index % 3}`">
                <el-icon><Document /></el-icon>
              </span>
              <span class="workflow-card-copy">
                <strong>{{ workflowName(item) }}</strong>
                <small>{{ workflowDescription(item) }}</small>
              </span>
            </span>
            <span class="workflow-card-footer">
              <span class="risk-pill" :class="riskClass(item.riskLevel)">{{ riskLabel(item.riskLevel) }}</span>
              <span class="available-dot">可用</span>
              <el-icon><ArrowRight /></el-icon>
            </span>
          </button>
        </div>

        <div v-else class="overview-empty-state">
          <span class="summary-icon"><el-icon><Document /></el-icon></span>
          <div><strong>还没有可调用的 Workflow</strong><p>添加后，Agent 才能执行对应的企业流程。</p></div>
          <el-button v-if="props.canWrite" type="primary" @click="emit('add-workflow')">添加 Workflow</el-button>
        </div>

        <button
          v-if="enabledWorkflows.length > visibleWorkflows.length"
          class="more-configured-button"
          type="button"
          @click="emit('edit-section', 'workflows')"
        >
          另外还有 {{ enabledWorkflows.length - visibleWorkflows.length }} 个 Workflow
          <el-icon><ArrowRight /></el-icon>
        </button>
      </WorkbenchPanel>

      <WorkbenchPanel
        class="overview-panel collaboration-panel"
        title="Agent 协作与 Skill"
        density="compact"
      >
        <div class="collaboration-summary-grid">
          <div class="collaboration-summary-item">
            <span class="summary-icon"><el-icon><Connection /></el-icon></span>
            <div class="collaboration-copy">
              <strong>Agent 协作</strong>
              <span>{{ enabledRemoteAgents.length ? `已添加 ${enabledRemoteAgents.length} 个` : '暂未添加' }}</span>
            </div>
            <el-button
              v-if="props.canWrite && !enabledRemoteAgents.length"
              link
              type="primary"
              @click="emit('add-remote')"
            >添加 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
            <el-button v-else link type="primary" @click="emit('edit-section', 'remote')">查看 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
          </div>
          <div class="collaboration-summary-item">
            <span class="summary-icon is-blue"><el-icon><Guide /></el-icon></span>
            <div class="collaboration-copy">
              <strong>Skill</strong>
              <span>{{ enabledSkills.length ? `已添加 ${enabledSkills.length} 个` : '暂未添加' }}</span>
            </div>
            <el-button
              v-if="props.canWrite && !enabledSkills.length"
              link
              type="primary"
              @click="emit('add-skill')"
            >添加 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
            <el-button v-else link type="primary" @click="emit('edit-section', 'skills')">查看 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
          </div>
        </div>
      </WorkbenchPanel>
    </div>

    <aside class="agent-overview-side" aria-label="Agent 配置状态与快捷操作">
      <WorkbenchPanel class="overview-side-card status-summary-card" density="compact">
        <h2>配置状态</h2>
        <div class="status-summary-hero" :class="{ 'has-draft': props.draftVersionNo }">
          <span class="status-summary-icon"><el-icon><CircleCheckFilled /></el-icon></span>
          <span><strong>{{ statusHeadline }}</strong><small>{{ statusSubline }}</small></span>
        </div>
        <ul class="configuration-check-list">
          <li><el-icon><CircleCheckFilled /></el-icon>{{ props.modelName ? '默认模型已配置' : '默认模型尚未配置' }}</li>
          <li><el-icon><CircleCheckFilled /></el-icon>{{ enabledWorkflows.length }} 个 Workflow 可用</li>
          <li><el-icon><CircleCheckFilled /></el-icon>{{ permissionReady ? '调用权限完整' : '存在未填写的调用权限' }}</li>
        </ul>
        <el-button link type="primary" @click="emit('show-versions')">查看发布详情 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
      </WorkbenchPanel>

      <WorkbenchPanel class="overview-side-card scope-summary-card" density="compact">
        <h2>使用范围</h2>
        <dl>
          <div><span class="scope-icon"><el-icon><Connection /></el-icon></span><dt>所属项目</dt><dd>{{ props.projectLabel }}</dd></div>
          <div><span class="scope-icon is-blue"><el-icon><Lock /></el-icon></span><dt>允许角色</dt><dd>{{ roleLabel }}</dd></div>
        </dl>
        <el-button v-if="props.canWrite" link type="primary" @click="emit('edit-section', 'basic')">调整范围 <el-icon class="el-icon--right"><ArrowRight /></el-icon></el-button>
      </WorkbenchPanel>

      <WorkbenchPanel v-if="props.canDebug" class="overview-side-card debug-summary-card" density="compact">
        <div class="debug-summary-heading"><span class="debug-icon"><el-icon><ChatDotRound /></el-icon></span><h2>开始调试</h2></div>
        <p>模拟一次真实提问，检查回答内容和 Workflow 调用过程。</p>
        <el-button type="primary" :icon="VideoPlay" @click="emit('debug')">进入调试</el-button>
      </WorkbenchPanel>
    </aside>
  </div>
</template>

<style scoped lang="scss">
.agent-overview-layout {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) 292px;
  align-items: start;
  gap: var(--section-gap);
}

.agent-overview-main,
.agent-overview-side {
  display: grid;
  min-width: 0;
  gap: var(--section-gap);
}

.overview-panel,
.overview-side-card {
  border: 1px solid var(--border-divider);
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
}

.overview-panel :deep(.workbench-panel__header) {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border-divider);
}

.overview-panel :deep(.workbench-panel__title),
.overview-side-card h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 22px;
}

.decision-summary-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.35fr);
}

.decision-summary-item {
  display: grid;
  min-width: 0;
  grid-template-columns: 42px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  padding: 12px 16px 4px 0;
}

.decision-summary-item + .decision-summary-item {
  padding-left: 22px;
  border-left: 1px solid var(--border-divider);
}

.prompt-summary {
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.prompt-summary:hover strong,
.prompt-summary:focus-visible strong {
  color: var(--brand-active);
}

.prompt-summary:focus-visible {
  outline: 2px solid rgb(var(--brand-primary-rgb) / 0.28);
  outline-offset: 3px;
  border-radius: var(--radius-md);
}

.summary-icon,
.workflow-card-icon,
.scope-icon,
.debug-icon {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 11px;
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-size: 20px;
}

.decision-summary-item small,
.decision-summary-item strong,
.decision-summary-item p {
  display: block;
}

.decision-summary-item small {
  color: var(--text-muted);
  font-size: 11px;
}

.decision-summary-item strong {
  overflow: hidden;
  margin-top: 2px;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
}

.decision-summary-item p {
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.inline-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 5px;
  color: var(--brand-active);
  font-size: 11px;
  font-weight: 650;
}

.workflow-card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.workflow-overview-card {
  display: flex;
  min-width: 0;
  min-height: 148px;
  flex-direction: column;
  justify-content: space-between;
  gap: 18px;
  padding: 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-solid-control);
  color: var(--text-primary);
  box-shadow: var(--inner-highlight);
  cursor: pointer;
  text-align: left;
  transition: border-color var(--motion-duration-fast) ease, box-shadow var(--motion-duration-fast) ease, transform var(--motion-duration-fast) ease;
}

.workflow-overview-card:hover,
.workflow-overview-card:focus-visible {
  border-color: rgb(var(--brand-primary-rgb) / 0.34);
  box-shadow: var(--shadow-panel);
  transform: translateY(-1px);
}

.workflow-overview-card:focus-visible {
  outline: 2px solid rgb(var(--brand-primary-rgb) / 0.24);
  outline-offset: 2px;
}

.workflow-card-heading {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 11px;
}

.workflow-card-icon {
  width: 36px;
  height: 36px;
  flex-basis: 36px;
  font-size: 17px;
}

.workflow-card-icon.tone-1,
.summary-icon.is-blue,
.scope-icon.is-blue {
  color: var(--status-info);
  background: color-mix(in srgb, var(--status-info) 10%, var(--surface-solid-control));
}

.workflow-card-icon.tone-2 {
  color: var(--status-warning);
  background: color-mix(in srgb, var(--status-warning) 10%, var(--surface-solid-control));
}

.workflow-card-copy {
  min-width: 0;
}

.workflow-card-copy strong,
.workflow-card-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
}

.workflow-card-copy strong {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 20px;
  white-space: nowrap;
}

.workflow-card-copy small {
  display: -webkit-box;
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 18px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.workflow-card-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-muted);
  font-size: 11px;
}

.workflow-card-footer > .el-icon {
  margin-left: 0;
}

.risk-pill {
  padding: 3px 8px;
  border-radius: 999px;
  color: var(--status-warning);
  background: color-mix(in srgb, var(--status-warning) 10%, var(--surface-solid-control));
  font-weight: 650;
}

.risk-pill.is-read {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.risk-pill.is-page-action {
  color: var(--status-info);
  background: color-mix(in srgb, var(--status-info) 10%, var(--surface-solid-control));
}

.risk-pill.is-danger {
  color: var(--status-danger);
  background: color-mix(in srgb, var(--status-danger) 10%, var(--surface-solid-control));
}

.available-dot {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  color: var(--status-success);
  font-weight: 650;
}

.available-dot::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.overview-empty-state {
  display: flex;
  min-height: 92px;
  align-items: center;
  gap: 13px;
  padding: 14px;
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-lg);
  background: var(--bg-subtle);
}

.overview-empty-state > div {
  min-width: 0;
  flex: 1;
}

.overview-empty-state strong {
  color: var(--text-primary);
}

.overview-empty-state p {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.more-configured-button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  justify-self: end;
  margin-top: 10px;
  padding: 3px 0;
  border: 0;
  background: transparent;
  color: var(--brand-active);
  font-size: 11px;
  cursor: pointer;
}

.collaboration-summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.collaboration-summary-item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-solid-control);
}

.collaboration-copy {
  min-width: 0;
  flex: 1;
}

.collaboration-copy strong,
.collaboration-copy span {
  display: block;
}

.collaboration-copy strong {
  color: var(--text-primary);
  font-size: 13px;
}

.collaboration-copy span {
  margin-top: 2px;
  color: var(--text-secondary);
  font-size: 11px;
}

.overview-side-card h2 {
  margin-bottom: 14px;
}

.status-summary-hero {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 15px;
  border-bottom: 1px solid var(--border-divider);
}

.status-summary-icon {
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 46px;
  place-items: center;
  border-radius: 50%;
  color: var(--text-inverse);
  background: var(--status-success);
  box-shadow: 0 10px 22px -14px color-mix(in srgb, var(--status-success) 70%, transparent);
  font-size: 23px;
}

.status-summary-hero.has-draft .status-summary-icon {
  background: var(--status-warning);
}

.status-summary-hero strong,
.status-summary-hero small {
  display: block;
}

.status-summary-hero strong {
  color: var(--brand-active);
  font-size: 14px;
}

.status-summary-hero small {
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 17px;
}

.configuration-check-list {
  display: grid;
  gap: 10px;
  margin: 15px 0 12px;
  padding: 0;
  list-style: none;
}

.configuration-check-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 12px;
}

.configuration-check-list .el-icon {
  color: var(--status-success);
}

.scope-summary-card dl {
  display: grid;
  gap: 12px;
  margin: 0 0 12px;
}

.scope-summary-card dl > div {
  display: grid;
  min-width: 0;
  grid-template-columns: 34px 70px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}

.scope-icon {
  width: 34px;
  height: 34px;
  flex-basis: 34px;
  font-size: 16px;
}

.scope-summary-card dt {
  color: var(--text-muted);
  font-size: 11px;
}

.scope-summary-card dd {
  overflow: hidden;
  margin: 0;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.debug-summary-card {
  background:
    linear-gradient(145deg, transparent 30%, rgb(var(--brand-primary-rgb) / 0.07)),
    var(--surface-glass-panel);
}

.debug-summary-heading {
  display: flex;
  align-items: center;
  gap: 10px;
}

.debug-summary-heading h2 {
  margin: 0;
}

.debug-icon {
  color: var(--text-inverse);
  background: var(--brand-primary);
}

.debug-summary-card p {
  margin: 12px 0 14px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.debug-summary-card .el-button {
  width: 100%;
}

@media (max-width: 1180px) {
  .agent-overview-layout {
    grid-template-columns: minmax(0, 1fr) 260px;
  }

  .workflow-card-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .workflow-overview-card {
    min-height: 118px;
  }
}

@media (max-width: 900px) {
  .agent-overview-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .agent-overview-side {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .debug-summary-card {
    grid-column: 1 / -1;
  }
}

@media (max-width: 680px) {
  .decision-summary-grid,
  .collaboration-summary-grid,
  .agent-overview-side {
    grid-template-columns: minmax(0, 1fr);
  }

  .decision-summary-item + .decision-summary-item {
    padding-left: 0;
    border-top: 1px solid var(--border-divider);
    border-left: 0;
  }

  .debug-summary-card {
    grid-column: auto;
  }
}
</style>

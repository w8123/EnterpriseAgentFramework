<template>
  <WorkbenchPage class="agent-edit-page" density="compact" full-height>
    <PageHeader
      :variant="isNew ? 'standard' : 'entity'"
      domain="agent"
      presentation="plain"
      action-presentation="inline"
      :title="isNew ? '新建 Agent' : (form.name || agentId || 'Agent 配置')"
      tag-placement="title"
      :description="isNew ? '创建一个能够理解需求并调用企业流程的 Agent。' : (form.description || '尚未填写职责说明')"
      density="compact"
      :artwork="false"
    >
      <template v-if="!isNew" #leading>
        <span class="app-page-header__entity-mark agent-entity-mark"><el-icon><Cpu /></el-icon></span>
      </template>
      <template v-if="!isNew" #tags>
        <StatusTag
          :label="form.enabled !== false ? '已启用' : '已停用'"
          :tone="form.enabled !== false ? 'success' : 'info'"
        />
        <StatusTag
          v-if="!isNew && activeConfig"
          :label="`线上 v${activeConfig.versionNo}`"
          tone="info"
        />
        <StatusTag v-if="!isNew && draftConfig" :label="`草稿 v${draftConfig.versionNo} 待发布`" tone="warning" />
      </template>
      <template #actions>
        <template v-if="isNew">
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
        </template>
        <template v-else-if="activeSection === 'config'">
          <el-button v-if="canDebugAgent && canWriteAgent" :icon="VideoPlay" :loading="saving" @click="handleSaveAndDebug">保存并调试</el-button>
          <el-button v-else-if="canDebugAgent" :icon="VideoPlay" @click="openDebug">调试</el-button>
          <el-button v-if="canWriteAgent" :loading="saving" @click="handleSave">保存草稿</el-button>
          <el-button v-if="canPublishAgent" type="primary" :loading="publishing" @click="handlePublish">发布配置</el-button>
        </template>
        <template v-else>
          <el-button v-if="canDebugAgent" :icon="VideoPlay" @click="openDebug">调试</el-button>
          <el-button v-if="canWriteAgent" type="primary" :icon="EditPen" @click="activeSection = 'config'">编辑配置</el-button>
        </template>
      </template>
    </PageHeader>

    <LiquidGlassNav v-if="!isNew" source="wallpaper" class="agent-detail-tabs" aria-label="Agent 详情分区">
      <button type="button" :class="{ 'is-active': activeSection === 'overview' }" @click="activeSection = 'overview'">概览</button>
      <button type="button" :class="{ 'is-active': activeSection === 'config' }" @click="activeSection = 'config'">配置</button>
      <button type="button" :class="{ 'is-active': activeSection === 'versions' }" @click="showVersionSection">版本记录</button>
      <button v-if="canReadEmbed" type="button" :class="{ 'is-active': activeSection === 'embed' }" @click="activeSection = 'embed'">嵌入</button>
      <button v-if="canReadRuns" type="button" class="is-link" @click="openRunRecords()">运行记录<el-icon><TopRight /></el-icon></button>
      <button v-if="canEvaluateAgent" type="button" class="is-link" @click="openAgentEval">评测<el-icon><TopRight /></el-icon></button>
    </LiquidGlassNav>

    <LiquidGlassNotice
      v-if="!isNew && staleWorkflowTools.length"
      class="agent-workflow-freshness-alert"
      type="warning"
      :title="`${staleWorkflowTools.length} 个 Workflow 有新发布版本，线上配置 v${workflowFreshness?.activeConfigVersionNo ?? '-'} 仍在使用旧版本`"
    >
      <span class="agent-workflow-freshness-alert__detail">
        <span v-for="tool in staleWorkflowTools" :key="tool.workflowId">
          {{ tool.workflowName || tool.workflowKeySlug || tool.workflowId }}：当前 {{ formatWorkflowVersionLabel(tool.pinnedWorkflowVersion) }} · 最新 {{ formatWorkflowVersionLabel(tool.latestWorkflowVersion) }}
        </span>
      </span>
      <span class="agent-workflow-freshness-alert__detail">
        需要重新发布 Agent 才会用到新版本。点击「发布配置」会同时发布草稿并改用最新 Workflow 版本；
        「更新并发布」只更新 Workflow 版本，其余线上配置保持不变，未发布的草稿不受影响。
      </span>
      <template v-if="canPublishAgent" #actions>
        <LiquidGlassButton
          size="small"
          type="warning"
          :loading="publishingLatestWorkflows"
          :disabled="publishing"
          @click="handlePublishLatestWorkflows"
        >
          更新并发布
        </LiquidGlassButton>
      </template>
    </LiquidGlassNotice>

    <LiquidGlassNotice
      v-if="!isNew && isDevelopmentPolicy"
      class="agent-workflow-freshness-alert"
      type="warning"
      title="这个 Agent 使用研发模式：运行时不检查租户和权限角色"
    >
      给业务用户使用前，请在「高级运行设置」里改为标准策略，保存后先调试草稿再发布。
      <template v-if="canWriteAgent" #actions>
        <LiquidGlassButton size="small" type="warning" @click="openPolicySettings">打开高级运行设置</LiquidGlassButton>
      </template>
    </LiquidGlassNotice>

    <AgentDetailOverview
      v-if="!isNew && activeSection === 'overview'"
      class="agent-overview-scroll"
      :model-name="selectedModelLabel"
      :system-prompt="supervisor.systemPrompt"
      :workflow-tools="generalWorkflowTools"
      :knowledge-tools="knowledgeTools"
      :agent-id="agentId"
      :can-read-runs="canReadRuns"
      :can-evaluate="canEvaluateAgent"
      :remote-agents="remoteAgentBindings"
      :skills="skillBindings"
      :active-version-no="activeConfig?.versionNo"
      :draft-version-no="draftConfig?.versionNo"
      :project-label="projectSummaryLabel"
      :allowed-roles="form.allowedRoles || []"
      :can-debug="canDebugAgent"
      :can-write="canWriteAgent"
      @edit-section="showConfigSection"
      @edit-prompt="openSystemPromptEditor"
      @advanced="advancedSettingsVisible = true"
      @add-workflow="openWorkflowPicker"
      @add-remote="openA2aBindingDialog"
      @add-skill="openSkillPicker()"
      @debug="openDebug"
      @show-versions="showVersionSection"
      @add-knowledge="openKnowledgeFromOverview"
      @open-run="openRunRecords"
      @open-runs="openRunRecords()"
      @open-eval="openAgentEval"
    />

    <AgentEmbedPanel
      v-if="!isNew && canReadEmbed && activeSection === 'embed'"
      class="agent-overview-scroll"
      :agent-id="agentId"
      :agent-key-slug="form.keySlug"
      :can-manage="canManageEmbed"
    />

    <AgentVersionsPanel v-if="!isNew && activeSection === 'versions'" />

    <el-form
      v-show="isNew || activeSection === 'config'"
      ref="formRef"
      class="agent-config-form"
      :model="form"
      :rules="rules"
      label-position="top"
      v-loading="pageLoading"
    >
      <main class="workbench-layout" :class="{ 'has-section-nav': !isNew }">
        <AgentConfigNav />
        <section class="agent-workbench__main">
          <div class="agent-foundation-grid" :class="{ 'is-single': !isNew }">
            <AgentBasicSection />

            <AgentDecisionSection />
          </div>

        <AgentWorkflowSection />

        <AgentKnowledgeSection />

        <AgentCollaborationSection />

        <AgentSkillSection />

        <AgentMemorySection />

        </section>

      </main>

      <AgentAdvancedDrawer />
    </el-form>

  </WorkbenchPage>
</template>

<script setup lang="ts">
import { Cpu, EditPen, TopRight, VideoPlay } from '@element-plus/icons-vue'
import AgentDetailOverview from '@/components/agent/AgentDetailOverview.vue'
import AgentEmbedPanel from '@/components/agent/AgentEmbedPanel.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import { LiquidGlassButton, LiquidGlassNav, LiquidGlassNotice } from '@/components/common/liquid-glass'
import AgentVersionsPanel from './edit/AgentVersionsPanel.vue'
import AgentConfigNav from './edit/AgentConfigNav.vue'
import AgentBasicSection from './edit/AgentBasicSection.vue'
import AgentDecisionSection from './edit/AgentDecisionSection.vue'
import AgentWorkflowSection from './edit/AgentWorkflowSection.vue'
import AgentKnowledgeSection from './edit/AgentKnowledgeSection.vue'
import AgentCollaborationSection from './edit/AgentCollaborationSection.vue'
import AgentSkillSection from './edit/AgentSkillSection.vue'
import AgentMemorySection from './edit/AgentMemorySection.vue'
import AgentAdvancedDrawer from './edit/AgentAdvancedDrawer.vue'
import { provideAgentEditor, useAgentEditor } from './edit/useAgentEditor'

const editor = useAgentEditor()
provideAgentEditor(editor)
const {
  advancedSettingsVisible,
  agentId,
  isNew,
  activeSection,
  formRef,
  pageLoading,
  saving,
  publishing,
  publishingLatestWorkflows,
  workflowFreshness,
  workflowTools,
  remoteAgentBindings,
  skillBindings,
  supervisor,
  canWriteAgent,
  canDebugAgent,
  canPublishAgent,
  canReadEmbed,
  canManageEmbed,
  openSystemPromptEditor,
  isDevelopmentPolicy,
  openPolicySettings,
  form,
  rules,
  activeConfig,
  draftConfig,
  staleWorkflowTools,
  handlePublishLatestWorkflows,
  selectedModelLabel,
  projectSummaryLabel,
  showConfigSection,
  showVersionSection,
  openWorkflowPicker,
  openA2aBindingDialog,
  openSkillPicker,
  handleSave,
  handleSaveAndDebug,
  handlePublish,
  openDebug,
  formatWorkflowVersionLabel,
  generalWorkflowTools,
  knowledgeTools,
  canReadRuns,
  canEvaluateAgent,
  openRunRecords,
  openAgentEval,
  openKnowledgeFromOverview,
} = editor
</script>

<style scoped lang="scss">
.agent-edit-page {
  height: 100%;
  max-height: 100%;
  min-height: 0;
  overflow: hidden;
}

.agent-edit-page :deep(.app-page-header) {
  flex: 0 0 auto;
}

.agent-entity-mark {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-size: 28px;
}

.agent-detail-tabs {
  flex: 0 0 auto;
  align-self: flex-start;
}

.agent-detail-tabs button.is-link {
  color: var(--text-muted);
}

.agent-detail-tabs button.is-link .el-icon {
  font-size: 12px;
}

.agent-overview-scroll,
.agent-version-section {
  min-height: 0;
  flex: 1 1 auto;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 1px 2px 2px 1px;
  scrollbar-gutter: stable;
}

.agent-config-form {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  overflow: hidden;
}

.workbench-layout {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--section-gap);
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 2px;
  scrollbar-gutter: stable;
}

.workbench-layout.has-section-nav {
  grid-template-columns: 220px minmax(0, 1fr);
  align-items: start;
}

.agent-workbench__main,
.form-grid {
  min-width: 0;
}

.agent-workbench__main {
  display: grid;
  gap: var(--section-gap);
}

.agent-foundation-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(0, 0.92fr);
  gap: 12px;
  align-items: start;
}

.agent-foundation-grid.is-single {
  grid-template-columns: minmax(0, 1fr);
}

.agent-foundation-grid :deep(.agent-core-panel) {
  min-height: 0;
}

.agent-workbench__main :deep(.workbench-panel__description) {
  max-width: 52ch;
}

.agent-config-form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  color: var(--text-secondary);
  font-weight: 650;
  line-height: 20px;
}

.agent-config-form :deep(.el-input__wrapper),
.agent-config-form :deep(.el-select__wrapper),
.agent-config-form :deep(.el-textarea__inner) {
  border-radius: 8px;
}

.config-asset-card__actions .el-button {
  padding: 6px;
}

.agent-workflow-freshness-alert {
  flex: 0 0 auto;
}

.agent-workflow-freshness-alert__detail {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 2px 0 4px;
  font-size: 12px;
}

@media (max-width: 1280px) {
  .agent-foundation-grid {
      grid-template-columns: minmax(0, 1fr);
    }

  .workbench-layout.has-section-nav {
      grid-template-columns: 196px minmax(0, 1fr);
    }
}

@media (max-width: 960px) {
  .workbench-layout.has-section-nav {
      grid-template-columns: minmax(0, 1fr);
    }

  .workflow-tool-zero-state .el-button {
      width: 100%;
    }
}
</style>

<template>
  <WorkbenchPage class="agent-edit-page" density="compact" full-height>
    <PageHeader
      :variant="isNew ? 'standard' : 'entity'"
      domain="agent"
      :title="isNew ? '新建 Agent' : (form.name || agentId || 'Agent 配置')"
      eyebrow="Agent 配置"
      :description="isNew ? '创建一个能够理解需求并调用企业流程的 Agent。' : (form.description || '尚未填写职责说明')"
      density="compact"
      height-preset="emphasis"
      :show-back="!isNew"
      :artwork="false"
      @back="router.push('/agent')"
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
          :label="`线上版本 v${activeConfig.versionNo}`"
          tone="success"
        />
        <StatusTag v-if="!isNew && draftConfig" :label="`草稿 v${draftConfig.versionNo} 待发布`" tone="warning" />
      </template>
      <template #actions>
        <template v-if="isNew">
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
        </template>
        <template v-else-if="activeSection === 'config'">
          <el-button v-if="canDebugAgent" :icon="VideoPlay" @click="openDebug">调试</el-button>
          <el-button v-if="canWriteAgent" :loading="saving" @click="handleSave">保存草稿</el-button>
          <el-button v-if="canPublishAgent" type="primary" :loading="publishing" @click="handlePublish">发布配置</el-button>
        </template>
        <template v-else>
          <el-button v-if="canDebugAgent" :icon="VideoPlay" @click="openDebug">调试</el-button>
          <el-button v-if="canWriteAgent" type="primary" :icon="EditPen" @click="activeSection = 'config'">编辑配置</el-button>
        </template>
      </template>
    </PageHeader>

    <nav v-if="!isNew" class="agent-detail-tabs" aria-label="Agent 详情分区">
      <button type="button" :class="{ 'is-active': activeSection === 'overview' }" @click="activeSection = 'overview'">概览</button>
      <button type="button" :class="{ 'is-active': activeSection === 'config' }" @click="activeSection = 'config'">配置</button>
      <button type="button" :class="{ 'is-active': activeSection === 'versions' }" @click="showVersionSection">版本记录</button>
    </nav>

    <AgentDetailOverview
      v-if="!isNew && activeSection === 'overview'"
      class="agent-overview-scroll"
      :model-name="selectedModelLabel"
      :system-prompt="supervisor.systemPrompt"
      :workflow-tools="workflowTools"
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
    />

    <section v-if="!isNew && activeSection === 'versions'" class="agent-version-section">
      <WorkbenchPanel
        title="配置版本"
        description="查看当前线上版本和历史快照；历史版本可以复制为新的可编辑草稿。"
        density="compact"
      >
        <template #actions>
          <StatusTag v-if="activeConfig" :label="`线上 v${activeConfig.versionNo}`" tone="success" />
          <StatusTag v-if="draftConfig" :label="`草稿 v${draftConfig.versionNo}`" tone="warning" />
        </template>
        <el-table :data="configVersions" class="config-version-table agent-version-table">
          <el-table-column label="版本" width="90"><template #default="{ row }"><strong>v{{ row.versionNo }}</strong></template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="versionStatusType(row.status)">{{ configVersionStatusLabel(row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="运行时" prop="runtimeType" min-width="120" />
          <el-table-column label="Workflow" width="100"><template #default="{ row }">{{ row.tools?.length || 0 }} 个</template></el-table-column>
          <el-table-column label="Skill" width="100"><template #default="{ row }">{{ row.skills?.length || 0 }} 个</template></el-table-column>
          <el-table-column label="更新时间" min-width="168"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
          <el-table-column label="操作" width="168" fixed="right">
            <template #default="{ row }">
              <el-button link @click="viewVersionInConfig(row)">查看</el-button>
              <el-button v-if="row.status !== 'DRAFT' && canWriteAgent" link type="primary" @click="handleCopyVersion(row)">复制为草稿</el-button>
            </template>
          </el-table-column>
        </el-table>
      </WorkbenchPanel>
    </section>

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
        <nav v-if="!isNew" class="config-section-nav" aria-label="配置分区">
          <strong class="config-section-nav__title">配置</strong>
          <button type="button" :class="{ 'is-active': activeConfigSection === 'basic' }" @click="showConfigSection('basic')">
            <span class="config-section-nav__icon"><el-icon><Cpu /></el-icon></span>
            <span class="config-section-nav__label">基本信息</span>
            <span class="config-section-nav__count is-ready"><el-icon><Check /></el-icon></span>
          </button>
          <button type="button" :class="{ 'is-active': activeConfigSection === 'decision' }" @click="showConfigSection('decision')">
            <span class="config-section-nav__icon"><el-icon><ChatDotRound /></el-icon></span>
            <span class="config-section-nav__label">对话与决策</span>
            <span class="config-section-nav__count is-ready"><el-icon><Check /></el-icon></span>
          </button>
          <button type="button" :class="{ 'is-active': activeConfigSection === 'workflows' }" @click="showConfigSection('workflows')">
            <span class="config-section-nav__icon"><el-icon><Document /></el-icon></span>
            <span class="config-section-nav__label">可调用 Workflow</span>
            <span class="config-section-nav__count">{{ workflowTools.length }}</span>
          </button>
          <button type="button" :class="{ 'is-active': activeConfigSection === 'remote' }" @click="showConfigSection('remote')">
            <span class="config-section-nav__icon"><el-icon><Connection /></el-icon></span>
            <span class="config-section-nav__label">Agent 协作</span>
            <span class="config-section-nav__count">{{ remoteAgentBindings.length }}</span>
          </button>
          <button type="button" :class="{ 'is-active': activeConfigSection === 'skills' }" @click="showConfigSection('skills')">
            <span class="config-section-nav__icon"><el-icon><Guide /></el-icon></span>
            <span class="config-section-nav__label">Skill</span>
            <span class="config-section-nav__count">{{ skillBindings.length }}</span>
          </button>
          <div v-if="draftConfig" class="config-section-nav__footer">
            <span><strong>草稿 v{{ draftConfig.versionNo }}</strong><small>待发布</small></span>
            <button type="button" @click="showVersionSection">查看版本</button>
          </div>
        </nav>
        <section class="agent-workbench__main">
          <div class="agent-foundation-grid" :class="{ 'is-single': !isNew }">
            <WorkbenchPanel
              v-show="isNew || activeConfigSection === 'basic'"
              id="agent-config-basic"
              class="agent-core-panel"
              title="基本信息"
              description="说明这个 Agent 是谁、归属哪个项目，以及谁可以使用。"
              density="compact"
            >
              <template #actions>
                <el-switch
                  v-model="form.enabled"
                  inline-prompt
                  active-text="启"
                  inactive-text="停"
                />
              </template>

              <div class="form-grid two agent-identity-grid">
                <el-form-item label="名称" prop="name">
                  <el-input v-model="form.name" placeholder="如：合同审核助手" />
                </el-form-item>
                <el-form-item label="唯一标识" prop="keySlug">
                  <el-input v-model="form.keySlug" placeholder="如 contract-review（保存后不可修改）" :disabled="!isNew" />
                </el-form-item>
                <el-form-item label="所属项目">
                  <el-select
                    v-model="form.projectId"
                    clearable
                    filterable
                    placeholder="平台级 / 全局"
                    @change="handleProjectChange"
                  >
                    <el-option
                      v-for="project in writableScanProjects"
                      :key="project.id"
                      :label="projectOptionLabel(project)"
                      :value="project.id"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="允许使用的角色">
                  <el-select
                    v-model="form.allowedRoles"
                    multiple
                    filterable
                    allow-create
                    default-first-option
                    collapse-tags
                    collapse-tags-tooltip
                    placeholder="留空不限制"
                  />
                </el-form-item>
                <el-form-item label="描述" class="wide">
                  <el-input
                    v-model="form.description"
                    type="textarea"
                    :rows="2"
                    resize="none"
                    placeholder="一句话说明职责与适用场景"
                  />
                </el-form-item>
              </div>
            </WorkbenchPanel>

            <WorkbenchPanel
              v-show="isNew || activeConfigSection === 'decision'"
              id="agent-config-decision"
              class="agent-core-panel supervisor-panel"
              title="对话与决策"
              description="选择 Agent 使用的模型，并说明它应如何理解需求和作出判断。"
              density="compact"
            >
              <template #actions>
                <el-button plain :icon="Setting" @click="advancedSettingsVisible = true">高级设置</el-button>
              </template>

              <div class="supervisor-config-stack">
                <div class="supervisor-inline-row">
                  <span class="supervisor-inline-label">默认模型</span>
                  <el-select
                    v-model="supervisor.modelInstanceId"
                    clearable
                    filterable
                    class="supervisor-inline-control"
                    placeholder="发布前必选"
                    :loading="llmModelInstancesLoading"
                    @visible-change="handleModelSelectVisible"
                  >
                    <el-option
                      v-for="item in llmModelInstances"
                      :key="item.id"
                      :label="`${item.name} (${item.modelName})`"
                      :value="item.id"
                    />
                    <template #empty>
                      <ModelSelectEmptyState
                        model-type="LLM"
                        :option-count="llmModelInstances.length"
                        :loading="llmModelInstancesLoading"
                        :load-error="llmModelInstancesLoadError"
                        @retry="loadModelInstances"
                      />
                    </template>
                  </el-select>
                </div>

                <div class="supervisor-inline-row">
                  <span class="supervisor-inline-label">工作要求</span>
                  <button
                    type="button"
                    class="prompt-preview"
                    :class="{ 'is-empty': !systemPromptPreviewHasContent }"
                    @click="openSystemPromptEditor"
                  >
                    <span class="prompt-preview__text">{{ systemPromptPreview }}</span>
                    <el-icon class="prompt-preview__icon"><EditPen /></el-icon>
                  </button>
                </div>
              </div>

              <p class="supervisor-panel-note">
                超时、重新规划和调用限制可在「高级设置」中调整。
              </p>
            </WorkbenchPanel>
          </div>

        <WorkbenchPanel
          v-show="isNew || activeConfigSection === 'workflows'"
          id="agent-config-workflows"
          class="capabilities-panel"
          title="可调用 Workflow"
          density="compact"
        >
          <template #actions>
            <el-button type="primary" :icon="Plus" @click="openWorkflowPicker">
              添加 Workflow
            </el-button>
          </template>
          <div v-if="!workflowTools.length" class="capability-empty-state">
            <span class="capability-empty-state__icon"><el-icon><Document /></el-icon></span>
            <strong>暂未添加 Workflow</strong>
          </div>
          <div v-else class="config-asset-list">
            <article v-for="(row, index) in workflowTools" :key="row.workflowId" class="config-asset-card">
              <header class="config-asset-card__header">
                <span class="config-asset-card__drag" aria-hidden="true"><el-icon><Rank /></el-icon></span>
                <span class="config-asset-card__icon is-workflow"><el-icon><Document /></el-icon></span>
                <div class="config-asset-card__copy">
                  <div class="config-asset-card__title">
                    <strong>{{ row.workflowName || workflowById(row.workflowId)?.name || row.workflowId }}</strong>
                    <span class="asset-pill" :class="riskToneClass(row.riskLevel)">{{ riskLabel(row.riskLevel) }}</span>
                  </div>
                  <p>{{ workflowSummary(row) }}</p>
                </div>
                <div class="config-asset-card__actions">
                  <span>{{ row.enabled !== false ? '已启用' : '已停用' }}</span>
                  <el-switch v-model="row.enabled" size="small" :aria-label="`启用 ${row.workflowName || row.workflowId}`" />
                  <el-dropdown trigger="click">
                    <el-button link :icon="MoreFilled" aria-label="更多操作" />
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item :disabled="index === 0" @click="moveWorkflowTool(index, -1)">上移</el-dropdown-item>
                        <el-dropdown-item :disabled="index === workflowTools.length - 1" @click="moveWorkflowTool(index, 1)">下移</el-dropdown-item>
                        <el-dropdown-item divided @click="removeWorkflowTool(index)">移除</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </header>

              <div class="config-asset-card__meta">
                <div><small>版本</small><strong>{{ row.workflowVersion || '已发布' }}</strong></div>
                <div><small>操作范围</small><strong>{{ riskLabel(row.riskLevel) }}</strong></div>
                <div><small>调用权限</small><strong :class="{ 'is-ready': row.permissionKey }">{{ row.permissionKey ? '已配置' : '待配置' }}</strong></div>
              </div>

              <details class="config-asset-card__settings">
                <summary>调用设置</summary>
                <div class="tool-override-grid config-card-settings-grid">
                  <el-form-item label="调用标识">
                    <el-input v-model="row.toolName" placeholder="通常无需修改" />
                  </el-form-item>
                  <el-form-item label="操作类型">
                    <el-select v-model="row.riskLevel" @change="syncReadOnly(row)">
                      <el-option label="只查询" value="READ" />
                      <el-option label="会修改数据" value="WRITE" />
                      <el-option label="会操作页面" value="PAGE_ACTION" />
                      <el-option label="可能无法撤销" value="IRREVERSIBLE" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="所需权限" class="wide">
                    <el-input v-model="row.permissionKey" placeholder="通常无需修改" />
                  </el-form-item>
                  <el-form-item label="自定义说明" class="wide">
                    <el-input v-model="row.descriptionOverride" type="textarea" :rows="2" placeholder="留空沿用 Workflow 描述" />
                  </el-form-item>
                  <el-form-item label="自定义输入格式（Schema）">
                    <el-input v-model="row.inputSchemaOverrideJson" type="textarea" :rows="3" placeholder="留空使用默认设置" />
                  </el-form-item>
                  <el-form-item label="自定义输出格式（Schema）">
                    <el-input v-model="row.outputSchemaOverrideJson" type="textarea" :rows="3" placeholder="留空使用默认设置" />
                  </el-form-item>
                </div>
              </details>
            </article>
          </div>
        </WorkbenchPanel>

        <WorkbenchPanel
          v-show="isNew || activeConfigSection === 'remote'"
          id="agent-config-remote"
          class="a2a-bindings-panel"
          title="Agent 协作"
          density="compact"
        >
          <template #actions>
            <el-button type="primary" :icon="Plus" :disabled="isNew" @click="openA2aBindingDialog">
              添加 Agent
            </el-button>
          </template>
          <el-alert
            v-if="isNew"
            type="info"
            :closable="false"
            show-icon
            title="请先保存当前 Agent，再添加协作 Agent。"
          />
          <div v-else-if="!remoteAgentBindings.length" class="capability-empty-state">
            <span class="capability-empty-state__icon is-collaboration"><el-icon><Connection /></el-icon></span>
            <strong>暂未添加 Agent</strong>
          </div>
          <div v-else class="config-asset-list">
            <article v-for="(row, index) in remoteAgentBindings" :key="row.remoteAgentRevisionId" class="config-asset-card is-collaboration">
              <header class="config-asset-card__header">
                <span class="config-asset-card__icon is-collaboration"><el-icon><Connection /></el-icon></span>
                <div class="config-asset-card__copy">
                  <div class="config-asset-card__title"><strong>{{ row.remoteAgentKey || `Agent #${row.remoteAgentId}` }}</strong></div>
                  <p>{{ row.description || '用于完成已授权的协作任务' }}</p>
                  <div class="config-asset-card__tags">
                    <span class="asset-pill is-info">外部 A2A</span>
                    <span class="asset-pill" :class="riskToneClass(row.riskLevel)">{{ riskLabel(row.riskLevel) }}</span>
                    <span class="asset-pill">最长等待 {{ Math.round((row.timeoutMs || 0) / 1000) }} 秒</span>
                  </div>
                </div>
                <div class="config-asset-card__actions">
                  <el-switch v-model="row.enabled" size="small" :aria-label="`启用 ${row.remoteAgentKey || '协作 Agent'}`" />
                  <el-dropdown trigger="click">
                    <el-button link :icon="MoreFilled" aria-label="更多操作" />
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item :disabled="index === 0" @click="moveA2aBinding(index, -1)">上移</el-dropdown-item>
                        <el-dropdown-item :disabled="index === remoteAgentBindings.length - 1" @click="moveA2aBinding(index, 1)">下移</el-dropdown-item>
                        <el-dropdown-item divided @click="removeA2aBinding(index)">移除</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </header>
              <details class="config-asset-card__settings">
                <summary>协作设置</summary>
                <div class="tool-override-grid config-card-settings-grid">
                  <el-form-item label="调用标识"><el-input v-model="row.toolName" placeholder="通常无需修改" /></el-form-item>
                  <el-form-item label="操作类型"><el-select v-model="row.riskLevel"><el-option label="只查询" value="READ" /><el-option label="会修改数据" value="WRITE" /><el-option label="可能无法撤销" value="IRREVERSIBLE" /></el-select></el-form-item>
                  <el-form-item label="所需权限" class="wide"><el-input v-model="row.permissionKey" placeholder="通常无需修改" /></el-form-item>
                  <el-form-item label="可委派任务" class="wide"><div class="config-asset-task-list"><el-tag v-for="skillId in row.allowedSkillIds" :key="skillId" size="small">{{ skillId }}</el-tag></div></el-form-item>
                </div>
              </details>
            </article>
          </div>
        </WorkbenchPanel>

        <WorkbenchPanel
          v-show="isNew || activeConfigSection === 'skills'"
          id="agent-config-skills"
          class="skills-panel"
          title="Skill"
          density="compact"
        >
          <template #actions>
            <el-button v-if="canBindSkills" type="primary" :icon="Plus" @click="openSkillPicker()">
              添加 Skill
            </el-button>
          </template>

          <div v-if="!skillBindings.length" class="capability-empty-state">
            <span class="capability-empty-state__icon is-skill"><el-icon><Guide /></el-icon></span>
            <strong>暂未添加 Skill</strong>
          </div>

          <el-table
            v-else
            :data="skillBindings"
            row-key="skillVersionId"
            class="skill-binding-table"
            size="small"
          >
            <el-table-column label="Skill" min-width="220">
              <template #default="{ row }">
                <div class="skill-binding-identity">
                  <strong>{{ row.displayName || row.name }}</strong>
                  <code>{{ row.publisher }}/{{ row.name }}</code>
                  <el-tag :type="row.publisher === 'reachai' ? 'success' : 'warning'" size="small">
                    {{ row.publisher === 'reachai' ? '内置可信' : '发布者未验证' }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="使用版本" width="128">
              <template #default="{ row }">
                <div class="skill-binding-version">
                  <code>{{ row.version }}</code>
                  <el-tag v-if="row.hasScripts" type="warning" size="small">含脚本</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="使用方式" min-width="168">
              <template #default="{ row }">
                <el-select v-model="row.activationMode" size="small" :disabled="!canBindSkills">
                  <el-option label="需要时自动使用" value="MODEL_SELECTED" />
                  <el-option label="每次都使用" value="ALWAYS" />
                  <el-option label="仅明确要求时使用（暂未开放）" value="EXPLICIT" disabled />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="脚本状态" width="112">
              <template #default="{ row }">
                <el-tooltip
                  :content="row.hasScripts ? '当前版本只加载说明和资源，脚本暂不执行。' : '该版本不含脚本。'"
                  placement="top"
                >
                  <el-tag :type="row.hasScripts ? 'warning' : 'info'" size="small">
                    {{ row.hasScripts ? '脚本不执行' : '不含脚本' }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="必须加载" width="84" align="center">
              <template #default="{ row }">
                <el-tooltip content="开启后，如果这个 Skill 无法加载，本次 Agent 运行会直接停止。" placement="top">
                  <el-switch v-model="row.required" size="small" :disabled="!canBindSkills" @change="handleSkillRequiredChange(row)" />
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="可用" width="68" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" size="small" :disabled="row.required || !canBindSkills" />
              </template>
            </el-table-column>
            <el-table-column v-if="canBindSkills" label="" width="176" align="right">
              <template #default="{ $index }">
                <div class="table-row-actions">
                  <el-button link @click="openSkillPicker($index)">更换版本</el-button>
                  <el-button link :icon="ArrowUp" :disabled="$index === 0" @click="moveSkillBinding($index, -1)" />
                  <el-button
                    link
                    :icon="ArrowDown"
                    :disabled="$index === skillBindings.length - 1"
                    @click="moveSkillBinding($index, 1)"
                  />
                  <el-button link type="danger" :icon="Delete" @click="removeSkillBinding($index)" />
                </div>
              </template>
            </el-table-column>
          </el-table>
        </WorkbenchPanel>

        <AppDialog
          v-model="a2aBindingDialogVisible"
          title="添加协作 Agent"
          width="min(720px, calc(100vw - 32px))"
          append-to-body
          destroy-on-close
          :close-on-click-modal="false"
        >
          <div class="a2a-dialog-shell" v-loading="a2aBindingLoading">
            <header class="a2a-dialog-intro">
              <span class="a2a-dialog-intro__icon"><el-icon><Connection /></el-icon></span>
              <div class="a2a-dialog-intro__copy">
                <small>AGENT COLLABORATION</small>
                <strong>把任务交给可信的外部 Agent</strong>
                <p>仅显示已经建立信任并通过评审的远程 Agent。</p>
              </div>
              <span class="a2a-dialog-intro__protocol">A2A</span>
            </header>

            <section v-if="!localAgentPrincipals.length" class="a2a-dialog-empty">
              <span class="a2a-dialog-empty__icon"><el-icon><Connection /></el-icon></span>
              <small class="a2a-dialog-empty__eyebrow">还差 1 项准备</small>
              <h3>为当前 Agent 创建协作身份</h3>
              <p>协作身份用于识别任务由谁发起，并套用对应的信任与权限策略。完成后再回来选择远程 Agent。</p>
              <ol class="a2a-setup-path" aria-label="添加协作 Agent 的步骤">
                <li class="is-current"><span>1</span><div><strong>创建协作身份</strong><small>当前待完成</small></div></li>
                <li><span>2</span><div><strong>选择远程 Agent</strong><small>从可信目录选择</small></div></li>
                <li><span>3</span><div><strong>确认委派范围</strong><small>添加到当前 Agent</small></div></li>
              </ol>
            </section>

            <section v-else-if="!trustedRemoteAgents.length" class="a2a-dialog-empty">
              <span class="a2a-dialog-empty__icon is-ready"><el-icon><Check /></el-icon></span>
              <small class="a2a-dialog-empty__eyebrow is-ready">协作身份已就绪</small>
              <h3>还没有可选择的可信 Agent</h3>
              <p>请先在 A2A 互联中心发现远程 Agent，完成评审并建立信任，再回到这里添加。</p>
              <ol class="a2a-setup-path" aria-label="添加协作 Agent 的步骤">
                <li class="is-done"><span>1</span><div><strong>创建协作身份</strong><small>已经完成</small></div></li>
                <li class="is-current"><span>2</span><div><strong>建立远程信任</strong><small>当前待完成</small></div></li>
                <li><span>3</span><div><strong>确认委派范围</strong><small>添加到当前 Agent</small></div></li>
              </ol>
            </section>

            <el-form v-else class="a2a-dialog-form" label-position="top">
              <div class="a2a-dialog-choice-grid">
                <section class="a2a-dialog-choice-card">
                  <div class="a2a-dialog-choice-card__heading">
                    <span>1</span>
                    <div><strong>选择发起身份</strong><small>代表当前 Agent 发起协作</small></div>
                  </div>
                  <el-select v-model="a2aPrincipalSelection" filterable placeholder="请选择协作身份">
                    <el-option v-for="principal in localAgentPrincipals" :key="principal.id" :label="`${principal.displayName} · ${principal.principalKey}`" :value="principal.id" />
                  </el-select>
                </section>

                <section class="a2a-dialog-choice-card">
                  <div class="a2a-dialog-choice-card__heading">
                    <span>2</span>
                    <div><strong>选择协作对象</strong><small>只列出已建立信任的 Agent</small></div>
                  </div>
                  <el-select v-model="a2aRemoteAgentSelection" filterable placeholder="请选择远程 Agent" @change="handleA2aRemoteAgentChanged">
                    <el-option v-for="remote in trustedRemoteAgents" :key="remote.id" :label="`${remote.displayName} · ${remote.remoteAgentKey}`" :value="remote.id" />
                  </el-select>
                </section>
              </div>

              <article v-if="selectedA2aRemoteAgent" class="a2a-dialog-agent-preview">
                <span class="a2a-dialog-agent-preview__avatar">{{ selectedA2aRemoteAgent.displayName.trim().slice(0, 1) || 'A' }}</span>
                <div class="a2a-dialog-agent-preview__copy">
                  <strong>{{ selectedA2aRemoteAgent.displayName }}</strong>
                  <span>{{ selectedA2aRemoteAgent.remoteAgentKey }} · {{ selectedA2aRemoteAgent.tenantScope || '全局范围' }}</span>
                  <p>{{ selectedA2aRemoteRevision?.description || selectedA2aRemoteAgent.lastHealthSummary || '已通过信任评审，可按授权范围接收委派任务。' }}</p>
                </div>
                <div class="a2a-dialog-agent-preview__tags">
                  <el-tag type="success" effect="plain" size="small">已信任</el-tag>
                  <el-tag :type="selectedA2aRemoteAgent.callable ? 'success' : 'warning'" effect="plain" size="small">
                    {{ selectedA2aRemoteAgent.callable ? '可调用' : '待就绪' }}
                  </el-tag>
                </div>
              </article>

              <section class="a2a-dialog-task-section">
                <div class="a2a-dialog-section-heading">
                  <div><strong>允许委派的任务</strong><small>只开放当前 Agent 真正需要使用的任务</small></div>
                  <span>{{ a2aBindingForm.allowedSkillIds.length }} 项</span>
                </div>
                <el-select
                  v-model="a2aBindingForm.allowedSkillIds"
                  multiple
                  filterable
                  collapse-tags
                  :max-collapse-tags="2"
                  :disabled="!selectedA2aRemoteRevision"
                  :placeholder="selectedA2aRemoteAgent ? '请选择允许委派的任务' : '请先选择远程 Agent'"
                >
                  <el-option v-for="skill in selectedA2aRemoteRevision?.protocolSkills || []" :key="skill.id" :label="`${skill.name} · ${skill.id}`" :value="skill.id" />
                </el-select>
              </section>

              <details class="a2a-dialog-settings">
                <summary><span><strong>调用设置</strong><small>系统已自动生成，通常无需修改</small></span></summary>
                <div class="a2a-dialog-settings__grid">
                  <el-form-item label="调用标识"><el-input v-model="a2aBindingForm.toolName" placeholder="通常无需修改" /></el-form-item>
                  <el-form-item label="所需权限"><el-input v-model="a2aBindingForm.permissionKey" placeholder="通常无需修改" /></el-form-item>
                  <el-form-item label="操作类型"><el-select v-model="a2aBindingForm.riskLevel"><el-option label="只查询" value="READ" /><el-option label="会修改数据" value="WRITE" /><el-option label="可能无法撤销" value="IRREVERSIBLE" /></el-select></el-form-item>
                  <el-form-item label="最长等待（秒）"><el-input-number v-model="a2aTimeoutSeconds" :min="1" :max="600" :step="1" /></el-form-item>
                </div>
              </details>
            </el-form>
          </div>
          <template #footer>
            <div class="a2a-dialog-footer">
              <span v-if="!localAgentPrincipals.length">完成设置后，再回来添加协作 Agent</span>
              <span v-else-if="!trustedRemoteAgents.length">建立信任后，再回来选择协作对象</span>
              <span v-else>添加后会进入当前配置草稿，保存后才会生效</span>
              <div class="a2a-dialog-footer__actions">
                <el-button @click="a2aBindingDialogVisible = false">取消</el-button>
                <el-button v-if="!localAgentPrincipals.length" type="primary" @click="openA2aTrustWorkspace">打开信任与策略</el-button>
                <el-button v-else-if="!trustedRemoteAgents.length" type="primary" @click="openA2aRemoteAgentCatalog">打开远程 Agent 目录</el-button>
                <el-button v-else type="primary" :disabled="!a2aBindingReady" @click="addA2aBinding">添加到当前 Agent</el-button>
              </div>
            </div>
          </template>
        </AppDialog>
        </section>

      </main>

      <AppDialog
        v-model="systemPromptDialogVisible"
        title="编辑 Agent 的工作要求"
        description="说明它的职责、判断规则、可调用范围和回答边界。"
        width="760px"
        append-to-body
        destroy-on-close
      >
        <el-input
          v-model="systemPromptDraft"
          type="textarea"
          :rows="18"
          class="system-prompt-editor"
          placeholder="例如：你是企业业务助手。先理解用户要解决的问题，再选择合适的 Workflow；没有可用流程时，直接说明限制。"
        />
        <template #footer>
          <el-button @click="systemPromptDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="applySystemPromptDraft">保存工作要求</el-button>
        </template>
      </AppDialog>

      <AppDialog
        v-model="workflowPickerVisible"
        title="添加可调用 Workflow"
        width="900px"
        append-to-body
        destroy-on-close
      >
        <div class="workflow-picker-intro">
          <div>
            <strong>选择已发布的 Workflow</strong>
            <p>添加后，请确认操作类型和所需权限；调用标识通常不用修改。</p>
          </div>
          <span>{{ workflowPickerTotal }} 个可用</span>
        </div>
        <div class="workflow-picker-searchbar">
          <el-input
            v-model="workflowPickerKeyword"
            clearable
            :prefix-icon="Search"
            placeholder="搜索名称、唯一标识、用途、项目或类型"
            @input="scheduleWorkflowPickerSearch"
          />
          <div class="workflow-picker-search-summary">
            <span>{{ workflowPickerTotal }} 个结果</span>
            <i />
            <strong>已选 {{ workflowPickerSelection.length }}</strong>
          </div>
        </div>
        <div class="workflow-picker-results" v-loading="workflowPickerLoading">
          <div v-if="workflowPickerRows.length" class="workflow-picker-list">
            <div
              v-for="workflow in workflowPickerRows"
              :key="workflow.id"
              class="workflow-picker-item"
              :class="{ 'is-selected': workflowPickerSelection.includes(workflow.id) }"
              role="checkbox"
              tabindex="0"
              :aria-checked="workflowPickerSelection.includes(workflow.id)"
              @click="toggleWorkflowPickerRow(workflow.id)"
              @keydown.enter.prevent="toggleWorkflowPickerRow(workflow.id)"
              @keydown.space.prevent="toggleWorkflowPickerRow(workflow.id)"
            >
              <el-checkbox
                class="workflow-picker-checkbox"
                :model-value="workflowPickerSelection.includes(workflow.id)"
                @click.stop
                @change="toggleWorkflowPickerRow(workflow.id, Boolean($event))"
              />
              <div
                class="workflow-avatar"
                :class="workflowAvatarClass(workflow.workflowKind, workflow.definitionAuthority)"
              >
                {{ workflowInitial(workflow.name) }}
              </div>
              <div class="workflow-picker-copy">
                <div class="workflow-picker-name-line">
                  <strong>{{ workflow.name }}</strong>
                  <el-tag :type="workflowKindTagType(workflow.workflowKind)" effect="light" size="small">
                    {{ formatWorkflowKindLabel(workflow.workflowKind) }}
                  </el-tag>
                </div>
                <span>{{ workflow.keySlug }}</span>
                <p :title="workflow.description || '暂无用途说明'">
                  {{ workflow.description || '暂无用途说明' }}
                </p>
              </div>
              <div class="workflow-picker-project" :title="workflow.projectCode || '平台级 Workflow'">
                <span>所属项目</span>
                <strong>{{ workflow.projectCode || '平台级' }}</strong>
              </div>
              <div v-if="workflowPickerSelection.includes(workflow.id)" class="workflow-picker-selected-mark">
                <el-icon><Check /></el-icon>
              </div>
            </div>
          </div>
          <el-empty
            v-else-if="!workflowPickerLoading"
            class="workflow-picker-no-results"
            description="没有匹配的已发布 Workflow"
            :image-size="72"
          />
        </div>
        <div v-if="workflowPickerTotal" class="workflow-picker-pagination">
          <el-pagination
            v-model:current-page="workflowPickerPage"
            background
            layout="total, prev, pager, next"
            :page-size="workflowPickerPageSize"
            :total="workflowPickerTotal"
            @current-change="loadWorkflowPickerPage"
          />
        </div>
        <template #footer>
          <el-button @click="workflowPickerVisible = false">取消</el-button>
          <el-button type="primary" @click="applyWorkflowPickerSelection">
            添加所选 Workflow（{{ workflowPickerSelection.length }}）
          </el-button>
        </template>
      </AppDialog>

      <AppDialog
        v-model="skillPickerVisible"
        :title="skillReplaceTargetIndex == null ? '添加 Skill' : '更换 Skill 版本'"
        description="只显示已经评审并发布的版本。保存后将固定使用所选版本，不会自动升级。"
        width="920px"
        append-to-body
        destroy-on-close
      >
        <div class="skill-picker-toolbar">
          <el-input
            v-model="skillPickerKeyword"
            clearable
            :prefix-icon="Search"
            placeholder="搜索名称、发布方或用途"
            @keyup.enter="loadSkillPicker"
            @clear="loadSkillPicker"
          />
          <el-button :icon="Search" :loading="skillPickerLoading" @click="loadSkillPicker">搜索</el-button>
        </div>
        <el-alert
          v-if="skillReplaceTargetIndex != null"
          type="info"
          :closable="false"
          title="更换时只能选择同一个 Skill 的其他版本；如需更换来源，请先移除原有 Skill。"
        />
        <el-table
          v-loading="skillPickerLoading"
          :data="skillPickerRows"
          row-key="rowKey"
          class="skill-picker-table"
          max-height="430"
        >
          <el-table-column label="Skill" min-width="230">
            <template #default="{ row }">
              <div class="skill-binding-identity">
                <strong>{{ row.skill.displayName || row.skill.name }}</strong>
                <code>{{ row.skill.publisher }}/{{ row.skill.name }}</code>
                <el-tag :type="row.skill.publisher === 'reachai' ? 'success' : 'warning'" size="small">
                  {{ row.skill.publisher === 'reachai' ? '内置可信' : '发布者未验证' }}
                </el-tag>
                <small>{{ row.skill.description || '未提供说明' }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="130">
            <template #default="{ row }"><code>{{ row.version.version }}</code></template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{ skillSourceLabel(row.version.sourceType) }}</template>
          </el-table-column>
          <el-table-column label="内容类型" min-width="168">
            <template #default="{ row }">
              <el-tag v-if="row.version.hasScripts" type="warning" size="small">包含脚本，当前不执行</el-tag>
              <el-tag v-else type="success" size="small">仅说明与资源</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="112" fixed="right" align="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="!canChooseSkillRow(row)"
                @click="chooseSkillRow(row)"
              >
                {{ canChooseSkillRow(row) ? (skillReplaceTargetIndex == null ? '添加' : '选用此版本') : '已添加' }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="没有可添加的已发布 Skill" :image-size="72" />
          </template>
        </el-table>
        <template #footer>
          <el-button @click="skillPickerVisible = false">关闭</el-button>
          <el-button @click="router.push('/skills')">打开 Skill 管理</el-button>
        </template>
      </AppDialog>

      <AppDrawer
        v-model="advancedSettingsVisible"
        title="高级运行设置"
        description="大多数 Agent 保持默认值即可。修改后会和当前配置一起保存。"
        size="720px"
        append-to-body
      >
        <div class="form-grid two">
          <el-form-item label="运行时">
            <el-input model-value="AgentScope Java 2.0.0 GA" disabled />
          </el-form-item>
          <el-form-item label="可调用 Workflow">
            <el-input model-value="仅限当前已添加的 Workflow" disabled />
          </el-form-item>
          <el-form-item label="权限与风险策略" class="wide">
            <el-select v-model="supervisor.policyProfile">
              <el-option label="研发模式（保留高风险保护）" value="DEV_ALLOW_ALL" />
              <el-option label="标准策略" value="STANDARD" />
              <el-option label="严格策略" value="STRICT" />
            </el-select>
          </el-form-item>
          <el-form-item label="最多执行步骤">
            <el-input-number v-model="supervisor.maxPlanSteps" :min="1" :max="20" />
          </el-form-item>
          <el-form-item label="最多调用 Workflow">
            <el-input-number v-model="supervisor.maxWorkflowCalls" :min="1" :max="20" />
          </el-form-item>
          <el-form-item label="最多重新规划">
            <el-input-number v-model="supervisor.maxReplans" :min="0" :max="10" />
          </el-form-item>
          <el-form-item label="总超时（秒）">
            <el-input-number v-model="totalTimeoutSeconds" :min="10" :max="600" />
          </el-form-item>
          <el-form-item label="Workflow 超时（秒）">
            <el-input-number v-model="workflowTimeoutSeconds" :min="5" :max="300" />
          </el-form-item>
          <el-form-item label="页面操作超时（秒）">
            <el-input-number v-model="pageBridgeTimeoutSeconds" :min="5" :max="120" />
          </el-form-item>
          <el-form-item label="可同时执行只读 Workflow" class="wide">
            <el-switch v-model="supervisor.parallelReadOnly" active-text="允许" inactive-text="串行" />
          </el-form-item>
        </div>
        <el-alert
          class="advanced-settings-note"
          :type="supervisor.policyProfile === 'DEV_ALLOW_ALL' ? 'warning' : 'success'"
          :closable="false"
          :title="policyProfileHint"
        />
        <el-alert
          class="advanced-settings-note"
          type="info"
          :closable="false"
          title="总超时是 Agent 完成整次任务的时间上限，应大于单个 Workflow 超时；等待用户确认的时间不计入页面操作超时。"
        />
        <el-form-item label="自定义策略（JSON）">
          <el-input
            v-model="entryConfigText"
            type="textarea"
            :rows="6"
            placeholder='策略 JSON，如 {"policy":{"allowedTenantIds":["default"],"permissionRoles":{"team:read":["operator"]},"irreversibleMode":"DENY"}}'
          />
        </el-form-item>
        <template #footer>
          <el-button type="primary" @click="advancedSettingsVisible = false">完成</el-button>
        </template>
      </AppDrawer>
    </el-form>

    <AppDrawer
      v-model="versionDrawerVisible"
      title="Agent 配置版本"
      description="“生效中”和“已归档”的版本不能直接修改；如需调整，请先复制为新的草稿。"
      size="680px"
    >
      <el-table :data="configVersions" class="config-version-table">
        <el-table-column label="版本" width="90">
          <template #default="{ row }">v{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag :type="versionStatusType(row.status)">{{ configVersionStatusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="运行时" prop="runtimeType" min-width="120" />
        <el-table-column label="Workflow" width="92">
          <template #default="{ row }">{{ row.tools?.length || 0 }}</template>
        </el-table-column>
        <el-table-column label="Skill" width="92">
          <template #default="{ row }">{{ row.skills?.length || 0 }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="168">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="168" fixed="right">
          <template #default="{ row }">
            <el-button link @click="loadConfigToForm(row)">查看</el-button>
            <el-button v-if="row.status !== 'DRAFT'" link type="primary" @click="handleCopyVersion(row)">复制为草稿</el-button>
          </template>
        </el-table-column>
      </el-table>
    </AppDrawer>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  ArrowDown,
  ArrowUp,
  ChatDotRound,
  Check,
  Connection,
  Cpu,
  Delete,
  Document,
  EditPen,
  Guide,
  MoreFilled,
  Plus,
  Rank,
  Search,
  Setting,
  VideoPlay,
} from '@element-plus/icons-vue'
import AgentDetailOverview from '@/components/agent/AgentDetailOverview.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import type {
  AgentConfigDraft,
  AgentConfigVersion,
  Agent,
  AgentA2aRemoteBindingConfig,
  AgentIdentityForm,
  AgentWorkflowToolConfig,
} from '@/types/agent'
import type { WorkflowWorkingCopy } from '@/types/workflow'
import type {
  AgentSkillBindingConfig,
  AgentSkillSummary,
  AgentSkillVersion,
} from '@/types/skill'
import {
  createAgent,
  copyAgentConfigToDraft,
  getAgent,
  listAgentConfigVersions,
  publishAgentConfig,
  saveAgentConfigDraft,
  searchWorkflows,
  updateAgent,
} from '@/api/workflow'
import { listBindableAgentSkillVersions } from '@/api/skill'
import { getA2aRemoteAgent, listA2aPrincipals, listA2aRemoteAgents } from '@/api/a2aHub'
import type {
  A2aPrincipal,
  A2aRemoteAgent,
  A2aRemoteAgentDetail,
} from '@/types/a2aHub'
import { getScanProjects } from '@/api/scanProject'
import { getModelInstances } from '@/api/model'
import type { ScanProject } from '@/types/scanProject'
import type { ModelInstance } from '@/types/model'
import { useProjectStore } from '@/store/project'
import { formatWorkflowKindLabel } from '@/utils/workflowLabels'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'
import { platformSessionUser } from '@/auth/platformSession'
import {
  hasPlatformResourcePermission,
  PLATFORM_PERMISSION_AGENT_DEBUG,
  PLATFORM_PERMISSION_AGENT_PUBLISH,
  PLATFORM_PERMISSION_AGENT_WRITE,
} from '@/auth/platformAccess'

const route = useRoute()
const router = useRouter()
const advancedSettingsVisible = ref(false)
const systemPromptDialogVisible = ref(false)
const systemPromptDraft = ref('')
const workflowPickerVisible = ref(false)
const workflowPickerKeyword = ref('')
const workflowPickerSelection = ref<string[]>([])
const workflowPickerPage = ref(1)
const workflowPickerPageSize = ref(10)
const workflowPickerRows = ref<WorkflowWorkingCopy[]>([])
const workflowPickerTotal = ref(0)
const workflowPickerLoading = ref(false)
const projectStore = useProjectStore()
const agentId = route.params.id as string
const isNew = agentId === 'new'
type AgentPageSection = 'overview' | 'config' | 'versions'
type AgentConfigSection = 'basic' | 'decision' | 'workflows' | 'remote' | 'skills'
const initialSection: AgentPageSection = route.query.tab === 'config'
  ? 'config'
  : route.query.tab === 'versions'
    ? 'versions'
    : 'overview'
const activeSection = ref<AgentPageSection>(isNew ? 'config' : initialSection)
const activeConfigSection = ref<AgentConfigSection>('basic')

const formRef = ref<FormInstance>()
const pageLoading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const scanProjects = ref<ScanProject[]>([])
const llmModelInstances = ref<ModelInstance[]>([])
const llmModelInstancesLoading = ref(false)
const llmModelInstancesLoadError = ref(false)
const publishedWorkflows = ref<WorkflowWorkingCopy[]>([])
const workflowTools = ref<AgentWorkflowToolConfig[]>([])
const remoteAgentBindings = ref<AgentA2aRemoteBindingConfig[]>([])
const a2aBindingDialogVisible = ref(false)
const a2aBindingLoading = ref(false)
const trustedRemoteAgents = ref<A2aRemoteAgent[]>([])
const localAgentPrincipals = ref<A2aPrincipal[]>([])
const selectedRemoteAgentDetail = ref<A2aRemoteAgentDetail | null>(null)
const DEFAULT_A2A_TIMEOUT_MS = 120_000
const a2aBindingForm = reactive<AgentA2aRemoteBindingConfig>({
  principalId: 0,
  remoteAgentId: 0,
  remoteAgentRevisionId: 0,
  toolName: '',
  allowedSkillIds: [],
  riskLevel: 'READ',
  permissionKey: '',
  timeoutMs: DEFAULT_A2A_TIMEOUT_MS,
  enabled: true,
})
const a2aPrincipalSelection = computed<number | undefined>({
  get: () => a2aBindingForm.principalId || undefined,
  set: (value) => { a2aBindingForm.principalId = value || 0 },
})
const a2aRemoteAgentSelection = computed<number | undefined>({
  get: () => a2aBindingForm.remoteAgentId || undefined,
  set: (value) => { a2aBindingForm.remoteAgentId = value || 0 },
})
const a2aTimeoutSeconds = computed({
  get: () => Math.round((a2aBindingForm.timeoutMs ?? DEFAULT_A2A_TIMEOUT_MS) / 1000),
  set: (value: number) => {
    a2aBindingForm.timeoutMs = Number.isFinite(value) ? value * 1000 : DEFAULT_A2A_TIMEOUT_MS
  },
})
const selectedA2aRemoteAgent = computed(() => trustedRemoteAgents.value.find((item) =>
  item.id === a2aBindingForm.remoteAgentId,
))
const selectedA2aRemoteRevision = computed(() => selectedRemoteAgentDetail.value?.revisions.find((item) =>
  item.id === a2aBindingForm.remoteAgentRevisionId,
))
const a2aBindingReady = computed(() => Boolean(
  a2aBindingForm.principalId
  && a2aBindingForm.remoteAgentId
  && a2aBindingForm.remoteAgentRevisionId
  && a2aBindingForm.allowedSkillIds.length
  && a2aBindingForm.toolName?.trim()
  && a2aBindingForm.permissionKey?.trim(),
))
const skillBindings = ref<AgentSkillBindingConfig[]>([])
const skillPickerVisible = ref(false)
const skillPickerKeyword = ref('')
const skillPickerLoading = ref(false)
const skillPickerRows = ref<SkillPickerRow[]>([])
const skillReplaceTargetIndex = ref<number | null>(null)
const configVersions = ref<AgentConfigVersion[]>([])
const currentConfig = ref<AgentConfigVersion | null>(null)
const versionDrawerVisible = ref(false)
const entryConfigText = ref('{}')
const DEFAULT_TOTAL_TIMEOUT_MS = 300_000
const DEFAULT_WORKFLOW_TIMEOUT_MS = 180_000
const DEFAULT_PAGE_BRIDGE_TIMEOUT_MS = 30_000

interface SkillPickerRow {
  rowKey: string
  skill: AgentSkillSummary
  version: AgentSkillVersion
}

const supervisor = reactive({
  systemPrompt: '',
  modelInstanceId: '',
  maxPlanSteps: 6,
  maxWorkflowCalls: 4,
  maxReplans: 2,
  totalTimeoutMs: DEFAULT_TOTAL_TIMEOUT_MS,
  workflowTimeoutMs: DEFAULT_WORKFLOW_TIMEOUT_MS,
  pageBridgeTimeoutMs: DEFAULT_PAGE_BRIDGE_TIMEOUT_MS,
  parallelReadOnly: true,
  policyProfile: 'DEV_ALLOW_ALL',
})

const totalTimeoutSeconds = computed({
  get: () => Math.round(supervisor.totalTimeoutMs / 1000),
  set: (value: number) => { supervisor.totalTimeoutMs = value * 1000 },
})
const workflowTimeoutSeconds = computed({
  get: () => Math.round(supervisor.workflowTimeoutMs / 1000),
  set: (value: number) => { supervisor.workflowTimeoutMs = value * 1000 },
})
const pageBridgeTimeoutSeconds = computed({
  get: () => Math.round(supervisor.pageBridgeTimeoutMs / 1000),
  set: (value: number) => { supervisor.pageBridgeTimeoutMs = value * 1000 },
})
const writableScanProjects = computed(() => scanProjects.value.filter((project) => (
  hasPlatformResourcePermission(
    platformSessionUser.value?.permissionGrants,
    PLATFORM_PERMISSION_AGENT_WRITE,
    'PROJECT',
    null,
    project.projectCode,
  )
)))
const canWriteAgent = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_AGENT_WRITE,
  'PROJECT',
  null,
  form.projectCode,
))
const canDebugAgent = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_AGENT_DEBUG,
  'PROJECT',
  null,
  form.projectCode,
))
const canPublishAgent = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_AGENT_PUBLISH,
  'PROJECT',
  null,
  form.projectCode,
))
const canBindSkills = computed(() => {
  const permissions = platformSessionUser.value?.permissions || []
  return permissions.includes('*') || permissions.includes('skill:bind')
})
const systemPromptPreviewHasContent = computed(() => Boolean((supervisor.systemPrompt || '').trim()))
const systemPromptPreview = computed(() => {
  const text = (supervisor.systemPrompt || '').replace(/\s+/g, ' ').trim()
  return text
    ? `已填写工作要求（${text.length} 字），点击查看或修改`
    : '点击填写工作要求，说明职责、判断规则和回答边界'
})

function openSystemPromptEditor() {
  systemPromptDraft.value = supervisor.systemPrompt || ''
  systemPromptDialogVisible.value = true
}

function applySystemPromptDraft() {
  supervisor.systemPrompt = systemPromptDraft.value
  systemPromptDialogVisible.value = false
}
const policyProfileHint = computed(() => supervisor.policyProfile === 'DEV_ALLOW_ALL'
  ? '研发模式会放宽租户与角色映射检查，但页面操作仍需用户明确要求，修改操作仍需确认，不可撤销操作仍默认禁止。'
  : '运行前会检查项目、租户、可使用角色、所需权限和操作类型，并记录审核信息。')
const selectedWorkflowIds = computed<string[]>({
  get: () => workflowTools.value.map((tool) => tool.workflowId),
  set: (workflowIds) => syncWorkflowSelection(workflowIds),
})

let workflowPickerSearchTimer: ReturnType<typeof setTimeout> | null = null
let workflowPickerRequestSequence = 0

const form = reactive<AgentIdentityForm>({
  keySlug: '',
  name: '',
  description: '',
  projectId: null,
  projectCode: null,
  visibility: 'PROJECT',
  allowedRoles: [],
  enabled: true,
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入智能体名称', trigger: 'blur' }],
  keySlug: [{ required: true, message: '请输入唯一标识', trigger: 'blur' }],
}

const activeConfig = computed(() => configVersions.value.find((item) => item.status === 'ACTIVE') || null)
const draftConfig = computed(() => configVersions.value.find((item) => item.status === 'DRAFT') || null)
const selectedModelLabel = computed(() => {
  const modelId = supervisor.modelInstanceId
  if (!modelId) return '尚未配置'
  const model = llmModelInstances.value.find((item) => String(item.id) === String(modelId))
  return model?.name || model?.modelName || '已配置模型'
})
const projectSummaryLabel = computed(() => {
  if (form.projectId == null && !form.projectCode) return '平台级'
  const project = scanProjects.value.find((item) => item.id === form.projectId)
  return project?.name || form.projectCode || '平台级'
})
function showConfigSection(section: AgentConfigSection) {
  activeSection.value = 'config'
  activeConfigSection.value = section
}

async function showVersionSection() {
  activeSection.value = 'versions'
  await refreshConfigVersions()
}

function viewVersionInConfig(config: AgentConfigVersion) {
  loadConfigToForm(config)
  activeSection.value = 'config'
  activeConfigSection.value = 'decision'
}

function riskLabel(value?: string | null) {
  return ({
    READ: '只查询',
    WRITE: '会修改数据',
    PAGE_ACTION: '会操作页面',
    IRREVERSIBLE: '可能无法撤销',
  } as Record<string, string>)[value || ''] || value || '未标注'
}

function riskToneClass(value?: string | null) {
  if (value === 'READ') return 'is-read'
  if (value === 'PAGE_ACTION') return 'is-info'
  if (value === 'IRREVERSIBLE') return 'is-danger'
  return 'is-warning'
}

function workflowSummary(row: AgentWorkflowToolConfig) {
  return row.descriptionOverride
    || row.description
    || workflowById(row.workflowId)?.description
    || '按需执行已发布流程'
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

function parseAllowedRoles(json?: string | null): string[] {
  if (!json?.trim()) return []
  try {
    const parsed = JSON.parse(json)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

function entryToForm(entry: Agent): void {
  Object.assign(form, {
    keySlug: entry.keySlug || '',
    name: entry.name || '',
    description: entry.description || '',
    projectId: entry.projectId ?? null,
    projectCode: entry.projectCode ?? null,
    visibility: entry.visibility || 'PROJECT',
    allowedRoles: parseAllowedRoles(entry.allowedRolesJson),
    enabled: entry.enabled !== false,
  })
}

function buildPayload(): Partial<Agent> {
  return {
    keySlug: form.keySlug?.trim(),
    name: form.name?.trim(),
    description: form.description || null,
    projectId: form.projectId ?? null,
    projectCode: form.projectCode ?? projectCodeById(form.projectId) ?? null,
    visibility: form.visibility || 'PROJECT',
    allowedRolesJson: form.allowedRoles?.length ? JSON.stringify(form.allowedRoles) : null,
    enabled: form.enabled !== false,
  }
}

function workflowToolName(workflow: WorkflowWorkingCopy): string {
  const raw = (workflow.keySlug || workflow.id || 'workflow').replace(/[^A-Za-z0-9_]/g, '_')
  const prefixed = /^[A-Za-z]/.test(raw) ? raw : `wf_${raw}`
  return prefixed.length >= 2 ? prefixed.slice(0, 128) : `wf_${prefixed}`
}

function workflowById(workflowId: string) {
  return publishedWorkflows.value.find((item) => item.id === workflowId)
}

function openWorkflowPicker() {
  if (workflowPickerSearchTimer) {
    clearTimeout(workflowPickerSearchTimer)
    workflowPickerSearchTimer = null
  }
  workflowPickerKeyword.value = ''
  workflowPickerSelection.value = [...selectedWorkflowIds.value]
  workflowPickerPage.value = 1
  workflowPickerRows.value = []
  workflowPickerTotal.value = 0
  workflowPickerVisible.value = true
  void loadWorkflowPickerPage()
}

function scheduleWorkflowPickerSearch() {
  workflowPickerPage.value = 1
  if (workflowPickerSearchTimer) clearTimeout(workflowPickerSearchTimer)
  workflowPickerSearchTimer = setTimeout(() => {
    workflowPickerSearchTimer = null
    void loadWorkflowPickerPage()
  }, 300)
}

function toggleWorkflowPickerRow(workflowId: string, selected?: boolean) {
  const next = new Set(workflowPickerSelection.value)
  const shouldSelect = selected ?? !next.has(workflowId)
  if (shouldSelect) next.add(workflowId)
  else next.delete(workflowId)
  workflowPickerSelection.value = [...next]
}

function applyWorkflowPickerSelection() {
  selectedWorkflowIds.value = [...workflowPickerSelection.value]
  workflowPickerVisible.value = false
}

function workflowInitial(value?: string | null) {
  return (value || 'W').trim().slice(0, 1).toUpperCase()
}

function workflowKindKey(value?: string | null) {
  return String(value || 'GENERAL').toUpperCase()
}

function workflowAvatarClass(kind?: string | null, authority?: string | null) {
  if (workflowKindKey(kind) === 'PAGE_ASSISTANT') return 'page-action'
  if (String(authority || '').toUpperCase() === 'SDK') return 'sdk-graph'
  return 'chat'
}

function workflowKindTagType(kind?: string | null) {
  if (workflowKindKey(kind) === 'PAGE_ASSISTANT') return 'success'
  return 'info'
}

function cachePublishedWorkflows(workflows: WorkflowWorkingCopy[]) {
  const cached = new Map(publishedWorkflows.value.map((workflow) => [workflow.id, workflow]))
  for (const workflow of workflows) cached.set(workflow.id, workflow)
  publishedWorkflows.value = [...cached.values()]
}

async function loadWorkflowPickerPage() {
  const requestSequence = ++workflowPickerRequestSequence
  workflowPickerLoading.value = true
  try {
    const { data } = await searchWorkflows({
      status: 'ACTIVE',
      keyword: workflowPickerKeyword.value.trim() || undefined,
      current: workflowPickerPage.value,
      size: workflowPickerPageSize.value,
    })
    if (requestSequence !== workflowPickerRequestSequence) return
    const rows = Array.isArray(data?.records) ? data.records : []
    workflowPickerRows.value = rows
    workflowPickerTotal.value = Math.max(Number(data?.total) || 0, 0)
    cachePublishedWorkflows(rows)

    const maxPage = Math.max(1, Math.ceil(workflowPickerTotal.value / workflowPickerPageSize.value))
    if (workflowPickerPage.value > maxPage) {
      workflowPickerPage.value = maxPage
      void loadWorkflowPickerPage()
    }
  } catch {
    if (requestSequence !== workflowPickerRequestSequence) return
    workflowPickerRows.value = []
    workflowPickerTotal.value = 0
    ElMessage.error('加载可选 Workflow 失败')
  } finally {
    if (requestSequence === workflowPickerRequestSequence) {
      workflowPickerLoading.value = false
    }
  }
}

function defaultWorkflowTool(workflow: WorkflowWorkingCopy): AgentWorkflowToolConfig {
  const pageAction = workflow.workflowKind === 'PAGE_ASSISTANT'
  return {
    workflowId: workflow.id,
    workflowKeySlug: workflow.keySlug,
    workflowName: workflow.name,
    toolName: workflowToolName(workflow),
    riskLevel: pageAction ? 'PAGE_ACTION' : 'READ',
    permissionKey: `workflow:${workflow.keySlug}`,
    readOnly: !pageAction,
    enabled: true,
    priority: workflowTools.value.length,
  }
}

function syncWorkflowSelection(workflowIds: string[]) {
  const current = new Map(workflowTools.value.map((tool) => [tool.workflowId, tool]))
  workflowTools.value = workflowIds.flatMap((workflowId, priority) => {
    const existing = current.get(workflowId)
    const workflow = workflowById(workflowId)
    if (!existing && !workflow) return []
    return [{ ...(existing || defaultWorkflowTool(workflow!)), priority }]
  })
}

function syncReadOnly(tool: AgentWorkflowToolConfig) {
  tool.readOnly = tool.riskLevel === 'READ'
}

function moveWorkflowTool(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= workflowTools.value.length) return
  const next = [...workflowTools.value]
  ;[next[index], next[target]] = [next[target], next[index]]
  workflowTools.value = next.map((tool, priority) => ({ ...tool, priority }))
}

function removeWorkflowTool(index: number) {
  workflowTools.value = workflowTools.value
    .filter((_, itemIndex) => itemIndex !== index)
    .map((tool, priority) => ({ ...tool, priority }))
}

function safeA2aToolName(remoteAgentKey: string) {
  const normalized = `delegate_${remoteAgentKey}`.replace(/[^A-Za-z0-9_]/g, '_')
  return /^[A-Za-z]/.test(normalized) ? normalized.slice(0, 128) : `a2a_${normalized}`.slice(0, 128)
}

function openA2aTrustWorkspace() {
  a2aBindingDialogVisible.value = false
  router.push('/a2a-hub/trust')
}

function openA2aRemoteAgentCatalog() {
  a2aBindingDialogVisible.value = false
  router.push('/a2a-hub/remote-agents')
}

async function openA2aBindingDialog() {
  if (isNew) {
    ElMessage.info('请先保存当前 Agent，再配置 Agent 协作')
    return
  }
  a2aBindingLoading.value = true
  try {
    const [remoteResult, principalResult] = await Promise.all([
      listA2aRemoteAgents({ status: 'TRUSTED', limit: 100 }),
      listA2aPrincipals({ status: 'ACTIVE', limit: 100 }),
    ])
    trustedRemoteAgents.value = remoteResult.data?.items ?? []
    localAgentPrincipals.value = (principalResult.data?.items ?? []).filter((principal) =>
      principal.principalType === 'LOCAL_AGENT' && principal.attributes?.runtimeAgentId === agentId,
    )
    selectedRemoteAgentDetail.value = null
    Object.assign(a2aBindingForm, {
      principalId: localAgentPrincipals.value[0]?.id ?? 0,
      remoteAgentId: 0,
      remoteAgentRevisionId: 0,
      toolName: '',
      allowedSkillIds: [],
      riskLevel: 'READ',
      permissionKey: '',
      timeoutMs: DEFAULT_A2A_TIMEOUT_MS,
      enabled: true,
    })
    a2aBindingDialogVisible.value = true
  } catch {
    ElMessage.error('加载可协作的远程 Agent 失败')
  } finally {
    a2aBindingLoading.value = false
  }
}

async function handleA2aRemoteAgentChanged(remoteAgentId: number) {
  selectedRemoteAgentDetail.value = null
  a2aBindingForm.remoteAgentRevisionId = 0
  a2aBindingForm.allowedSkillIds = []
  const remote = trustedRemoteAgents.value.find((item) => item.id === remoteAgentId)
  if (!remote?.currentRevisionId) return
  a2aBindingLoading.value = true
  try {
    const { data } = await getA2aRemoteAgent(remoteAgentId)
    selectedRemoteAgentDetail.value = data
    const revision = data.revisions.find((item) => item.id === remote.currentRevisionId
      && item.reviewStatus === 'APPROVED')
    if (!revision) throw new Error('该远程 Agent 当前没有已审核可用的版本')
    a2aBindingForm.remoteAgentRevisionId = revision.id
    a2aBindingForm.allowedSkillIds = revision.protocolSkills.map((skill) => skill.id)
    a2aBindingForm.toolName = safeA2aToolName(remote.remoteAgentKey)
    a2aBindingForm.permissionKey = `a2a:remote-agent:${remote.remoteAgentKey}:invoke`
  } catch {
    ElMessage.error('加载远程 Agent 可用版本失败')
  } finally {
    a2aBindingLoading.value = false
  }
}

function addA2aBinding() {
  if (!a2aBindingForm.principalId || !a2aBindingForm.remoteAgentId
    || !a2aBindingForm.remoteAgentRevisionId || !a2aBindingForm.allowedSkillIds.length) {
    ElMessage.warning('请选择当前 Agent 的协作身份、远程 Agent，以及至少一项可委派任务')
    return
  }
  if (!/^[A-Za-z][A-Za-z0-9_]{1,127}$/.test(a2aBindingForm.toolName || '')) {
    ElMessage.warning('调用标识格式不正确：需以英文字母开头，只能包含字母、数字和下划线')
    return
  }
  if (remoteAgentBindings.value.some((item) => item.remoteAgentRevisionId
    === a2aBindingForm.remoteAgentRevisionId)) {
    ElMessage.warning('这个远程 Agent 版本已添加')
    return
  }
  remoteAgentBindings.value.push({
    ...a2aBindingForm,
    allowedSkillIds: [...a2aBindingForm.allowedSkillIds],
    priority: remoteAgentBindings.value.length,
  })
  a2aBindingDialogVisible.value = false
}

function removeA2aBinding(index: number) {
  remoteAgentBindings.value = remoteAgentBindings.value
    .filter((_, itemIndex) => itemIndex !== index)
    .map((binding, priority) => ({ ...binding, priority }))
}

function moveA2aBinding(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= remoteAgentBindings.value.length) return
  const next = [...remoteAgentBindings.value]
  ;[next[index], next[target]] = [next[target], next[index]]
  remoteAgentBindings.value = next.map((binding, priority) => ({ ...binding, priority }))
}

function openSkillPicker(replaceIndex?: number) {
  if (!canBindSkills.value) {
    ElMessage.warning('当前账号只能查看，不能修改 Skill')
    return
  }
  const normalizedIndex = Number.isInteger(replaceIndex) ? Number(replaceIndex) : null
  skillReplaceTargetIndex.value = normalizedIndex
  const target = normalizedIndex == null ? null : skillBindings.value[normalizedIndex]
  skillPickerKeyword.value = target?.name || ''
  skillPickerRows.value = []
  skillPickerVisible.value = true
  void loadSkillPicker()
}

async function loadSkillPicker() {
  skillPickerLoading.value = true
  try {
    const { data } = await listBindableAgentSkillVersions({
      search: skillPickerKeyword.value.trim() || undefined,
      agentProjectCode: form.projectCode || projectCodeById(form.projectId) || undefined,
    })
    skillPickerRows.value = (Array.isArray(data) ? data : []).map(({ skill, version }) => ({
      rowKey: `${skill.id}:${version.id}`,
      skill,
      version,
    }))
  } catch {
    skillPickerRows.value = []
    ElMessage.error('加载可添加的 Skill 失败')
  } finally {
    skillPickerLoading.value = false
  }
}

function canChooseSkillRow(row: SkillPickerRow) {
  const replaceIndex = skillReplaceTargetIndex.value
  if (replaceIndex != null) {
    const target = skillBindings.value[replaceIndex]
    return Boolean(target)
      && target.skillId === row.skill.id
      && target.skillVersionId !== row.version.id
  }
  return !skillBindings.value.some((binding) =>
    binding.publisher?.toLowerCase() === row.skill.publisher.toLowerCase()
      && binding.name === row.skill.name,
  )
}

function chooseSkillRow(row: SkillPickerRow) {
  if (!canChooseSkillRow(row)) return
  const replaceIndex = skillReplaceTargetIndex.value
  const previous = replaceIndex == null ? null : skillBindings.value[replaceIndex]
  const next: AgentSkillBindingConfig = {
    skillId: row.skill.id,
    skillVersionId: row.version.id,
    publisher: row.skill.publisher,
    name: row.skill.name,
    displayName: row.skill.displayName,
    version: row.version.version,
    sourceSha256: row.version.sourceSha256,
    hasScripts: row.version.hasScripts,
    activationMode: previous?.activationMode || 'MODEL_SELECTED',
    scriptPolicy: 'DENY',
    required: previous?.required || false,
    enabled: previous?.enabled ?? true,
    priority: replaceIndex == null ? skillBindings.value.length : replaceIndex,
  }
  if (replaceIndex == null) {
    skillBindings.value = [...skillBindings.value, next]
  } else {
    const bindings = [...skillBindings.value]
    bindings[replaceIndex] = next
    skillBindings.value = bindings
  }
  skillPickerVisible.value = false
  ElMessage.success(replaceIndex == null ? 'Skill 已添加到当前草稿' : 'Skill 版本已更换')
}

function handleSkillRequiredChange(binding: AgentSkillBindingConfig) {
  if (binding.required) binding.enabled = true
}

function moveSkillBinding(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= skillBindings.value.length) return
  const next = [...skillBindings.value]
  ;[next[index], next[target]] = [next[target], next[index]]
  skillBindings.value = next.map((binding, priority) => ({ ...binding, priority }))
}

function removeSkillBinding(index: number) {
  skillBindings.value = skillBindings.value
    .filter((_, itemIndex) => itemIndex !== index)
    .map((binding, priority) => ({ ...binding, priority }))
}

function skillSourceLabel(value?: string | null) {
  return ({ UPLOAD: '上传', GIT: 'Git', MARKET: '市场', BUILTIN: '内置' } as Record<string, string>)[value || '']
    || value
    || '未知'
}

function validateJson(value: string, label: string) {
  if (!value.trim()) return
  try {
    JSON.parse(value)
  } catch {
    throw new Error(`${label} JSON 格式无效`)
  }
}

function buildConfigDraft(): AgentConfigDraft {
  validateJson(entryConfigText.value, '扩展配置')
  const toolNames = new Set<string>()
  for (const tool of workflowTools.value) {
    if (!/^[A-Za-z][A-Za-z0-9_]{1,127}$/.test(tool.toolName || '')) {
      throw new Error(`Workflow“${tool.workflowName || tool.workflowId}”的调用标识格式不正确`)
    }
    if (toolNames.has(tool.toolName)) throw new Error(`调用标识重复：${tool.toolName}`)
    toolNames.add(tool.toolName)
    validateJson(tool.inputSchemaOverrideJson || '', `${tool.toolName} 自定义输入格式`)
    validateJson(tool.outputSchemaOverrideJson || '', `${tool.toolName} 自定义输出格式`)
  }
  for (const binding of remoteAgentBindings.value) {
    if (!/^[A-Za-z][A-Za-z0-9_]{1,127}$/.test(binding.toolName || '')) {
      throw new Error(`远程 Agent“${binding.remoteAgentKey || binding.remoteAgentId}”的调用标识格式不正确`)
    }
    if (toolNames.has(binding.toolName)) throw new Error(`调用标识重复：${binding.toolName}`)
    toolNames.add(binding.toolName)
    if (!binding.allowedSkillIds.length) throw new Error(`${binding.toolName} 至少需要选择一项可委派任务`)
  }
  return {
    runtimeType: 'AGENTSCOPE',
    systemPrompt: supervisor.systemPrompt || null,
    modelInstanceId: supervisor.modelInstanceId || null,
    maxPlanSteps: supervisor.maxPlanSteps,
    maxWorkflowCalls: supervisor.maxWorkflowCalls,
    maxReplans: supervisor.maxReplans,
    totalTimeoutMs: supervisor.totalTimeoutMs,
    workflowTimeoutMs: supervisor.workflowTimeoutMs,
    pageBridgeTimeoutMs: supervisor.pageBridgeTimeoutMs,
    parallelReadOnly: supervisor.parallelReadOnly,
    policyProfile: supervisor.policyProfile,
    toolCatalogMode: 'ALLOW_LIST',
    configJson: entryConfigText.value.trim() || null,
    tools: workflowTools.value.map((tool, priority) => ({ ...tool, priority })),
    remoteAgents: remoteAgentBindings.value.map((binding, priority) => ({
      principalId: binding.principalId,
      remoteAgentId: binding.remoteAgentId,
      remoteAgentRevisionId: binding.remoteAgentRevisionId,
      remoteAgentKey: binding.remoteAgentKey,
      toolName: binding.toolName,
      allowedSkillIds: [...binding.allowedSkillIds],
      riskLevel: binding.riskLevel,
      permissionKey: binding.permissionKey,
      timeoutMs: binding.timeoutMs,
      enabled: binding.enabled !== false,
      priority,
    })),
    skills: skillBindings.value.map((binding, priority) => ({
      skillId: binding.skillId,
      skillVersionId: binding.skillVersionId,
      activationMode: binding.activationMode || 'MODEL_SELECTED',
      scriptPolicy: 'DENY',
      required: Boolean(binding.required),
      enabled: binding.enabled !== false,
      priority,
    })),
  }
}

async function handleProjectChange(projectId: number | null | undefined) {
  form.projectCode = projectCodeById(projectId)
}

async function handleSave() {
  if (!canWriteAgent.value) {
    ElMessage.warning('当前账号没有该项目的 Agent 写权限')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = buildPayload()
    if (isNew) {
      const { data } = await createAgent(payload)
      const { data: draft } = await saveAgentConfigDraft(data.id, buildConfigDraft())
      currentConfig.value = draft
      ElMessage.success('Agent 已创建，配置已保存为草稿')
      await router.push('/agent')
    } else {
      await updateAgent(agentId, payload)
      const { data } = await saveAgentConfigDraft(agentId, buildConfigDraft())
      currentConfig.value = data
      await refreshConfigVersions()
      activeSection.value = 'overview'
      ElMessage.success(`配置草稿 v${data.versionNo} 已保存，线上版本未改变`)
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : ''
    const response = (error as { response?: { data?: { message?: string; error?: string } } })?.response
    ElMessage.error(message || response?.data?.message || response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handlePublish() {
  if (!canPublishAgent.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || isNew) return
  publishing.value = true
  try {
    await ElMessageBox.confirm(
      '发布后，当前草稿会成为该 Agent 的生效配置，上一版本会自动归档。确认发布吗？',
      '发布 Agent 配置',
      { type: 'warning', confirmButtonText: '确认发布' },
    )
    await updateAgent(agentId, buildPayload())
    const { data: draft } = await saveAgentConfigDraft(agentId, buildConfigDraft())
    const { data: active } = await publishAgentConfig(agentId, draft.id, 'admin-ui')
    currentConfig.value = active
    await refreshConfigVersions()
    activeSection.value = 'overview'
    ElMessage.success(`Agent 配置 v${active.versionNo} 已发布`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      const message = error instanceof Error ? error.message : '发布失败'
      ElMessage.error(message)
    }
  } finally {
    publishing.value = false
  }
}

function openDebug() {
  if (!isNew && canDebugAgent.value) router.push(`/agent/${agentId}/debug`)
}

async function refreshConfigVersions() {
  if (isNew) return
  const { data } = await listAgentConfigVersions(agentId)
  configVersions.value = Array.isArray(data) ? data : []
}

function loadConfigToForm(config: AgentConfigVersion) {
  currentConfig.value = config
  Object.assign(supervisor, {
    systemPrompt: config.systemPrompt || '',
    modelInstanceId: config.modelInstanceId || '',
    maxPlanSteps: config.maxPlanSteps ?? 6,
    maxWorkflowCalls: config.maxWorkflowCalls ?? 4,
    maxReplans: config.maxReplans ?? 2,
    totalTimeoutMs: config.totalTimeoutMs ?? DEFAULT_TOTAL_TIMEOUT_MS,
    workflowTimeoutMs: config.workflowTimeoutMs ?? DEFAULT_WORKFLOW_TIMEOUT_MS,
    pageBridgeTimeoutMs: config.pageBridgeTimeoutMs ?? DEFAULT_PAGE_BRIDGE_TIMEOUT_MS,
    parallelReadOnly: config.parallelReadOnly !== false,
    policyProfile: config.policyProfile || 'DEV_ALLOW_ALL',
  })
  entryConfigText.value = config.configJson || '{}'
  workflowTools.value = (config.tools || []).map((tool, priority) => ({ ...tool, priority }))
  remoteAgentBindings.value = (config.remoteAgents || []).map((binding, priority) => ({
    ...binding,
    allowedSkillIds: [...(binding.allowedSkillIds || [])],
    enabled: binding.enabled !== false,
    priority,
  }))
  skillBindings.value = (config.skills || []).map((binding, priority) => ({
    ...binding,
    activationMode: binding.activationMode || 'MODEL_SELECTED',
    scriptPolicy: 'DENY',
    required: Boolean(binding.required),
    enabled: binding.enabled !== false,
    priority,
  }))
}

async function handleCopyVersion(config: AgentConfigVersion) {
  try {
    await ElMessageBox.confirm(
      `将 v${config.versionNo} 复制为新的可编辑草稿；现有草稿会被替换。确认继续？`,
      '复制配置版本',
      { type: 'warning' },
    )
    const { data } = await copyAgentConfigToDraft(agentId, config.id)
    loadConfigToForm(data)
    await refreshConfigVersions()
    versionDrawerVisible.value = false
    activeSection.value = 'config'
    activeConfigSection.value = 'decision'
    ElMessage.success(`已从 v${config.versionNo} 创建草稿 v${data.versionNo}`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('复制配置版本失败')
  }
}

function versionStatusType(status?: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

function configVersionStatusLabel(status?: string) {
  if (status === 'ACTIVE') return '生效中'
  if (status === 'DRAFT') return '草稿'
  if (status === 'ARCHIVED') return '已归档'
  return status || '未配置'
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? new Date(parsed).toLocaleString() : value
}

async function loadAgent() {
  if (isNew) return
  pageLoading.value = true
  try {
    const { data } = await getAgent(agentId)
    entryToForm(data)
    await loadAgentConfig()
  } catch {
    ElMessage.error('加载 Agent 失败')
  } finally {
    pageLoading.value = false
  }
}

async function loadAgentConfig() {
  if (isNew) return
  await refreshConfigVersions()
  const config = configVersions.value.find((item) => item.status === 'DRAFT')
    || configVersions.value.find((item) => item.status === 'ACTIVE')
    || configVersions.value[0]
  if (!config) return
  loadConfigToForm(config)
}

async function loadScanProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
    projectStore.projects = scanProjects.value
    if (isNew) {
      const queryProjectId = Number(route.query.projectId)
      form.projectId = Number.isFinite(queryProjectId) && queryProjectId > 0
        ? queryProjectId
        : projectStore.currentProjectId ?? null
      form.projectCode = projectCodeById(form.projectId)
    }
  } catch {
    scanProjects.value = []
  }
}

async function loadModelInstances() {
  llmModelInstancesLoading.value = true
  llmModelInstancesLoadError.value = false
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    llmModelInstances.value = normalizeActiveModelInstances(data, 'LLM')
  } catch {
    llmModelInstances.value = []
    llmModelInstancesLoadError.value = true
  } finally {
    llmModelInstancesLoading.value = false
  }
}

function handleModelSelectVisible(visible: boolean) {
  if (visible) void loadModelInstances()
}

onMounted(async () => {
  await Promise.all([loadScanProjects(), loadModelInstances()])
  await loadAgent()
})

onUnmounted(() => {
  if (workflowPickerSearchTimer) clearTimeout(workflowPickerSearchTimer)
})
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

.agent-edit-page :deep(.app-page-header--entity) {
  min-height: 136px;
  border: 1px solid var(--border-divider);
  box-shadow: var(--shadow-panel);
}

.agent-entity-mark {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-size: 28px;
}

.agent-header-meta {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-secondary);
  font-size: 12px;
}

.agent-header-meta + .agent-header-meta {
  margin-left: 2px;
  padding-left: 14px;
  border-left: 1px solid var(--border-divider);
}

.agent-header-meta strong {
  color: var(--text-primary);
  font-weight: 650;
}

.agent-detail-tabs {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 28px;
  min-height: 42px;
  padding: 0 4px;
  border-bottom: 1px solid var(--border-divider);
}

.agent-detail-tabs button {
  position: relative;
  align-self: stretch;
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  font-weight: 650;
  cursor: pointer;
  transition: color var(--motion-duration-fast) ease;
}

.agent-detail-tabs button::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  border-radius: 999px 999px 0 0;
  background: var(--brand-primary);
  opacity: 0;
  transform: scaleX(0.45);
  transition: opacity var(--motion-duration-fast) ease, transform var(--motion-duration-fast) ease;
}

.agent-detail-tabs button:hover,
.agent-detail-tabs button.is-active {
  color: var(--brand-active);
}

.agent-detail-tabs button.is-active::after {
  opacity: 1;
  transform: scaleX(1);
}

.agent-detail-tabs button:focus-visible {
  outline: 2px solid rgb(var(--brand-primary-rgb) / 0.26);
  outline-offset: 3px;
  border-radius: 5px;
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

.agent-version-section :deep(.workbench-panel) {
  border: 1px solid var(--border-divider);
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
}

.agent-version-table :deep(.el-table__cell) {
  padding-top: 11px;
  padding-bottom: 11px;
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

.config-section-nav {
  position: sticky;
  top: 0;
  z-index: 2;
  display: grid;
  gap: 5px;
  align-self: start;
  padding: 8px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-panel);
  box-shadow: var(--shadow-panel);
}

.config-section-nav__title {
  padding: 7px 10px 11px;
  border-bottom: 1px solid var(--border-divider);
  color: var(--text-primary);
  font-size: 13px;
  line-height: 20px;
}

.config-section-nav > button {
  display: grid;
  min-width: 0;
  min-height: 50px;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 5px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  text-align: left;
  transition: color var(--motion-duration-fast) ease, background var(--motion-duration-fast) ease;
}

.config-section-nav > button:hover {
  color: var(--brand-active);
  background: var(--bg-subtle);
}

.config-section-nav > button.is-active {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-weight: 700;
}

.config-section-nav__icon {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid var(--border-divider);
  border-radius: 9px;
  color: var(--text-secondary);
  background: var(--surface-solid-control);
  transition: color var(--motion-duration-fast) ease, background var(--motion-duration-fast) ease;
}

.config-section-nav > button.is-active .config-section-nav__icon {
  border-color: transparent;
  color: var(--text-inverse);
  background: var(--brand-primary);
  box-shadow: var(--shadow-active);
}

.config-section-nav__label {
  overflow: hidden;
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-section-nav__count {
  display: grid;
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  place-items: center;
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--bg-subtle);
  font-size: 11px;
  font-weight: 700;
}

.config-section-nav__count.is-ready {
  color: var(--status-success);
}

.config-section-nav__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  padding: 10px;
  border: 1px solid var(--border-divider);
  border-radius: 10px;
  background: var(--bg-subtle);
}

.config-section-nav__footer span,
.config-section-nav__footer strong,
.config-section-nav__footer small {
  display: block;
}

.config-section-nav__footer strong {
  color: var(--text-primary);
  font-size: 12px;
}

.config-section-nav__footer small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 10px;
}

.config-section-nav__footer button {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--brand-active);
  font-size: 11px;
  font-weight: 650;
  cursor: pointer;
  white-space: nowrap;
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

.supervisor-panel-note {
  margin: 14px 0 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.form-grid {
  display: grid;
  gap: var(--section-gap);
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.agent-identity-grid {
  gap: 12px;
}

.supervisor-config-stack {
  display: grid;
  gap: 14px;
  padding-top: 2px;
}

.supervisor-inline-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.supervisor-inline-label {
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 650;
  line-height: 20px;
  white-space: nowrap;
}

.supervisor-inline-control {
  width: 100%;
  min-width: 0;
}

.prompt-preview {
  display: grid;
  width: 100%;
  min-width: 0;
  min-height: 36px;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  border: 1px solid var(--border-divider);
  border-radius: 8px;
  background: var(--surface-solid-control);
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.16s ease,
    background 0.16s ease;
}

.prompt-preview:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  background: color-mix(in srgb, var(--brand-selected-bg) 36%, var(--surface-solid-control));
}

.prompt-preview:focus-visible {
  outline: 2px solid rgb(var(--brand-primary-rgb) / 0.32);
  outline-offset: 2px;
}

.prompt-preview.is-empty {
  color: var(--text-muted);
}

.prompt-preview__text {
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prompt-preview__icon {
  color: var(--brand-active);
  font-size: 15px;
}

.system-prompt-editor :deep(.el-textarea__inner) {
  min-height: 360px;
  line-height: 1.65;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

.agent-identity-grid :deep(.el-form-item) {
  margin-bottom: 0;
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

.workflow-name-cell {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.workflow-name-cell strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 650;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-name-cell small {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

.table-quiet-input,
.table-quiet-select {
  width: 100%;
}

.workflow-tool-table :deep(.table-quiet-input .el-input__wrapper),
.workflow-tool-table :deep(.table-quiet-select .el-select__wrapper) {
  min-height: 28px;
  padding-left: 8px;
  padding-right: 8px;
  background: transparent;
}

.workflow-tool-table :deep(.table-quiet-input .el-input__wrapper:hover),
.workflow-tool-table :deep(.table-quiet-select .el-select__wrapper:hover),
.workflow-tool-table :deep(.table-quiet-input .el-input__wrapper.is-focus),
.workflow-tool-table :deep(.table-quiet-select .el-select__wrapper.is-focused) {
  background: var(--surface-solid-control);
}

.workflow-tool-table :deep(.el-table__cell) {
  padding-top: 8px;
  padding-bottom: 8px;
}

.workflow-tool-table :deep(.el-table__header .el-table__cell) {
  padding-top: 6px;
  padding-bottom: 6px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
}

.wide {
  grid-column: span 2;
}

.advanced-settings-note {
  margin-bottom: var(--section-gap);
}

.workflow-tool-select {
  width: 100%;
}

.capability-empty-state {
  display: grid;
  min-height: 280px;
  place-items: center;
  align-content: center;
  gap: 12px;
  color: var(--text-primary);
}

.capability-empty-state__icon,
.config-asset-card__icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 12px;
  color: var(--status-info);
  background: color-mix(in srgb, var(--status-info) 10%, var(--surface-solid-control));
  font-size: 20px;
}

.capability-empty-state__icon.is-collaboration,
.config-asset-card__icon.is-collaboration {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.capability-empty-state__icon.is-skill {
  color: var(--status-info);
}

.capability-empty-state strong {
  font-size: 14px;
  line-height: 22px;
}

.config-asset-list {
  display: grid;
  gap: 12px;
}

.config-asset-card {
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  background: var(--surface-solid-control);
  box-shadow: var(--inner-highlight);
}

.config-asset-card__header {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
}

.config-asset-card__drag {
  display: grid;
  width: 20px;
  flex: 0 0 20px;
  place-items: center;
  color: var(--text-placeholder);
  font-size: 16px;
}

.config-asset-card__copy {
  min-width: 0;
  flex: 1;
}

.config-asset-card__title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.config-asset-card__title strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-asset-card__copy > p {
  overflow: hidden;
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 19px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-asset-card__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 11px;
}

.config-asset-card__actions .el-button {
  padding: 6px;
}

.asset-pill {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--bg-subtle);
  font-size: 11px;
  font-weight: 650;
  line-height: 18px;
  white-space: nowrap;
}

.asset-pill.is-read {
  color: var(--brand-active);
  background: var(--brand-selected-bg);
}

.asset-pill.is-info {
  color: var(--status-info);
  background: color-mix(in srgb, var(--status-info) 10%, var(--surface-solid-control));
}

.asset-pill.is-warning {
  color: var(--status-warning);
  background: color-mix(in srgb, var(--status-warning) 10%, var(--surface-solid-control));
}

.asset-pill.is-danger {
  color: var(--status-danger);
  background: color-mix(in srgb, var(--status-danger) 10%, var(--surface-solid-control));
}

.config-asset-card__meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--border-divider);
  background: var(--bg-subtle);
}

.config-asset-card__meta > div {
  min-width: 0;
  padding: 10px 16px;
}

.config-asset-card__meta > div + div {
  border-left: 1px solid var(--border-divider);
}

.config-asset-card__meta small,
.config-asset-card__meta strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-asset-card__meta small {
  color: var(--text-muted);
  font-size: 10px;
}

.config-asset-card__meta strong {
  margin-top: 3px;
  color: var(--text-primary);
  font-size: 12px;
}

.config-asset-card__meta strong.is-ready {
  color: var(--status-success);
}

.config-asset-card__settings {
  border-top: 1px solid var(--border-divider);
}

.config-asset-card__settings summary {
  display: flex;
  min-height: 42px;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  list-style: none;
}

.config-asset-card__settings summary::-webkit-details-marker {
  display: none;
}

.config-asset-card__settings summary::after {
  content: '展开';
  margin-left: auto;
  color: var(--brand-active);
  font-size: 11px;
  font-weight: 650;
}

.config-asset-card__settings[open] summary::after {
  content: '收起';
}

.config-asset-card__settings[open] summary {
  border-bottom: 1px solid var(--border-divider);
}

.config-card-settings-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  padding: 16px;
}

.config-card-settings-grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.config-asset-card__tags,
.config-asset-task-list {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.config-asset-card__tags {
  margin-top: 8px;
}

.config-asset-task-list {
  min-height: 32px;
}

.config-asset-card.is-collaboration .config-asset-card__header {
  min-height: 92px;
}

.workbench-layout:not(.has-section-nav) .capability-empty-state {
  min-height: 140px;
}

.workflow-tool-zero-state {
  display: flex;
  min-height: 84px;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px dashed rgb(var(--brand-primary-rgb) / 0.24);
  border-radius: 12px;
  background: rgb(var(--brand-primary-rgb) / 0.025);
}

.workflow-tool-zero-state__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-size: 18px;
}

.workflow-tool-zero-state__copy {
  min-width: 0;
  flex: 1;
}

.workflow-tool-zero-state__copy strong {
  color: var(--text-primary);
  font-size: 14px;
  line-height: 20px;
}

.workflow-tool-zero-state__copy p {
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 20px;
}

.workflow-tool-table,
.config-version-table {
  margin-top: 12px;
}

.workflow-picker-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

.workflow-picker-intro strong {
  display: block;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 22px;
}

.workflow-picker-intro p {
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 20px;
}

.workflow-picker-intro > span {
  flex: 0 0 auto;
  padding: 5px 10px;
  border-radius: 999px;
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-size: 12px;
  font-weight: 700;
}

.workflow-picker-searchbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  background: var(--surface-solid-control);
  box-shadow: var(--inner-highlight);
}

.workflow-picker-searchbar .el-input {
  flex: 1;
  min-width: 0;
}

.workflow-picker-searchbar :deep(.el-input__wrapper) {
  min-height: 40px;
  border-radius: 9px;
  box-shadow: var(--inner-highlight);
}

.workflow-picker-search-summary {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
  padding: 0 6px;
  color: var(--text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.workflow-picker-search-summary i {
  width: 1px;
  height: 14px;
  background: var(--border-divider);
}

.workflow-picker-search-summary strong {
  color: var(--brand-active);
}

.workflow-picker-results {
  position: relative;
  min-height: 112px;
}

.workflow-picker-list {
  display: grid;
  gap: 8px;
  max-height: 466px;
  margin-top: 14px;
  padding: 2px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.workflow-picker-item {
  position: relative;
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr) minmax(120px, 170px) 28px;
  align-items: center;
  gap: 13px;
  min-height: 82px;
  padding: 12px 14px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  background: var(--surface-solid-control);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.workflow-picker-item:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  background: rgb(var(--brand-primary-rgb) / 0.025);
  box-shadow: var(--shadow-panel);
  transform: translateY(-1px);
}

.workflow-picker-item:focus-visible {
  outline: 2px solid rgb(var(--brand-primary-rgb) / 0.32);
  outline-offset: 2px;
}

.workflow-picker-item.is-selected {
  border-color: rgb(var(--brand-primary-rgb) / 0.46);
  background: var(--brand-selected-bg);
  box-shadow: var(--shadow-active);
}

.workflow-picker-checkbox {
  flex: 0 0 auto;
}

.workflow-picker-copy {
  min-width: 0;
}

.workflow-picker-name-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.workflow-picker-name-line > strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-copy > span {
  display: block;
  overflow: hidden;
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-copy > p {
  overflow: hidden;
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-project {
  min-width: 0;
  padding-left: 14px;
  border-left: 1px solid var(--border-divider);
}

.workflow-picker-project span,
.workflow-picker-project strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-project span {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
}

.workflow-picker-project strong {
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
}

.workflow-picker-selected-mark {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 999px;
  color: var(--text-inverse);
  background: var(--brand-primary);
  box-shadow: var(--shadow-active);
}

.workflow-picker-no-results {
  min-height: 220px;
}

.workflow-picker-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 16px;
}

.workflow-avatar {
  display: grid;
  place-items: center;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--text-inverse);
  font-weight: 800;
}

.workflow-avatar.chat {
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-active));
}

.workflow-avatar.sdk-graph {
  background: linear-gradient(
    135deg,
    var(--status-success),
    color-mix(in srgb, var(--status-success) 72%, var(--status-info))
  );
}

.workflow-avatar.page-action {
  background: linear-gradient(
    135deg,
    var(--status-warning),
    color-mix(in srgb, var(--status-warning) 76%, var(--status-danger))
  );
}

.tool-cell-hint {
  display: block;
  margin-top: 3px;
  color: var(--text-muted);
}

.tool-override-grid {
  display: grid;
  gap: 12px;
  padding: 8px 20px;
}

.skill-binding-zero-state {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-lg);
  background: var(--bg-subtle);
}

.skill-binding-zero-state strong {
  color: var(--text-primary);
}

.skill-binding-zero-state p {
  max-width: 820px;
  margin: 5px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.skill-binding-identity,
.skill-binding-version {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.skill-binding-identity strong {
  overflow: hidden;
  color: var(--text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-binding-identity code,
.skill-binding-version code {
  color: var(--brand-primary);
  font-size: 12px;
}

.skill-binding-identity .el-tag {
  justify-self: start;
}

.skill-binding-identity small {
  overflow: hidden;
  color: var(--text-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-binding-version {
  justify-items: start;
}

.skill-picker-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  margin-bottom: 12px;
}

.skill-picker-table {
  margin-top: 12px;
}

.a2a-dialog-shell {
  display: grid;
  min-width: 0;
  gap: 16px;
}

.a2a-dialog-intro {
  display: grid;
  min-width: 0;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: center;
  gap: 13px;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 18%, var(--border-divider));
  border-radius: var(--radius-md);
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--brand-primary) 11%, transparent), transparent 72%),
    var(--surface-solid-control);
}

.a2a-dialog-intro__icon,
.a2a-dialog-empty__icon {
  display: grid;
  place-items: center;
  border-radius: 13px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--brand-primary) 13%, var(--surface-solid-control));
}

.a2a-dialog-intro__icon {
  width: 44px;
  height: 44px;
  font-size: 21px;
}

.a2a-dialog-intro__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.a2a-dialog-intro__copy small {
  color: var(--text-link);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.11em;
}

.a2a-dialog-intro__copy strong {
  color: var(--text-primary);
  font-size: 15px;
  line-height: 22px;
}

.a2a-dialog-intro__copy p {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 18px;
}

.a2a-dialog-intro__protocol {
  padding: 4px 8px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 18%, var(--border-divider));
  border-radius: 999px;
  color: var(--text-link);
  background: var(--surface-glass-control);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.08em;
}

.a2a-dialog-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 24px 20px 20px;
  border: 1px dashed color-mix(in srgb, var(--brand-primary) 20%, var(--border-divider));
  border-radius: var(--radius-lg);
  background: color-mix(in srgb, var(--bg-subtle) 68%, var(--surface-solid-control));
  text-align: center;
}

.a2a-dialog-empty__icon {
  width: 48px;
  height: 48px;
  margin-bottom: 2px;
  font-size: 22px;
}

.a2a-dialog-empty__icon.is-ready {
  color: var(--status-success);
  background: var(--status-success-soft);
}

.a2a-dialog-empty__eyebrow {
  color: var(--text-link);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.04em;
}

.a2a-dialog-empty__eyebrow.is-ready {
  color: var(--status-success);
}

.a2a-dialog-empty h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: 18px;
  line-height: 26px;
}

.a2a-dialog-empty > p {
  max-width: 54ch;
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 21px;
}

.a2a-setup-path {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  text-align: left;
}

.a2a-setup-path li {
  display: grid;
  min-width: 0;
  grid-template-columns: 26px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--border-divider);
  border-radius: 10px;
  background: var(--surface-solid-control);
}

.a2a-setup-path li > span,
.a2a-dialog-choice-card__heading > span {
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--text-muted);
  background: var(--bg-subtle);
  font-size: 11px;
  font-weight: 750;
}

.a2a-setup-path li > span {
  width: 26px;
  height: 26px;
}

.a2a-setup-path li div,
.a2a-dialog-choice-card__heading div {
  display: grid;
  min-width: 0;
  gap: 1px;
}

.a2a-setup-path strong,
.a2a-dialog-choice-card__heading strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.a2a-setup-path small,
.a2a-dialog-choice-card__heading small {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.a2a-setup-path li.is-current {
  border-color: color-mix(in srgb, var(--brand-primary) 28%, var(--border-divider));
  background: color-mix(in srgb, var(--brand-primary) 12%, var(--surface-solid-control));
}

.a2a-setup-path li.is-current > span {
  color: var(--text-inverse);
  background: var(--brand-primary);
}

.a2a-setup-path li.is-done > span {
  color: var(--text-inverse);
  background: var(--status-success);
}

.a2a-dialog-form,
.a2a-dialog-task-section {
  display: grid;
  min-width: 0;
  gap: 12px;
}

.a2a-dialog-choice-grid {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.a2a-dialog-choice-card {
  display: grid;
  min-width: 0;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.a2a-dialog-choice-card__heading {
  display: grid;
  min-width: 0;
  grid-template-columns: 28px minmax(0, 1fr);
  align-items: center;
  gap: 9px;
}

.a2a-dialog-choice-card__heading > span {
  width: 28px;
  height: 28px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--brand-primary) 13%, var(--surface-solid-control));
}

.a2a-dialog-choice-card :deep(.el-select),
.a2a-dialog-task-section :deep(.el-select),
.a2a-dialog-settings :deep(.el-select),
.a2a-dialog-settings :deep(.el-input-number) {
  width: 100%;
}

.a2a-dialog-agent-preview {
  display: grid;
  min-width: 0;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--status-success) 22%, var(--border-divider));
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--status-success-soft) 58%, var(--surface-solid-control));
}

.a2a-dialog-agent-preview__avatar {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border-radius: 12px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--brand-primary) 13%, var(--surface-solid-control));
  font-size: 17px;
  font-weight: 800;
}

.a2a-dialog-agent-preview__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.a2a-dialog-agent-preview__copy strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.a2a-dialog-agent-preview__copy > span {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.a2a-dialog-agent-preview__copy p {
  display: -webkit-box;
  overflow: hidden;
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.a2a-dialog-agent-preview__tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 5px;
}

.a2a-dialog-task-section {
  padding: 14px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.a2a-dialog-section-heading {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.a2a-dialog-section-heading > div {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.a2a-dialog-section-heading strong {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 19px;
}

.a2a-dialog-section-heading small {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
}

.a2a-dialog-section-heading > span {
  flex: 0 0 auto;
  padding: 3px 8px;
  border-radius: 999px;
  color: var(--text-link);
  background: color-mix(in srgb, var(--brand-primary) 13%, var(--surface-solid-control));
  font-size: 10px;
  font-weight: 750;
}

.a2a-dialog-settings {
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.a2a-dialog-settings summary {
  display: flex;
  min-height: 54px;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  color: var(--text-primary);
  cursor: pointer;
  list-style: none;
}

.a2a-dialog-settings summary::-webkit-details-marker {
  display: none;
}

.a2a-dialog-settings summary > span {
  display: grid;
  gap: 1px;
}

.a2a-dialog-settings summary strong {
  font-size: 13px;
  line-height: 19px;
}

.a2a-dialog-settings summary small {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
}

.a2a-dialog-settings summary::after {
  content: '展开';
  margin-left: auto;
  color: var(--text-link);
  font-size: 11px;
  font-weight: 700;
}

.a2a-dialog-settings[open] summary {
  border-bottom: 1px solid var(--border-divider);
}

.a2a-dialog-settings[open] summary::after {
  content: '收起';
}

.a2a-dialog-settings__grid {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 14px;
}

.a2a-dialog-settings__grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.a2a-dialog-footer {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.a2a-dialog-footer > span {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
  text-align: left;
}

.a2a-dialog-footer__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
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

  .config-section-nav {
    position: static;
    display: flex;
    overflow-x: auto;
  }

  .config-section-nav > button {
    flex: 0 0 auto;
    min-width: 150px;
    white-space: nowrap;
  }

  .config-section-nav__title,
  .config-section-nav__footer {
    display: none;
  }

  .form-grid.two {
    grid-template-columns: 1fr;
  }

  .wide {
    grid-column: auto;
  }

  .workflow-tool-zero-state {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .workflow-tool-zero-state .el-button {
    width: 100%;
  }

  .skill-binding-zero-state {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (max-width: 720px) {
  .a2a-dialog-intro {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .a2a-dialog-intro__icon {
    width: 40px;
    height: 40px;
  }

  .a2a-dialog-intro__protocol {
    grid-column: 2;
    justify-self: start;
  }

  .a2a-setup-path,
  .a2a-dialog-choice-grid,
  .a2a-dialog-settings__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .a2a-dialog-agent-preview {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .a2a-dialog-agent-preview__avatar {
    width: 40px;
    height: 40px;
  }

  .a2a-dialog-agent-preview__tags {
    grid-column: 2;
    justify-content: flex-start;
  }

  .a2a-dialog-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .a2a-dialog-footer__actions {
    justify-content: flex-end;
  }

  .config-asset-card__header {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .config-asset-card__actions {
    width: 100%;
    justify-content: flex-end;
    padding-left: 76px;
  }

  .config-asset-card__meta,
  .config-card-settings-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .config-asset-card__meta > div + div {
    border-top: 1px solid var(--border-divider);
    border-left: 0;
  }

  .config-card-settings-grid .wide {
    grid-column: auto;
  }
}
</style>

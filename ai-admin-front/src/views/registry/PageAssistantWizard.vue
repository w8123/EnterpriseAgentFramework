<template>
  <div class="page-assistant">
    <header class="page-header">
      <div>
        <el-button link :icon="ArrowLeft" @click="goBack">返回项目详情</el-button>
        <h1>创建页面助手</h1>
      </div>
    </header>

    <main class="wizard-shell">
      <section ref="statusStripRef" class="step-progress" aria-label="页面助手创建步骤" @wheel="handleWizardWheel">
        <button
          v-for="step in steps"
          :key="step.key"
          class="progress-step"
          :class="{ active: displayedStep === step.key, done: step.done }"
          type="button"
          :aria-current="displayedStep === step.key ? 'step' : undefined"
          @click="selectStep(step.key)"
        >
          <span class="step-index">{{ step.index }}</span>
          <span>
            <strong>{{ step.title }}</strong>
            <small>{{ step.desc }}</small>
          </span>
        </button>
      </section>

      <section class="stage-shell" @wheel="handleWizardWheel">
        <div class="stack-card stack-card-back" />
        <div class="stack-card stack-card-middle" />
        <button class="stage-cue stage-cue-up" :disabled="!canGoPrev" type="button" aria-label="向上翻页" @click="goPrevStep">
          <span class="cue-chevron" aria-hidden="true" />
        </button>
        <section :key="displayedStep" ref="pagePanelRef" :class="['focus-panel', stepTransitionName, stepAttentionName]" @wheel.stop>
          <div v-if="displayedStep === 'connect'" :key="'connect'" class="step-screen">
          <div class="panel-head">
            <div>
              <span class="step-kicker">步骤 1</span>
            </div>
            <div class="panel-actions">
              <el-button type="primary" :icon="DocumentCopy" @click="openAiPromptDialog">AI 快速接入</el-button>
              <el-popover v-model:visible="sdkHelperVisible" placement="left-start" :width="640" trigger="click" popper-class="sdk-template-popover">
                <template #reference>
                  <el-button class="access-template-button" :icon="DocumentCopy">手动接入</el-button>
                </template>
                <div class="template-box template-modal inline-template">
                  <div class="template-modal-head">
                    <div class="template-title-block">
                      <span class="template-icon">
                        <el-icon><DocumentCopy /></el-icon>
                      </span>
                      <div>
                        <h3>手动接入</h3>
                        <p>用于展示最小页面动作声明示例，帮助业务系统完成 SDK 接入</p>
                        <div class="template-badges">
                          <span>推荐模板</span>
                          <span>最小示例</span>
                        </div>
                      </div>
                    </div>
                    <div class="template-actions">
                      <el-button class="copy-template-button" :icon="DocumentCopy" @click="copySdkTemplate">
                        {{ sdkTemplateCopied ? '已复制' : '复制代码' }}
                      </el-button>
                      <button class="template-close-button" type="button" aria-label="关闭手动接入" @click="sdkHelperVisible = false">
                        <el-icon><Close /></el-icon>
                      </button>
                    </div>
                  </div>
                  <div class="template-code-shell">
                    <div class="template-code-toolbar">
                      <span>JavaScript</span>
                      <span>SDK 示例</span>
                      <span>已适配页面动作目录</span>
                    </div>
                    <pre><code v-html="highlightedSdkTemplate" /></pre>
                  </div>
                </div>
              </el-popover>
            </div>
          </div>

          <div class="health-grid">
            <div v-for="item in stats" :key="item.label" class="health-card" :class="`stat-${item.key}`">
              <span class="stat-icon">{{ item.icon }}</span>
              <span class="stat-label">{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>

          <section class="page-access-board">
            <div class="page-access-board-head">
              <div>
                <h3>页面接入进度</h3>
                <small>{{ pageAssistantAccessCount ? `${pageAssistantAccessCount} 个页面接入任务` : '复制提示词后，Cursor 回传进度会出现在这里' }}</small>
              </div>
              <el-button size="small" :loading="pageAssistantSessionsLoading" @click="loadPageAssistantSessions()">刷新进度</el-button>
            </div>
            <div v-if="pageAssistantAccessCount" class="page-access-progress">
              <div class="page-access-status-row" aria-label="页面接入状态统计">
                <span v-for="group in pageAssistantAccessGroups" :key="group.key" class="page-access-status-pill">
                  <strong>{{ group.title }}</strong>
                  <small>{{ group.items.length }}</small>
                </span>
              </div>
              <div class="page-access-card-list">
                <article v-for="session in pageAssistantSessions" :key="session.sessionId" class="page-access-card">
                  <div class="page-access-card-head">
                    <span>
                      <strong>{{ pageAccessTitle(session) }}</strong>
                      <small>{{ session.targetRoute || '等待目标路由' }}</small>
                    </span>
                    <el-tag size="small" :type="pageAccessStateTagType(session.completionState)" effect="plain">
                      {{ pageAccessStateLabel(session.completionState) }}
                    </el-tag>
                  </div>
                  <div class="page-access-card-meta">
                    <span>{{ session.toolName || 'AI Coding' }}</span>
                    <span>{{ session.completedSteps }}/{{ session.totalSteps }} 步</span>
                    <span>{{ session.actionCount }} 动作</span>
                  </div>
                  <p>{{ session.lastMessage || session.sessionId }}</p>
                  <div class="page-access-card-actions">
                    <el-button size="small" text @click="selectedPageAssistantAccess = session">详情</el-button>
                    <el-button size="small" text :loading="pageAssistantCheckRunning" @click="runPageAssistantCardCheck(session)">自检</el-button>
                    <el-button size="small" text :disabled="!session.targetPageKey" @click="usePageAssistantAccess(session)">创建助手</el-button>
                  </div>
                </article>
              </div>
            </div>
            <el-empty v-else description="暂无页面接入任务" :image-size="64" />
          </section>

          <div class="step-footer-note">
            <el-alert
              v-if="!pageRegistry.length"
              type="warning"
              show-icon
              :closable="false"
              title="当前项目还没有页面上报"
              description="可以查看手动接入完成 SDK 声明，或先手工声明一个页面动作草案。"
            />
            <el-alert
              v-else
              type="success"
              show-icon
              :closable="false"
              title="页面动作目录已就绪"
              description="下一步选择要服务的业务页面。"
            />
          </div>
        </div>

        <div v-else-if="displayedStep === 'page'" :key="'page'" class="step-screen">
          <div class="panel-head">
            <div>
              <span class="step-kicker">步骤 2</span>
              <h2>选择业务页面</h2>
            </div>
            <el-button :icon="Plus" @click="manualDialogVisible = true">手工声明动作</el-button>
          </div>

          <div v-if="pageRegistry.length" class="page-list">
            <button
              v-for="page in pageRegistry"
              :key="page.id || page.pageKey"
              class="page-row"
              :class="{ selected: pageIdentity(page) === selectedPageIdentity }"
              type="button"
              @click="selectPage(page)"
            >
              <span>
                <strong>{{ page.name || page.pageKey }}</strong>
                <small>{{ page.routePattern || '-' }}</small>
              </span>
              <el-tag size="small" effect="plain">{{ actionCount(page.pageKey) }} 动作</el-tag>
            </button>
          </div>
          <el-empty v-else description="暂无页面上报，可先查看手动接入或手工声明动作" :image-size="88" />

          <div class="step-footer-note">
            <el-alert
              type="info"
              show-icon
              :closable="false"
              title="业务页面选择完成后"
              description="可通过向下翻页进入动作选择。"
            />
          </div>
        </div>

        <div v-else-if="displayedStep === 'action'" :key="'action'" class="step-screen">
          <div class="panel-head">
            <div>
              <span class="step-kicker">步骤 3</span>
              <h2>选择页面动作</h2>
            </div>
            <el-tag v-if="selectedPage" effect="plain">{{ selectedPage.name || selectedPage.pageKey }}</el-tag>
          </div>

          <div v-if="filteredActions.length" class="action-select-area">
            <div class="action-toolbar">
              <span><strong>{{ selectedActions.length }}</strong> / {{ filteredActions.length }} 个动作已选</span>
              <div class="action-toolbar-actions">
                <button type="button" @click="selectAllFilteredActions">全选</button>
                <button type="button" @click="clearFilteredActions">清空</button>
              </div>
            </div>
            <div class="action-card-list">
              <button
                v-for="action in filteredActions"
                :key="actionRowKey(action)"
                class="action-card"
                :class="{ selected: isActionSelected(action) }"
                type="button"
                @click="toggleActionSelection(action)"
              >
                <span class="action-check" aria-hidden="true">
                  <span v-if="isActionSelected(action)">✓</span>
                </span>
                <span class="action-main">
                  <strong>{{ action.title || action.actionKey }}</strong>
                  <small>{{ action.description || action.actionKey }}</small>
                </span>
                <span class="action-meta">
                  <small>actionKey</small>
                  <strong>{{ action.actionKey }}</strong>
                </span>
                <span class="action-flags">
                  <el-tag size="small" effect="plain">{{ action.confirmRequired ? '需确认' : '免确认' }}</el-tag>
                  <small>{{ action.lastSeenAt || '未上报时间' }}</small>
                </span>
              </button>
            </div>
          </div>
          <el-empty v-else description="当前页面暂无动作，可先手工声明动作草案" :image-size="88" />

          <div class="step-footer-note">
            <el-alert
              type="info"
              show-icon
              :closable="false"
              title="动作选择完成后"
              description="可通过向下翻页进入草稿配置。"
            />
          </div>
        </div>

        <div v-else-if="displayedStep === 'draft'" :key="'draft'" class="step-screen">
          <div class="panel-head">
            <div>
              <span class="step-kicker">步骤 4</span>
              <h2>生成 / 选择 Workflow 草稿</h2>
            </div>
            <div class="panel-actions">
              <el-tag effect="plain">{{ selectedApiAssets.length }} 个 API 资产</el-tag>
            </div>
          </div>

          <div class="draft-summary-strip" aria-label="生成草稿摘要">
            <span>页面：{{ selectedPage?.name || selectedPageKey || '未选择' }}</span>
            <span>动作：{{ selectedActions.length }} 个已选</span>
            <span>模型：{{ modelInstanceId ? '已选择' : '未选择' }}</span>
            <span>API：{{ selectedApiAssets.length }} 个</span>
          </div>

          <section class="draft-entry-section ai-coding-entry">
            <div class="draft-entry-head">
              <div>
                <h3>AI Coding 生成</h3>
                <small>由外部 AI 工具创建 Workflow 并回传结果</small>
              </div>
              <el-button :icon="DocumentCopy" @click="openWorkflowAiCodingPromptDialog">使用 AI Coding 生成</el-button>
            </div>

            <div v-if="isAiCodingWorkflowSelected" class="draft-source-banner ai-coding">
              <strong>已选用 AI Coding Workflow</strong>
              <span>workflowId：{{ createdWorkflowId }}</span>
              <el-button size="small" type="primary" @click="focusStepCard('bind')">去挂载智能体</el-button>
            </div>

            <div v-else-if="workflowAiCodingDraftStep" class="workflow-ai-coding-result-card">
              <div class="workflow-ai-coding-result-head">
                <el-tag :type="stepStatusTagType(workflowAiCodingDraftStep.status)" effect="plain">
                  AI Coding {{ workflowAiCodingDraftStep.status }}
                </el-tag>
                <strong>{{ workflowAiCodingDraftStep.message || 'Workflow AI Coding 草稿已回传' }}</strong>
              </div>
              <div class="studio-ready-metrics compact">
                <div>
                  <span>workflowId</span>
                  <strong>{{ workflowAiCodingDraftEvidence.workflowId || '—' }}</strong>
                </div>
                <div>
                  <span>keySlug</span>
                  <strong>{{ workflowAiCodingDraftEvidence.keySlug || '—' }}</strong>
                </div>
                <div>
                  <span>validate</span>
                  <strong>{{ workflowAiCodingValidationSummary || '—' }}</strong>
                </div>
                <div>
                  <span>page-assistant validate</span>
                  <strong>{{ workflowAiCodingPageAssistantValidationSummary || '—' }}</strong>
                </div>
                <div>
                  <span>browser runtime</span>
                  <strong>{{ workflowAiCodingRuntimeVerificationSummary || '—' }}</strong>
                </div>
              </div>
              <div class="workflow-ai-coding-result-actions">
                <el-button size="small" @click="openAiCodingWorkflowStudio">打开 Studio</el-button>
                <el-button size="small" type="primary" @click="useAiCodingWorkflowDraft">使用该 Workflow 继续</el-button>
              </div>
            </div>
          </section>

          <section class="draft-entry-section platform-entry">
            <div class="draft-entry-head">
              <div>
                <h3>平台内生成</h3>
                <small>配置模型与要求，在平台内生成 GraphSpec 草稿</small>
              </div>
              <el-tag v-if="draftPreview && draftSource === 'PLATFORM_GENERATED'" effect="plain" type="success">已有平台草稿</el-tag>
            </div>

          <el-form label-position="top" class="draft-form draft-console">
            <section class="draft-config-card">
              <div class="draft-section-head">
                <h3>助手基础配置</h3>
                <small>确认助手类型、名称和模型</small>
              </div>

              <div class="goal-card-grid" role="radiogroup" aria-label="助手目标">
                <button
                  v-for="goal in assistantGoalOptions"
                  :key="goal.value"
                  class="goal-card"
                  :class="{ selected: assistantGoal === goal.value }"
                  type="button"
                  role="radio"
                  :aria-checked="assistantGoal === goal.value"
                  @click="assistantGoal = goal.value"
                >
                  <span class="goal-card-icon" :class="`tone-${goal.tone}`" aria-hidden="true">
                    <el-icon>
                      <component :is="goal.icon" />
                    </el-icon>
                  </span>
                  <strong>{{ goal.title }}</strong>
                  <small>{{ goal.desc }}</small>
                </button>
              </div>

              <el-form-item label="Workflow 名称">
                <el-input v-model="agentName" placeholder="例如：班组档案页面助手" />
              </el-form-item>
              <el-form-item label="模型实例">
                <el-select v-model="modelInstanceId" placeholder="选择 LLM 模型实例" filterable>
                  <el-option v-for="model in modelOptions" :key="model.id" :label="modelOptionLabel(model)" :value="model.id" />
                </el-select>
              </el-form-item>
            </section>

            <section class="draft-config-card prompt-card">
              <div class="draft-section-head prompt-head">
                <div>
                  <h3>生成要求</h3>
                  <small>描述生成草稿的执行意图和约束</small>
                </div>
                <button type="button" @click="requirement = defaultRequirement()">使用默认要求</button>
              </div>
              <el-input class="prompt-editor" v-model="requirement" type="textarea" :rows="8" resize="none" />
            </section>

            <section class="draft-config-card api-resource-card">
              <div class="draft-section-head">
                <h3>API 资产（可选）</h3>
                <small>可绑定后端 API 作为工具资源提供给模型</small>
              </div>
              <div class="api-assets">
                <el-table
                  v-if="apiAssets.length"
                  :data="apiAssets"
                  row-key="apiId"
                  size="small"
                  @selection-change="selectedApiAssets = $event"
                >
                  <el-table-column type="selection" width="42" />
                  <el-table-column prop="name" label="API" min-width="180" show-overflow-tooltip />
                  <el-table-column prop="httpMethod" label="方法" width="80" />
                  <el-table-column prop="globalToolName" label="Tool" min-width="160" show-overflow-tooltip />
                </el-table>
                <div v-else class="api-empty-card">
                  <span class="api-empty-icon">API</span>
                  <div>
                    <strong>暂无可选 API 资产</strong>
                    <small>本次将仅基于已选择的页面动作生成 GraphSpec 草稿。</small>
                  </div>
                </div>
              </div>
            </section>
          </el-form>

          <div class="draft-generate-row">
            <template v-if="isAiCodingWorkflowSelected">
              <el-button link type="primary" @click="confirmSwitchToPlatformGeneration">改用平台生成</el-button>
            </template>
            <template v-else>
              <el-button type="primary" :icon="MagicStick" :loading="generating" @click="generateDraft">生成 Workflow 草稿</el-button>
            </template>
          </div>
          </section>
        </div>

        <div v-else-if="displayedStep === 'confirm'" :key="'confirm'" class="step-screen">
          <div class="panel-head">
            <div>
              <span class="step-kicker">步骤 5</span>
              <h2>确认草稿</h2>
            </div>
          </div>

          <div v-if="isAiCodingWorkflowSelected" class="studio-ready">
            <div class="studio-ready-hero">
              <div class="studio-ready-icon">
                <el-icon><Finished /></el-icon>
              </div>
              <div class="studio-ready-copy">
                <span>AI Coding 链路</span>
                <strong>已选择 AI Coding Workflow，可直接挂载智能体</strong>
                <p>该 Workflow 已由外部 AI 工具创建，无需在此步再次创建。</p>
              </div>
              <div class="studio-ready-state">
                <em>Skipped</em>
              </div>
            </div>

            <div class="studio-ready-metrics">
              <div>
                <span>workflowId</span>
                <strong>{{ createdWorkflowId }}</strong>
              </div>
              <div>
                <span>pageKey</span>
                <strong>{{ selectedPageKey || '未选择' }}</strong>
              </div>
              <div>
                <span>routePattern</span>
                <strong>{{ selectedPage?.routePattern || '未设置' }}</strong>
              </div>
              <div>
                <span>已选动作</span>
                <strong>{{ selectedActions.map((item) => item.actionKey).join('、') || '无' }}</strong>
              </div>
            </div>

            <div class="studio-ready-actions">
              <button type="button" class="secondary" @click="focusStepCard('draft')">
                返回选择 Workflow
              </button>
              <button type="button" class="primary" @click="selectStep('bind')">
                <el-icon><Finished /></el-icon>
                去挂载智能体
              </button>
            </div>
          </div>

          <div v-else-if="draftPreview" class="studio-ready">
            <div class="studio-ready-hero" :class="{ warning: draftIssueCount }">
              <div class="studio-ready-icon">
                <el-icon>
                  <Warning v-if="draftIssueCount" />
                  <Finished v-else />
                </el-icon>
              </div>
              <div class="studio-ready-copy">
                <span>{{ draftIssueCount ? '需要复核' : '草稿已就绪' }}</span>
                <strong>{{ draftIssueCount ? '草稿仍有校验问题' : '确认后将创建 PAGE_ASSISTANT Workflow' }}</strong>
                <p>
                  {{ draftIssueCount
                    ? '请返回配置或重新生成，修复后再创建 Workflow。'
                    : '创建 Workflow 后将继续挂载到页面副驾驶 Agent，再进入 Workflow Studio。' }}
                </p>
              </div>
              <div class="studio-ready-state">
                <em>{{ draftIssueCount ? `${draftIssueCount} 个问题` : 'Preview' }}</em>
              </div>
            </div>

            <div class="studio-ready-metrics">
              <div>
                <span>Workflow 名称</span>
                <strong>{{ pageAssistantWorkflowName() }}</strong>
              </div>
              <div>
                <span>pageKey</span>
                <strong>{{ selectedPageKey || '未选择' }}</strong>
              </div>
              <div>
                <span>routePattern</span>
                <strong>{{ selectedPage?.routePattern || '未设置' }}</strong>
              </div>
              <div>
                <span>已选动作</span>
                <strong>{{ selectedActions.map((item) => item.actionKey).join('、') || '无' }}</strong>
              </div>
              <div>
                <span>流程结构</span>
                <strong>{{ draftNodeCount }} 节点 / {{ draftEdgeCount }} 连线</strong>
              </div>
              <div>
                <span>模型</span>
                <strong>{{ selectedModelLabel }}</strong>
              </div>
            </div>

            <div v-if="draftIssueCount" class="studio-ready-issues">
              <span v-for="item in draftIssues" :key="item">{{ item }}</span>
            </div>

            <div class="studio-ready-actions">
              <button type="button" class="secondary" @click="selectStep('draft')">
                返回配置
              </button>
              <button type="button" class="primary" :disabled="Boolean(draftIssueCount) || creatingWorkflow" @click="confirmCreateWorkflow">
                <el-icon><Finished /></el-icon>
                {{ creatingWorkflow ? '创建中...' : '确认创建 Workflow' }}
              </button>
            </div>
          </div>
          <div v-else class="studio-ready-empty">
            <div class="studio-ready-icon warning">
              <el-icon><MagicStick /></el-icon>
            </div>
            <strong>还没有生成草稿</strong>
            <span>先完成配置并生成 GraphSpec 预览，再确认创建 Workflow。</span>
            <button type="button" @click="selectStep('draft')">去生成草稿</button>
          </div>
        </div>

        <div v-else-if="displayedStep === 'bind'" :key="'bind'" class="step-screen">
          <div class="panel-head">
            <div>
              <span class="step-kicker">步骤 6</span>
              <h2>挂载智能体</h2>
            </div>
          </div>

          <div v-if="createdWorkflowId" class="studio-ready">
            <p class="bind-intro">
              页面副驾驶 Agent 是业务系统统一 AI 按钮的入口。这里会把当前页面助手 Workflow 挂载到它，让嵌入式对话在该页面可调用这些动作。
            </p>

            <div class="studio-ready-metrics">
              <div>
                <span>Agent 名称</span>
                <strong>{{ pageCopilotAgent?.name || '页面副驾驶 Agent' }}</strong>
              </div>
              <div>
                <span>keySlug</span>
                <strong>{{ pageCopilotAgent?.keySlug || `${projectCode}-page-copilot` }}</strong>
              </div>
              <div>
                <span>agentKind</span>
                <strong>{{ pageCopilotAgent?.agentKind || 'PAGE_COPILOT' }}</strong>
              </div>
              <div>
                <span>状态</span>
                <strong>{{ pageCopilotAgent ? '已存在' : '将自动创建/复用' }}</strong>
              </div>
              <div>
                <span>bindingType</span>
                <strong>PAGE</strong>
              </div>
              <div>
                <span>pageKey</span>
                <strong>{{ selectedPageKey || '未选择' }}</strong>
              </div>
              <div>
                <span>routePattern</span>
                <strong>{{ selectedPage?.routePattern || '未设置' }}</strong>
              </div>
              <div>
                <span>actionKeys</span>
                <strong>{{ selectedActions.map((item) => item.actionKey).join('、') || '无' }}</strong>
              </div>
            </div>

            <div class="studio-ready-actions">
              <button type="button" class="secondary" @click="focusStepCard(isAiCodingWorkflowSelected ? 'draft' : 'confirm')">
                {{ isAiCodingWorkflowSelected ? '返回选择 Workflow' : '返回确认草稿' }}
              </button>
              <button type="button" class="primary" :disabled="bindingAgent" @click="bindToPageCopilot">
                <el-icon><Connection /></el-icon>
                {{ bindingAgent ? '挂载中...' : '挂载到页面副驾驶 Agent' }}
              </button>
            </div>
          </div>
          <div v-else class="studio-ready-empty">
            <strong>还没有创建 Workflow</strong>
            <span>请先在上一步确认并创建 PAGE_ASSISTANT Workflow。</span>
            <button type="button" @click="selectStep('confirm')">去确认草稿</button>
          </div>
        </div>

        <div v-else :key="'studio'" class="step-screen">
          <div class="panel-head">
            <div>
              <span class="step-kicker">步骤 7</span>
              <h2>进入 Workflow Studio</h2>
            </div>
          </div>

          <div v-if="bindingResult" class="studio-ready">
            <div class="studio-ready-hero">
              <div class="studio-ready-icon">
                <el-icon><Finished /></el-icon>
              </div>
              <div class="studio-ready-copy">
                <span>挂载完成</span>
                <strong>页面助手 Workflow 已绑定到页面副驾驶 Agent</strong>
                <p>下一步进入 Workflow Studio，检查画布结构、参数映射和发布校验。</p>
              </div>
              <div class="studio-ready-state">
                <em>Bound</em>
              </div>
            </div>

            <div class="studio-ready-metrics">
              <div>
                <span>agentId</span>
                <strong>{{ bindingResult.agentId }}</strong>
              </div>
              <div>
                <span>agentKeySlug</span>
                <strong>{{ bindingResult.agentKeySlug }}</strong>
              </div>
              <div>
                <span>workflowId</span>
                <strong>{{ bindingResult.workflowId }}</strong>
              </div>
              <div>
                <span>workflowKeySlug</span>
                <strong>{{ bindingResult.workflowKeySlug }}</strong>
              </div>
              <div>
                <span>bindingId</span>
                <strong>{{ bindingResult.bindingId }}</strong>
              </div>
            </div>

            <div class="studio-ready-actions">
              <button type="button" class="primary" @click="enterWorkflowStudio">
                <el-icon><Connection /></el-icon>
                进入 Workflow Studio
              </button>
            </div>
          </div>
          <div v-else class="studio-ready-empty">
            <strong>还没有完成挂载</strong>
            <span>请先在“挂载智能体”步骤完成 Agent binding。</span>
            <button type="button" @click="selectStep('bind')">去挂载智能体</button>
          </div>
        </div>
        </section>
        <button class="stage-cue stage-cue-down" :disabled="!canGoNext" type="button" aria-label="向下翻页" @click="goNextStep">
          <span class="cue-chevron" aria-hidden="true" />
        </button>
      </section>
    </main>

    <el-dialog v-model="manualDialogVisible" title="手工声明页面动作" width="720px" destroy-on-close>
      <el-form label-position="top">
        <div class="manual-grid">
          <el-form-item label="pageKey">
            <el-input v-model="manualForm.pageKey" placeholder="teamArchive.list" />
          </el-form-item>
          <el-form-item label="页面名称">
            <el-input v-model="manualForm.pageName" placeholder="班组档案" />
          </el-form-item>
          <el-form-item label="路由">
            <el-input v-model="manualForm.routePattern" placeholder="/teams/archive" />
          </el-form-item>
          <el-form-item label="actionKey">
            <el-input v-model="manualForm.actionKey" placeholder="qmssmp.teamArchive.search" />
          </el-form-item>
          <el-form-item label="动作标题">
            <el-input v-model="manualForm.title" placeholder="查询班组档案" />
          </el-form-item>
          <el-form-item label="二次确认">
            <el-switch v-model="manualForm.confirmRequired" />
          </el-form-item>
        </div>
        <el-form-item label="动作描述">
          <el-input v-model="manualForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="inputSchema JSON">
          <el-input v-model="manualForm.inputSchemaText" type="textarea" :rows="5" />
        </el-form-item>
        <el-form-item label="sampleArgs JSON">
          <el-input v-model="manualForm.sampleArgsText" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="manualSubmitting" @click="submitManualAction">保存动作草案</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="pageAssistantAccessDetailVisible"
      title="页面接入详情"
      width="780px"
      destroy-on-close
    >
      <div v-if="selectedPageAssistantAccess" class="page-access-detail">
        <div class="page-access-detail-head">
          <span>
            <strong>{{ pageAccessTitle(selectedPageAssistantAccess) }}</strong>
            <small>{{ selectedPageAssistantAccess.targetRoute || '等待 Cursor 绑定目标路由' }}</small>
          </span>
          <el-tag :type="pageAccessStateTagType(selectedPageAssistantAccess.completionState)" effect="plain">
            {{ pageAccessStateLabel(selectedPageAssistantAccess.completionState) }}
          </el-tag>
        </div>
        <div class="page-access-detail-meta">
          <span>Session：{{ selectedPageAssistantAccess.sessionId }}</span>
          <span>工具：{{ selectedPageAssistantAccess.toolName || '-' }}</span>
          <span>动作：{{ selectedPageAssistantAccess.actionCount }}</span>
          <span>最近回传：{{ selectedPageAssistantAccess.lastReportedAt || '-' }}</span>
        </div>
        <div class="page-access-step-detail-list">
          <section v-for="step in selectedPageAssistantAccess.steps" :key="step.stepKey" class="page-access-step-detail">
            <div>
              <el-tag size="small" :type="stepStatusTagType(step.status)" effect="plain">{{ step.status }}</el-tag>
              <strong>{{ step.title }}</strong>
              <small>{{ step.stepKey }}</small>
            </div>
            <p>{{ step.message || '暂无回传说明' }}</p>
            <pre v-if="Object.keys(step.evidence || {}).length">{{ formatEvidence(step.evidence) }}</pre>
          </section>
        </div>
      </div>
      <template #footer>
        <el-button @click="selectedPageAssistantAccess = null">关闭</el-button>
        <el-button
          v-if="selectedPageAssistantAccess"
          type="primary"
          :disabled="!selectedPageAssistantAccess.targetPageKey"
          @click="usePageAssistantAccess(selectedPageAssistantAccess)"
        >
          基于此页面创建助手
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="aiPromptDialogVisible" title="页面助手 AI 快速接入" width="860px" destroy-on-close>
      <div class="ai-prompt-dialog">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="复制给 Cursor / Codex / Claude Code"
          description="该提示词只面向当前业务前端页面动作接入；项目级 SDK、网关和 embed token 接入仍走项目 AI 快速接入。"
        />
        <section class="ai-access-session-panel">
          <div class="ai-access-session-head">
            <div>
              <span>页面助手进度会话</span>
              <strong>{{ pageAssistantSession?.sessionId || '准备中' }}</strong>
            </div>
            <div class="ai-access-session-actions">
              <el-button size="small" :loading="pageAssistantManifestLoading" @click="refreshPageAssistantSession">刷新进度</el-button>
              <el-button
                size="small"
                type="primary"
                :loading="pageAssistantCheckRunning"
                :disabled="!pageAssistantSession"
                @click="runPageAssistantSelfCheck"
              >
                运行自检
              </el-button>
            </div>
          </div>
          <div class="ai-access-meta-grid">
            <span>
              <small>App Key</small>
              <strong>{{ pageAssistantManifest?.project.registryAppKey || project?.registryAppKey || '未配置' }}</strong>
            </span>
            <span>
              <small>AI Coding</small>
              <strong>{{ aiCodingAccessState }}</strong>
            </span>
            <span>
              <small>目标页面</small>
              <strong>{{ selectedPage?.name || selectedPageKey || '待确认' }}</strong>
            </span>
            <span>
              <small>接入进度</small>
              <strong>{{ pageAssistantProgressText }}</strong>
            </span>
          </div>
          <div v-if="pageAssistantSessionSteps.length" class="ai-access-step-list">
            <div v-for="step in pageAssistantSessionSteps" :key="step.stepKey" class="ai-access-step">
              <el-tag size="small" :type="stepStatusTagType(step.status)" effect="plain">{{ step.status }}</el-tag>
              <span>{{ step.title }}</span>
              <small>{{ step.message || step.stepKey }}</small>
            </div>
          </div>
          <el-alert
            v-else
            type="warning"
            show-icon
            :closable="false"
            title="尚未获取到页面助手进度"
            description="可以先复制提示词；Cursor 完成接入后可按提示词中的 page-assistant session URL 回传进度。"
          />
        </section>
        <section class="ai-helper-command-list">
          <div class="ai-helper-command">
            <span>
              <strong>Angular scaffold</strong>
              <small>在业务前端仓库生成官方 Page Action bridge 模板</small>
            </span>
            <code>{{ pageAssistantScaffoldCommand }}</code>
            <el-button size="small" :icon="DocumentCopy" @click="copyText(pageAssistantScaffoldCommand, '已复制 scaffold 命令')">复制</el-button>
          </div>
          <div class="ai-helper-command">
            <span>
              <strong>本地 verify</strong>
              <small>使用本机 PowerShell 验证静态证据；需要时加 -ReportToPlatform 回传</small>
            </span>
            <code>{{ pageAssistantVerifyCommand }}</code>
            <el-button size="small" :icon="DocumentCopy" @click="copyText(pageAssistantVerifyCommand, '已复制 verify 命令')">复制</el-button>
          </div>
        </section>
        <div class="ai-prompt-toolbar">
          <el-radio-group v-model="aiPromptTool" size="small">
            <el-radio-button label="Cursor" />
            <el-radio-button label="Codex" />
            <el-radio-button label="Claude Code" />
          </el-radio-group>
          <el-button type="primary" :icon="DocumentCopy" @click="copyPageAssistantPrompt">
            {{ aiPromptCopied ? '已复制' : '复制提示词' }}
          </el-button>
        </div>
        <el-input
          class="ai-prompt-editor"
          :model-value="pageAssistantOnboardingPrompt"
          type="textarea"
          :rows="24"
          resize="none"
          readonly
        />
      </div>
    </el-dialog>

    <el-dialog
      v-model="workflowAiCodingPromptDialogVisible"
      title="使用 AI Coding 生成页面助手 Workflow"
      width="860px"
      destroy-on-close
    >
      <div class="ai-prompt-dialog">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="复制给 Cursor / Codex / Claude Code"
          description="让外部 AI 工具通过 Workflow AI Coding REST API 创建 PAGE_ASSISTANT 草稿；本阶段只复制提示词，不在向导内自动调用 API。生成完成后 AI 工具应回传 workflow-ai-coding-result，向导将展示结果。"
        />
        <div v-if="workflowAiCodingDraftStep" class="workflow-ai-coding-result-card dialog">
          <div class="workflow-ai-coding-result-head">
            <el-tag :type="stepStatusTagType(workflowAiCodingDraftStep.status)" effect="plain">
              已回传 {{ workflowAiCodingDraftStep.status }}
            </el-tag>
            <strong>{{ workflowAiCodingDraftStep.message || 'Workflow AI Coding 草稿已回传' }}</strong>
            <el-button size="small" link :loading="pageAssistantManifestLoading" @click="refreshWorkflowAiCodingDraftStatus">
              刷新状态
            </el-button>
          </div>
          <div class="studio-ready-metrics compact">
            <div>
              <span>workflowId</span>
              <strong>{{ workflowAiCodingDraftEvidence.workflowId || '—' }}</strong>
            </div>
            <div>
              <span>keySlug</span>
              <strong>{{ workflowAiCodingDraftEvidence.keySlug || '—' }}</strong>
            </div>
            <div>
              <span>workflowName</span>
              <strong>{{ workflowAiCodingDraftEvidence.workflowName || '—' }}</strong>
            </div>
            <div>
              <span>validate</span>
              <strong>{{ workflowAiCodingValidationSummary || '—' }}</strong>
            </div>
            <div>
              <span>page-assistant validate</span>
              <strong>{{ workflowAiCodingPageAssistantValidationSummary || '—' }}</strong>
            </div>
            <div>
              <span>browser runtime</span>
              <strong>{{ workflowAiCodingRuntimeVerificationSummary || '—' }}</strong>
            </div>
          </div>
          <div class="workflow-ai-coding-result-actions">
            <el-button size="small" @click="openAiCodingWorkflowStudio">打开 Studio</el-button>
            <el-button size="small" type="primary" @click="useAiCodingWorkflowDraft">使用该 Workflow 继续</el-button>
          </div>
        </div>
        <div v-else class="workflow-ai-coding-result-empty">
          <span>外部 AI 完成创建/validate 并回传后，这里会显示 workflowId 与校验摘要。</span>
          <el-button size="small" link :loading="pageAssistantManifestLoading" @click="refreshWorkflowAiCodingDraftStatus">
            刷新回传状态
          </el-button>
        </div>
        <div class="ai-prompt-toolbar">
          <el-radio-group v-model="workflowAiCodingPromptTool" size="small">
            <el-radio-button label="Cursor" />
            <el-radio-button label="Codex" />
            <el-radio-button label="Claude Code" />
          </el-radio-group>
        </div>
        <el-input
          class="ai-prompt-editor"
          :model-value="workflowAiCodingPrompt"
          type="textarea"
          :rows="22"
          readonly
        />
      </div>
      <template #footer>
        <el-button @click="workflowAiCodingPromptDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="DocumentCopy" @click="copyWorkflowAiCodingPrompt">
          {{ workflowAiCodingPromptCopied ? '已复制' : '复制提示词' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Close, Connection, DocumentCopy, Finished, MagicStick, Operation, Plus, Search, Warning } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bindPageAssistantWorkflow, createWorkflow, generateWorkflowDraft, listAgentEntries, saveWorkflowStudio } from '@/api/workflow'
import { listApiAssets } from '@/api/apiAsset'
import {
  declarePageActionCatalog,
  listPageActionCatalog,
  listPageRegistry,
  type PageActionManualDeclarePayload,
  type PageActionRegistryView,
  type PageRegistryView,
} from '@/api/embedOps'
import { getModelInstances } from '@/api/model'
import type { ModelInstance } from '@/types/model'
import {
  getPageAssistantAccessSessions,
  getLatestPageAssistantAccessSession,
  getPageAssistantOnboardingManifest,
  getScanProjects,
  runPageAssistantAccessSessionChecks,
} from '@/api/scanProject'
import type { AgentEntry, WorkflowDefinitionDraft, WorkflowDraftGenerationResult, WorkflowDraftResource, PageAssistantWorkflowBindingResult } from '@/types/workflow'
import type { ApiAssetItem } from '@/types/apiAsset'
import { buildPageAssistantDraftRequirement, type PageAssistantGoal } from './pageAssistantDraftRequirement'
import { buildPageAssistantWorkflowAiCodingPrompt } from './pageAssistantWorkflowAiCodingPrompt'
import type { AiAccessSession, PageAssistantOnboardingManifest, PageAssistantSessionRequest, PageAssistantSessionSummary, ScanProject } from '@/types/scanProject'
import { buildPageAssistantOnboardingPrompt } from './pageAssistantOnboardingPrompt'

type WizardStepKey = 'connect' | 'page' | 'action' | 'draft' | 'confirm' | 'bind' | 'studio'
type AssistantGoal = 'query' | 'operate' | 'queryThenAction'
type DraftSource = 'NONE' | 'PLATFORM_GENERATED' | 'AI_CODING_RETURNED'

const WORKFLOW_AI_CODING_DRAFT_STEP_KEY = 'workflow-ai-coding-draft'

const route = useRoute()
const router = useRouter()
const projectCode = computed(() => String(route.params.projectCode || ''))
const project = ref<ScanProject | null>(null)
const pageRegistry = ref<PageRegistryView[]>([])
const pageActions = ref<PageActionRegistryView[]>([])
const selectedPageKey = ref('')
const selectedPageIdentity = ref('')
const selectedActions = ref<PageActionRegistryView[]>([])
const apiAssets = ref<ApiAssetItem[]>([])
const selectedApiAssets = ref<ApiAssetItem[]>([])
const modelOptions = ref<ModelInstance[]>([])
const modelInstanceId = ref('')
const focusedStep = ref<WizardStepKey | ''>('connect')
const statusStripRef = ref<HTMLElement | null>(null)
const pagePanelRef = ref<HTMLElement | null>(null)
const sdkHelperVisible = ref(false)
const sdkTemplateCopied = ref(false)
const assistantGoal = ref<AssistantGoal>('query')
const agentName = ref('')
const requirement = ref('')
const loading = ref(false)
const generating = ref(false)
const creatingWorkflow = ref(false)
const bindingAgent = ref(false)
const draftPreview = ref<WorkflowDraftGenerationResult | null>(null)
const draftSource = ref<DraftSource>('NONE')
const createdWorkflowId = ref('')
const bindingResult = ref<PageAssistantWorkflowBindingResult | null>(null)
const pageCopilotAgent = ref<AgentEntry | null>(null)
const manualDialogVisible = ref(false)
const manualSubmitting = ref(false)
const aiPromptDialogVisible = ref(false)
const aiPromptCopied = ref(false)
const aiPromptTool = ref<'Cursor' | 'Codex' | 'Claude Code'>('Cursor')
const workflowAiCodingPromptDialogVisible = ref(false)
const workflowAiCodingPromptCopied = ref(false)
const workflowAiCodingPromptTool = ref<'Cursor' | 'Codex' | 'Claude Code'>('Cursor')
const pageAssistantManifest = ref<PageAssistantOnboardingManifest | null>(null)
const pageAssistantSession = ref<AiAccessSession | null>(null)
const pageAssistantSessions = ref<PageAssistantSessionSummary[]>([])
const selectedPageAssistantAccess = ref<PageAssistantSessionSummary | null>(null)
const pageAssistantManifestLoading = ref(false)
const pageAssistantCheckRunning = ref(false)
const pageAssistantSessionsLoading = ref(false)
const stepTransitionName = ref('')
const stepAttentionName = ref('')
let lastWheelAt = 0
let cardAnimationTimer: ReturnType<typeof setTimeout> | undefined
let cardAttentionTimer: ReturnType<typeof setTimeout> | undefined
let sdkCopyTimer: ReturnType<typeof setTimeout> | undefined
let aiPromptCopyTimer: ReturnType<typeof setTimeout> | undefined
let workflowAiCodingPromptCopyTimer: ReturnType<typeof setTimeout> | undefined
const manualForm = reactive({
  pageKey: '',
  pageName: '',
  routePattern: '',
  actionKey: '',
  title: '',
  description: '',
  confirmRequired: false,
  inputSchemaText: '{\n  "type": "object",\n  "properties": {}\n}',
  sampleArgsText: '{}',
})
const assistantGoalOptions: Array<{ value: AssistantGoal; icon: Component; tone: 'query' | 'operate' | 'link'; title: string; desc: string }> = [
  { value: 'query', icon: Search, tone: 'query', title: '查询/筛选助手', desc: '提取条件并触发页面查询' },
  { value: 'operate', icon: Operation, tone: 'operate', title: '页面操作助手', desc: '围绕页面动作执行操作' },
  { value: 'queryThenAction', icon: Connection, tone: 'link', title: '查询后联动', desc: '先查询再触发后续动作' },
]

const filteredActions = computed(() => pageActions.value.filter((action) => !selectedPageKey.value || action.pageKey === selectedPageKey.value))
const activeActionCount = computed(() => pageActions.value.filter((action) => action.status === 'ACTIVE').length)
const isAiCodingWorkflowSelected = computed(() =>
  draftSource.value === 'AI_CODING_RETURNED' && Boolean(createdWorkflowId.value),
)
const isDraftStepComplete = computed(() => {
  if (draftSource.value === 'AI_CODING_RETURNED') {
    return Boolean(createdWorkflowId.value)
  }
  return Boolean(draftPreview.value)
})
const activeStep = computed(() => {
  if (!pageRegistry.value.length && !pageActions.value.length) return 'connect'
  if (!selectedPageKey.value) return 'page'
  if (!selectedActions.value.length) return 'action'
  if (!isDraftStepComplete.value) return 'draft'
  if (isAiCodingWorkflowSelected.value) {
    if (!bindingResult.value) return 'bind'
    return 'studio'
  }
  if (!createdWorkflowId.value) return 'confirm'
  if (!bindingResult.value) return 'bind'
  return 'studio'
})
const displayedStep = computed(() => focusedStep.value || activeStep.value)
const stepKeys: WizardStepKey[] = ['connect', 'page', 'action', 'draft', 'confirm', 'bind', 'studio']
const displayedStepIndex = computed(() => stepKeys.indexOf(displayedStep.value))
const canGoPrev = computed(() => displayedStepIndex.value > 0)
const canGoNext = computed(() => displayedStepIndex.value >= 0 && displayedStepIndex.value < stepKeys.length - 1)
const selectedPage = computed(() =>
  pageRegistry.value.find((page) => selectedPageIdentity.value
    ? pageIdentity(page) === selectedPageIdentity.value
    : page.pageKey === selectedPageKey.value) || null
)
const draftNodeCount = computed(() => draftPreview.value?.graphSpec?.nodes?.length || 0)
const draftEdgeCount = computed(() => draftPreview.value?.graphSpec?.edges?.length || 0)
const draftIssues = computed(() => [
  ...(draftPreview.value?.validationErrors || []),
  ...(draftPreview.value?.placeholderNodes || []).map((item) => `${item.label || item.nodeId}: ${item.reason || '节点仍需配置'}`),
])
const draftIssueCount = computed(() => draftIssues.value.length)
const selectedModelLabel = computed(() => {
  const model = modelOptions.value.find((item) => item.id === modelInstanceId.value)
  return model ? modelOptionLabel(model) : modelInstanceId.value || '未选择'
})
const steps = computed(() => [
  { index: 1, key: 'connect' as const, title: '接入准备', desc: 'AI 或手动接入页面动作', done: pageRegistry.value.length > 0 || pageActions.value.length > 0 },
  { index: 2, key: 'page' as const, title: '选择页面', desc: selectedPage.value?.name || selectedPageKey.value || '定位业务页面', done: Boolean(selectedPageKey.value) },
  { index: 3, key: 'action' as const, title: '选择动作', desc: '声明可执行能力', done: selectedActions.value.length > 0 },
  {
    index: 4,
    key: 'draft' as const,
    title: '生成 / 选择草稿',
    desc: isAiCodingWorkflowSelected.value ? 'AI Coding Workflow' : '构建 GraphSpec',
    done: isDraftStepComplete.value,
  },
  {
    index: 5,
    key: 'confirm' as const,
    title: '确认草稿',
    desc: '创建 Workflow',
    done: isAiCodingWorkflowSelected.value ? isAiCodingWorkflowSelected.value : Boolean(createdWorkflowId.value),
  },
  { index: 6, key: 'bind' as const, title: '挂载智能体', desc: '绑定 PAGE_COPILOT', done: Boolean(bindingResult.value) },
  { index: 7, key: 'studio' as const, title: '进入 Studio', desc: '预览保存发布', done: false },
])
const stats = computed(() => [
  { key: 'page', icon: '页', label: '页面', value: String(pageRegistry.value.length) },
  { key: 'action', icon: '动', label: '动作', value: String(pageActions.value.length) },
  { key: 'active', icon: 'A', label: 'ACTIVE', value: String(activeActionCount.value) },
  { key: 'api', icon: 'API', label: 'API 资产', value: String(apiAssets.value.length) },
])
const pageAssistantAccessGroups = computed(() => {
  const groups = [
    { key: 'WAITING_TARGET', title: '待确认', desc: '等待 Cursor 绑定目标页面', items: [] as PageAssistantSessionSummary[] },
    { key: 'IN_PROGRESS', title: '接入中', desc: 'AI 正在回传步骤进度', items: [] as PageAssistantSessionSummary[] },
    { key: 'COMPLETED', title: '已完成', desc: '可直接带入创建助手', items: [] as PageAssistantSessionSummary[] },
    { key: 'BLOCKED', title: '失败/阻塞', desc: '需要人工处理异常', items: [] as PageAssistantSessionSummary[] },
  ]
  const byKey = new Map(groups.map((group) => [group.key, group]))
  for (const session of pageAssistantSessions.value) {
    const state = session.completionState || 'IN_PROGRESS'
    const group = byKey.get(state) || byKey.get('IN_PROGRESS')
    group?.items.push(session)
  }
  return groups
})
const pageAssistantAccessCount = computed(() => pageAssistantSessions.value.length)
const sdkTemplate = computed(() => {
  const pageKey = selectedPageKey.value || manualForm.pageKey || 'teamArchive.list'
  const actionKey = selectedActions.value[0]?.actionKey || manualForm.actionKey || 'qmssmp.teamArchive.search'
  return `pageBridge.registerAction({\n  pageKey: '${pageKey}',\n  actionKey: '${actionKey}',\n  title: '查询班组档案',\n  inputSchema: {\n    type: 'object',\n    properties: {\n      teamName: { type: 'string', description: '班组名称' }\n    }\n  },\n  sampleArgs: { teamName: '一班' },\n  handler: async (args) => {\n    // 调用当前页面已有查询函数，并返回执行结果\n    return await queryTeams(args)\n  }\n})`
})
const highlightedSdkTemplate = computed(() => highlightSdkCode(sdkTemplate.value))
const pageAssistantPromptActions = computed(() => selectedActions.value.length ? selectedActions.value : filteredActions.value)
const pageAssistantActionKeys = computed(() => {
  const keys = pageAssistantPromptActions.value
    .map((action) => action.actionKey)
    .filter((key): key is string => Boolean(key))
  return Array.from(new Set(keys))
})
const pageAssistantSessionSteps = computed(() => pageAssistantSession.value?.steps || [])
const pageAssistantAccessDetailVisible = computed({
  get: () => Boolean(selectedPageAssistantAccess.value),
  set: (visible: boolean) => {
    if (!visible) selectedPageAssistantAccess.value = null
  },
})
const pageAssistantProgressText = computed(() => {
  const session = pageAssistantSession.value
  if (!session) return '未开始'
  if (!session.totalSteps) return session.status || 'OPEN'
  return `${session.completedSteps}/${session.totalSteps} · ${session.status}`
})
const aiCodingAccessState = computed(() => {
  const access = pageAssistantManifest.value?.aiCodingAccess
  if (!access?.enabled) return '未启用'
  return access.accessKey ? '已启用' : '已启用，未生成秘钥'
})
const pageAssistantManifestUrlWithKey = computed(() => withAiCodingKey(pageAssistantManifest.value?.endpoints.manifestUrl) || '')
const pageAssistantRegisterPageUrlWithKey = computed(() => withAiCodingKey(pageAssistantManifest.value?.endpoints.registerPageUrl) || '')
const pageAssistantScaffoldCommand = computed(() => {
  const fromManifest = pageAssistantManifest.value?.scaffold?.scaffoldCommand
  if (fromManifest) return fromManifest
  const manifestUrl = pageAssistantManifestUrlWithKey.value || '<页面助手接入清单 URL>'
  return `.\\scripts\\reachai-page-assistant.ps1 scaffold -ManifestUrl "${manifestUrl}" -Framework angular -OutputDir ".\\src\\app\\shared\\reachai"`
})
const pageAssistantVerifyCommand = computed(() => {
  const fromManifest = pageAssistantManifest.value?.scaffold?.verifyCommand
  if (fromManifest) return fromManifest
  const manifestUrl = pageAssistantManifestUrlWithKey.value || '<页面助手接入清单 URL>'
  const routePattern = selectedPage.value?.routePattern || '<目标路由>'
  const pageKey = selectedPage.value?.pageKey || selectedPageKey.value || '<pageKey>'
  return `.\\scripts\\reachai-page-assistant.ps1 verify -ManifestUrl "${manifestUrl}" -FrontendUrl "<业务前端地址>" -Route "${routePattern}" -PageKey "${pageKey}"`
})
const pageAssistantOnboardingPrompt = computed(() => buildPageAssistantOnboardingPrompt({
  toolName: aiPromptTool.value,
  platformUrl: window.location.origin,
  project: {
    id: project.value?.id,
    projectCode: project.value?.projectCode || projectCode.value,
    name: project.value?.name || projectCode.value,
    appKey: pageAssistantManifest.value?.project.registryAppKey || project.value?.registryAppKey,
  },
  page: selectedPage.value
    ? {
      pageKey: selectedPage.value.pageKey,
      name: selectedPage.value.name,
      routePattern: selectedPage.value.routePattern,
    }
    : {
      pageKey: selectedPageKey.value,
      name: selectedPageKey.value,
      routePattern: '',
    },
  actions: pageAssistantPromptActions.value,
  progress: {
    aiCodingAccessKey: pageAssistantManifest.value?.aiCodingAccess.accessKey,
    appSecretEnv: pageAssistantManifest.value?.security.appSecretEnv,
    sessionId: pageAssistantSession.value?.sessionId || pageAssistantManifest.value?.session.sessionId,
    manifestUrl: withAiCodingKey(pageAssistantManifest.value?.endpoints.manifestUrl),
    latestSessionUrl: withAiCodingKey(pageAssistantManifest.value?.endpoints.latestSessionUrl),
    stepReportUrl: withAiCodingKey(pageAssistantManifest.value?.endpoints.stepReportUrl),
    targetBindUrl: withAiCodingKey(pageAssistantManifest.value?.endpoints.targetBindUrl),
    catalogSyncUrl: withAiCodingKey(pageAssistantManifest.value?.endpoints.catalogSyncUrl),
    checksRunUrl: withAiCodingKey(pageAssistantManifest.value?.endpoints.checksRunUrl),
    registerPageUrl: pageAssistantRegisterPageUrlWithKey.value,
    skillPackageUrl: pageAssistantManifest.value?.endpoints.skillPackageUrl || pageAssistantManifest.value?.scaffold?.skillPackageUrl,
    scriptDownloadUrl: pageAssistantManifest.value?.endpoints.scriptDownloadUrl || pageAssistantManifest.value?.scaffold?.scriptDownloadUrl,
    helperScriptPath: pageAssistantManifest.value?.scaffold?.helperScriptPath || 'scripts/reachai-page-assistant.ps1',
    scaffoldCommand: pageAssistantScaffoldCommand.value,
    verifyCommand: pageAssistantVerifyCommand.value,
    bridgeApiGlobal: pageAssistantManifest.value?.pageActionContract?.bridgeApi?.global
      || `window.${pageAssistantManifest.value?.pageActionContract?.bridgeGlobal || '__REACHAI_PAGE_BRIDGE__'}`,
  },
}))

const workflowAiCodingReportUrl = computed(() => {
  const sessionId = pageAssistantSession.value?.sessionId || pageAssistantManifest.value?.session.sessionId
  if (!project.value?.id || !sessionId) return ''
  const base = `${window.location.origin}/api/ai-assist/projects/${project.value.id}/page-assistant/sessions/${sessionId}/workflow-ai-coding-result`
  return withAiCodingKey(base) || base
})

const workflowAiCodingDraftStep = computed(() =>
  pageAssistantSession.value?.steps?.find((step) => step.stepKey === WORKFLOW_AI_CODING_DRAFT_STEP_KEY) || null,
)

const workflowAiCodingDraftEvidence = computed(() => {
  const evidence = workflowAiCodingDraftStep.value?.evidence || {}
  return {
    workflowId: typeof evidence.workflowId === 'string' ? evidence.workflowId : '',
    keySlug: typeof evidence.keySlug === 'string' ? evidence.keySlug : '',
    workflowName: typeof evidence.workflowName === 'string' ? evidence.workflowName : '',
    studioUrl: typeof evidence.studioUrl === 'string' ? evidence.studioUrl : '',
    validation: (evidence.validation && typeof evidence.validation === 'object'
      ? evidence.validation
      : {}) as Record<string, unknown>,
    pageAssistantValidation: (evidence.pageAssistantValidation && typeof evidence.pageAssistantValidation === 'object'
      ? evidence.pageAssistantValidation
      : {}) as Record<string, unknown>,
    runtimeVerification: (evidence.runtimeVerification && typeof evidence.runtimeVerification === 'object'
      ? evidence.runtimeVerification
      : {}) as Record<string, unknown>,
  }
})

function formatValidationOverallStatus(summary: Record<string, unknown>) {
  const status = String(summary.overallStatus || '').trim()
  const errors = Array.isArray(summary.errors) ? summary.errors.filter(Boolean) : []
  const warnings = Array.isArray(summary.warnings) ? summary.warnings.filter(Boolean) : []
  if (!status && !errors.length && !warnings.length) return ''
  const parts = [status || 'UNKNOWN']
  if (errors.length) parts.push(`${errors.length} errors`)
  if (warnings.length) parts.push(`${warnings.length} warnings`)
  return parts.join(' · ')
}

const workflowAiCodingValidationSummary = computed(() =>
  formatValidationOverallStatus(workflowAiCodingDraftEvidence.value.validation),
)

const workflowAiCodingPageAssistantValidationSummary = computed(() => {
  const summary = workflowAiCodingDraftEvidence.value.pageAssistantValidation
  const base = formatValidationOverallStatus(summary)
  const matched = Array.isArray(summary.matchedActions) ? summary.matchedActions.filter(Boolean) : []
  const missing = Array.isArray(summary.missingActions) ? summary.missingActions.filter(Boolean) : []
  if (!base && !matched.length && !missing.length) return ''
  const parts = [base || 'UNKNOWN']
  if (matched.length) parts.push(`matched ${matched.length}`)
  if (missing.length) parts.push(`missing ${missing.length}`)
  return parts.join(' · ')
})

const workflowAiCodingRuntimeVerificationSummary = computed(() => {
  const verification = workflowAiCodingDraftEvidence.value.runtimeVerification
  const browserRuntime = verification.browserRuntime && typeof verification.browserRuntime === 'object'
    ? verification.browserRuntime as Record<string, unknown>
    : verification
  const status = String(browserRuntime.status || '').trim()
  const checkedActions = Array.isArray(browserRuntime.checkedActions)
    ? browserRuntime.checkedActions.filter(Boolean)
    : Array.isArray(browserRuntime.invokedActions)
      ? browserRuntime.invokedActions.filter(Boolean)
      : []
  const parts = [status || 'UNKNOWN']
  if (checkedActions.length) parts.push(`${checkedActions.length} actions`)
  const message = String(browserRuntime.message || '').trim()
  if (message) parts.push(message)
  return Object.keys(browserRuntime).length ? parts.join(' · ') : ''
})

const workflowAiCodingPrompt = computed(() => buildPageAssistantWorkflowAiCodingPrompt({
  toolName: workflowAiCodingPromptTool.value,
  platformUrl: window.location.origin,
  project: {
    id: project.value?.id,
    projectCode: project.value?.projectCode || projectCode.value,
    name: project.value?.name || projectCode.value,
    registryAppKey: pageAssistantManifest.value?.project.registryAppKey || project.value?.registryAppKey,
  },
  aiCodingAccess: {
    enabled: pageAssistantManifest.value?.aiCodingAccess?.enabled,
    accessKey: pageAssistantManifest.value?.aiCodingAccess?.accessKey,
    stateLabel: aiCodingAccessState.value,
  },
  sessionId: pageAssistantSession.value?.sessionId || pageAssistantManifest.value?.session.sessionId,
  reportUrl: workflowAiCodingReportUrl.value,
  page: {
    pageKey: selectedPage.value?.pageKey || selectedPageKey.value,
    pageName: selectedPage.value?.name || selectedPageKey.value,
    routePattern: selectedPage.value?.routePattern || '',
  },
  actions: selectedActions.value.map((action) => ({
    actionKey: action.actionKey,
    title: action.title,
    description: action.description,
    confirmRequired: Boolean(action.confirmRequired),
  })),
  requirement: requirement.value || defaultRequirement(),
  workflowName: pageAssistantWorkflowName(),
  workflowKeySlug: pageAssistantWorkflowKeySlug(),
  modelInstanceId: modelInstanceId.value,
  skillPackageUrl: `${window.location.origin}/api/ai-assist/skills/workflow-ai-coding/latest.zip`,
}))

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function highlightSdkCode(value: string) {
  const escaped = escapeHtml(value)
  const tokenPattern = /(\/\/[^\n]*|'[^']*'|\b(?:async|await|return)\b|\b(?:pageBridge|registerAction|queryTeams)\b|\b(?:pageKey|actionKey|title|inputSchema|type|properties|teamName|description|sampleArgs|handler)\b|[{}()[\],:])/g
  return escaped.replace(tokenPattern, (token) => {
    if (token.startsWith('//')) return `<span class="code-comment">${token}</span>`
    if (token.startsWith("'")) return `<span class="code-string">${token}</span>`
    if (/^(async|await|return)$/.test(token)) return `<span class="code-keyword">${token}</span>`
    if (/^(pageBridge|registerAction|queryTeams)$/.test(token)) return `<span class="code-function">${token}</span>`
    if (/^(pageKey|actionKey|title|inputSchema|type|properties|teamName|description|sampleArgs|handler)$/.test(token)) {
      return `<span class="code-property">${token}</span>`
    }
    return `<span class="code-punctuation">${token}</span>`
  })
}

watch([selectedPage, assistantGoal], () => {
  agentName.value = agentName.value || `${selectedPage.value?.name || project.value?.name || projectCode.value}页面助手`
  requirement.value = defaultRequirement()
})

watch(aiPromptDialogVisible, (visible) => {
  if (visible) {
    void loadPageAssistantManifest()
  }
})

watch([selectedPageKey, pageAssistantActionKeys, aiPromptTool], () => {
  if (aiPromptDialogVisible.value) {
    void loadPageAssistantManifest({ silent: true })
  }
})

function clearPersistedWizardState(options: { includePreview?: boolean } = {}) {
  if (options.includePreview !== false) {
    draftPreview.value = null
  }
  createdWorkflowId.value = ''
  draftSource.value = 'NONE'
  bindingResult.value = null
  pageCopilotAgent.value = null
}

function resetWizardProgressFromDraft() {
  clearPersistedWizardState()
}

function actionCount(pageKey: string) {
  return pageActions.value.filter((action) => action.pageKey === pageKey).length
}

function pageIdentity(page: PageRegistryView) {
  return String(page.id || page.pageKey)
}

function actionRowKey(action: PageActionRegistryView) {
  return String(action.id || action.actionKey)
}

function isActionSelected(action: PageActionRegistryView) {
  const key = actionRowKey(action)
  return selectedActions.value.some((item) => actionRowKey(item) === key)
}

function toggleActionSelection(action: PageActionRegistryView) {
  const key = actionRowKey(action)
  selectedActions.value = isActionSelected(action)
    ? selectedActions.value.filter((item) => actionRowKey(item) !== key)
    : [...selectedActions.value, action]
  resetWizardProgressFromDraft()
}

function selectAllFilteredActions() {
  const visibleKeys = new Set(filteredActions.value.map(actionRowKey))
  const kept = selectedActions.value.filter((action) => !visibleKeys.has(actionRowKey(action)))
  selectedActions.value = [...kept, ...filteredActions.value]
  resetWizardProgressFromDraft()
}

function clearFilteredActions() {
  const visibleKeys = new Set(filteredActions.value.map(actionRowKey))
  selectedActions.value = selectedActions.value.filter((action) => !visibleKeys.has(actionRowKey(action)))
  resetWizardProgressFromDraft()
}

function requiredStepComplete(key: WizardStepKey) {
  if (key === 'page') return Boolean(selectedPageKey.value)
  if (key === 'action') return selectedActions.value.length > 0
  if (key === 'draft') {
    if (draftSource.value === 'AI_CODING_RETURNED') {
      return Boolean(createdWorkflowId.value)
    }
    return Boolean(draftPreview.value)
  }
  if (key === 'confirm') {
    if (draftSource.value === 'AI_CODING_RETURNED') {
      return true
    }
    return Boolean(createdWorkflowId.value)
  }
  if (key === 'bind') return Boolean(bindingResult.value)
  return true
}

function firstBlockingStep(targetKey: WizardStepKey) {
  const targetIndex = stepKeys.indexOf(targetKey)
  if (targetIndex <= displayedStepIndex.value) return null
  const requiredKeys: WizardStepKey[] = ['page', 'action', 'draft', 'confirm', 'bind']
  return requiredKeys.find((key) => stepKeys.indexOf(key) < targetIndex && !requiredStepComplete(key)) || null
}

function triggerStepAttention(delay = 0) {
  if (cardAttentionTimer) {
    clearTimeout(cardAttentionTimer)
  }
  stepAttentionName.value = ''
  cardAttentionTimer = setTimeout(() => {
    stepAttentionName.value = 'step-shake'
    cardAttentionTimer = setTimeout(() => {
      stepAttentionName.value = ''
      cardAttentionTimer = undefined
    }, 520)
  }, delay)
}

function focusStepCard(key: WizardStepKey, options: { attention?: boolean } = {}) {
  const fromIndex = displayedStepIndex.value
  const toIndex = stepKeys.indexOf(key)
  if (cardAnimationTimer) {
    clearTimeout(cardAnimationTimer)
  }
  stepTransitionName.value = toIndex === fromIndex ? '' : toIndex > fromIndex ? 'step-slide-down' : 'step-slide-up'
  focusedStep.value = key
  const shouldDelayAttention = options.attention && toIndex !== fromIndex
  if (stepTransitionName.value) {
    cardAnimationTimer = setTimeout(() => {
      stepTransitionName.value = ''
      cardAnimationTimer = undefined
    }, 460)
  }
  if (options.attention) {
    triggerStepAttention(shouldDelayAttention ? 470 : 0)
  }
  const target = key === 'connect' ? statusStripRef.value : pagePanelRef.value
  nextTick(() => {
    const scrollParent = target?.closest?.('.main-content') as HTMLElement | null
    if (!target || !scrollParent || target.offsetHeight > scrollParent.clientHeight) return
    target.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  })
}

function selectStep(key: WizardStepKey) {
  const blocker = firstBlockingStep(key)
  if (blocker) {
    focusStepCard(blocker, { attention: true })
    return false
  }
  if (['page', 'action', 'draft'].includes(key) && createdWorkflowId.value && !bindingResult.value) {
    const wasAiCoding = draftSource.value === 'AI_CODING_RETURNED'
    createdWorkflowId.value = ''
    draftSource.value = 'NONE'
    pageCopilotAgent.value = null
    ElMessage.warning(
      wasAiCoding
        ? '已选择的 AI Coding Workflow 状态已清空，请重新选择或生成'
        : '已创建但未挂载的 Workflow 状态已清空，请重新确认创建',
    )
  }
  focusStepCard(key)
  return true
}

function goStepByOffset(offset: number) {
  const nextIndex = displayedStepIndex.value + offset
  if (nextIndex < 0 || nextIndex >= stepKeys.length) return
  const nextKey = stepKeys[nextIndex]
  selectStep(nextKey)
}

function goPrevStep() {
  goStepByOffset(-1)
}

function goNextStep() {
  goStepByOffset(1)
}

function handleWizardWheel(event: WheelEvent) {
  if ((event.target as HTMLElement | null)?.closest?.('.focus-panel')) return
  if (Math.abs(event.deltaY) < 24) return
  const now = Date.now()
  if (now - lastWheelAt < 520) return
  lastWheelAt = now
  event.preventDefault()
  goStepByOffset(event.deltaY > 0 ? 1 : -1)
}

function selectPage(page: PageRegistryView) {
  const pageKey = page.pageKey
  selectedPageKey.value = pageKey
  selectedPageIdentity.value = pageIdentity(page)
  selectedActions.value = selectedActions.value.filter((action) => action.pageKey === pageKey)
  resetWizardProgressFromDraft()
  selectStep('action')
}

async function loadAll() {
  if (!projectCode.value) return
  loading.value = true
  try {
    const [projects, pages, actions, assets, models] = await Promise.all([
      getScanProjects(),
      listPageRegistry({ projectCode: projectCode.value, limit: 200 }),
      listPageActionCatalog({ projectCode: projectCode.value, limit: 500 }),
      listApiAssets({ projectCode: projectCode.value, page: 1, pageSize: 100, enabled: true }),
      getModelInstances({ modelType: 'LLM' }),
    ])
    project.value = projects.data.find((item) => item.projectCode === projectCode.value) || null
    pageRegistry.value = pages.data || []
    pageActions.value = actions.data || []
    apiAssets.value = (assets.data.items || []).filter((item) => Boolean(item.globalToolName))
    modelOptions.value = normalizeModelInstanceList(models.data).filter(isActiveModelInstance)
    modelInstanceId.value = modelInstanceId.value || modelOptions.value[0]?.id || ''
    if (selectedPageIdentity.value && !pageRegistry.value.some((page) => pageIdentity(page) === selectedPageIdentity.value)) {
      selectedPageIdentity.value = ''
      selectedPageKey.value = ''
      selectedActions.value = []
      resetWizardProgressFromDraft()
    }
    if (project.value?.id) {
      await loadPageAssistantSessions({ silent: true })
    }
    agentName.value = agentName.value || `${project.value?.name || projectCode.value}页面助手`
    requirement.value = requirement.value || defaultRequirement()
  } catch (error) {
    ElMessage.error((error as Error).message || '加载页面助手向导失败')
  } finally {
    loading.value = false
  }
}

function defaultRequirement() {
  return buildPageAssistantDraftRequirement({
    pageName: selectedPage.value?.name || selectedPageKey.value || '当前业务页面',
    assistantGoal: assistantGoal.value as PageAssistantGoal,
    actions: selectedActions.value.map((item) => ({
      actionKey: item.actionKey,
      title: item.title,
      confirmRequired: Boolean(item.confirmRequired),
    })),
  })
}

function workflowKeyPart(value: unknown, fallback: string) {
  const normalized = String(value || '')
    .trim()
    .replace(/\./g, '-')
    .replace(/[^A-Za-z0-9_-]+/g, '-')
    .replace(/[-_]{2,}/g, '-')
    .replace(/^[^A-Za-z0-9]+/, '')
    .replace(/[^A-Za-z0-9]+$/, '')
    .toLowerCase()
  return (normalized || fallback).slice(0, 48)
}

function pageAssistantWorkflowKeySlug() {
  const projectPart = workflowKeyPart(projectCode.value, 'project')
  const pagePart = workflowKeyPart(selectedPageKey.value || selectedPage.value?.pageKey || selectedPage.value?.name, 'page')
  const suffix = Date.now().toString(36)
  return `${projectPart}-${pagePart}-page-assistant-${suffix}`.slice(0, 128)
}

function pageAssistantWorkflowName() {
  return agentName.value.trim() || `${selectedPage.value?.name || selectedPageKey.value || '页面'}页面助手 Workflow`
}

function parseJsonObject(text: string, label: string) {
  const parsed = JSON.parse(text || '{}')
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${label} 必须是 JSON 对象`)
  }
  return parsed as Record<string, unknown>
}

async function submitManualAction() {
  try {
    const payload: PageActionManualDeclarePayload = {
      projectCode: projectCode.value,
      appId: projectCode.value,
      pageKey: manualForm.pageKey.trim(),
      pageName: manualForm.pageName.trim(),
      routePattern: manualForm.routePattern.trim(),
      actionKey: manualForm.actionKey.trim(),
      title: manualForm.title.trim(),
      description: manualForm.description.trim(),
      confirmRequired: manualForm.confirmRequired,
      inputSchema: parseJsonObject(manualForm.inputSchemaText, 'inputSchema'),
      outputSchema: { type: 'object' },
      sampleArgs: parseJsonObject(manualForm.sampleArgsText, 'sampleArgs'),
      allowedAgentIds: [],
      status: 'ACTIVE',
    }
    if (!payload.pageKey || !payload.actionKey) {
      ElMessage.warning('请填写 pageKey 和 actionKey')
      return
    }
    manualSubmitting.value = true
    const { data } = await declarePageActionCatalog(payload)
    ElMessage.success('页面动作草案已保存')
    manualDialogVisible.value = false
    await loadAll()
    selectedPageKey.value = data.page.pageKey
    selectedPageIdentity.value = pageIdentity(data.page)
    focusedStep.value = 'action'
  } catch (error) {
    ElMessage.error((error as Error).message || '保存页面动作草案失败')
  } finally {
    manualSubmitting.value = false
  }
}

function pageActionToResource(action: PageActionRegistryView): WorkflowDraftResource {
  const page = pageRegistry.value.find((item) => item.pageKey === action.pageKey)
  return {
    kind: 'PAGE_ACTION',
    name: action.actionKey,
    qualifiedName: `${action.pageKey}/${action.actionKey}`,
    projectCode: action.projectCode,
    description: action.title || action.description || action.actionKey,
    metadata: {
      pageKey: action.pageKey,
      routePattern: page?.routePattern || '',
      actionKey: action.actionKey,
      confirmRequired: Boolean(action.confirmRequired),
      inputSchema: safeJson(action.inputSchemaJson),
      outputSchema: safeJson(action.outputSchemaJson),
      sampleArgs: safeJson(action.sampleArgsJson),
    },
  }
}

function apiAssetToResource(asset: ApiAssetItem): WorkflowDraftResource {
  return {
    kind: 'TOOL',
    name: asset.globalToolName || asset.name,
    qualifiedName: asset.globalToolQualifiedName || asset.globalToolName || asset.name,
    definitionId: asset.globalToolDefinitionId || null,
    projectCode: asset.projectCode,
    description: asset.aiDescription || asset.description || asset.name,
    metadata: {
      endpointPath: asset.endpointPath,
      httpMethod: asset.httpMethod,
      parameters: asset.parameters,
    },
  }
}

function safeJson(text?: string) {
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return {}
  }
}

function normalizeModelInstanceList(payload: unknown): ModelInstance[] {
  if (Array.isArray(payload)) {
    return payload as ModelInstance[]
  }
  if (payload !== null && typeof payload === 'object' && 'data' in payload) {
    const wrapped = (payload as { data?: unknown }).data
    return Array.isArray(wrapped) ? (wrapped as ModelInstance[]) : []
  }
  return []
}

function isActiveModelInstance(item: ModelInstance) {
  return String(item.status ?? '').toUpperCase() === 'ACTIVE'
}

function modelOptionLabel(model: ModelInstance) {
  const modelName = model.modelName || model.id
  return `${model.name || model.id} · ${model.provider || '未知厂商'} · ${modelName}`
}

function pageAssistantSessionRequest(): PageAssistantSessionRequest {
  return {
    toolName: aiPromptTool.value,
    pageKey: selectedPage.value?.pageKey || selectedPageKey.value || undefined,
    routePattern: selectedPage.value?.routePattern || undefined,
    actionKeys: pageAssistantActionKeys.value,
  }
}

function withAiCodingKey(url?: string | null) {
  const value = url?.trim()
  const key = pageAssistantManifest.value?.aiCodingAccess.enabled
    ? pageAssistantManifest.value?.aiCodingAccess.accessKey?.trim()
    : ''
  if (!value || !key || value.includes('aiCodingKey=')) return value || undefined
  const separator = value.includes('?') ? '&' : '?'
  return `${value}${separator}aiCodingKey=${encodeURIComponent(key)}`
}

function stepStatusTagType(status: string) {
  if (status === 'PASS') return 'success'
  if (status === 'WARN' || status === 'SKIPPED') return 'warning'
  if (status === 'FAIL') return 'danger'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}

function pageAccessStateLabel(state?: string | null) {
  const value = String(state || '').toUpperCase()
  if (value === 'WAITING_TARGET') return '待确认'
  if (value === 'COMPLETED') return '已完成'
  if (value === 'BLOCKED') return '阻塞'
  return '接入中'
}

function pageAccessStateTagType(state?: string | null) {
  const value = String(state || '').toUpperCase()
  if (value === 'COMPLETED') return 'success'
  if (value === 'BLOCKED') return 'danger'
  if (value === 'WAITING_TARGET') return 'warning'
  return 'primary'
}

function pageAccessTitle(session: PageAssistantSessionSummary) {
  const page = pageRegistry.value.find((item) => item.pageKey === session.targetPageKey)
  return page?.name || session.targetPageKey || '待确认业务页面'
}

function pageActionKeysForSession(session: PageAssistantSessionSummary) {
  return pageActions.value
    .filter((action) => action.pageKey === session.targetPageKey)
    .map((action) => action.actionKey)
    .filter((key): key is string => Boolean(key))
}

function formatEvidence(evidence: Record<string, unknown>) {
  return JSON.stringify(evidence || {}, null, 2)
}

function openAiPromptDialog() {
  if (aiPromptDialogVisible.value) {
    void loadPageAssistantManifest()
    return
  }
  aiPromptDialogVisible.value = true
}

function openWorkflowAiCodingPromptDialog() {
  if (!selectedPageKey.value && !selectedPage.value) {
    ElMessage.warning('请先选择页面')
    return
  }
  if (!selectedActions.value.length) {
    ElMessage.warning('请先选择至少一个页面动作')
    return
  }
  workflowAiCodingPromptDialogVisible.value = true
  void loadPageAssistantManifest({ silent: true }).then(() => refreshWorkflowAiCodingDraftStatus())
}

async function refreshWorkflowAiCodingDraftStatus() {
  await refreshPageAssistantSession()
}

function openAiCodingWorkflowStudio() {
  const workflowId = workflowAiCodingDraftEvidence.value.workflowId
  if (!workflowId) {
    ElMessage.warning('尚未收到 AI Coding 回传的 workflowId')
    return
  }
  const studioUrl = workflowAiCodingDraftEvidence.value.studioUrl || `/workflows/${workflowId}/studio`
  router.push(studioUrl.startsWith('/') ? studioUrl : `/${studioUrl}`)
}

async function confirmSwitchToPlatformGeneration() {
  try {
    await ElMessageBox.confirm(
      '这不会删除 AI Coding 已创建的 Workflow，但会取消当前选择并重新生成平台草稿。',
      '改用平台生成',
      { type: 'warning', confirmButtonText: '确认改用', cancelButtonText: '取消' },
    )
    createdWorkflowId.value = ''
    bindingResult.value = null
    pageCopilotAgent.value = null
    draftPreview.value = null
    draftSource.value = 'NONE'
    return true
  } catch {
    return false
  }
}

async function useAiCodingWorkflowDraft() {
  const workflowId = workflowAiCodingDraftEvidence.value.workflowId
  if (!workflowId) {
    ElMessage.warning('尚未收到 AI Coding 回传的 workflowId')
    return
  }
  draftPreview.value = null
  createdWorkflowId.value = workflowId
  draftSource.value = 'AI_CODING_RETURNED'
  bindingResult.value = null
  workflowAiCodingPromptDialogVisible.value = false
  await loadPageCopilotAgent()
  ElMessage.success('已选用 AI Coding 生成的 Workflow，请继续挂载智能体')
  selectStep('bind')
}

async function copyWorkflowAiCodingPrompt() {
  try {
    await navigator.clipboard.writeText(workflowAiCodingPrompt.value)
    workflowAiCodingPromptCopied.value = true
    if (workflowAiCodingPromptCopyTimer) {
      clearTimeout(workflowAiCodingPromptCopyTimer)
    }
    workflowAiCodingPromptCopyTimer = setTimeout(() => {
      workflowAiCodingPromptCopied.value = false
      workflowAiCodingPromptCopyTimer = undefined
    }, 1600)
    ElMessage.success('Workflow AI Coding 提示词已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择提示词')
  }
}

async function loadPageAssistantSessions(options: { silent?: boolean } = {}) {
  if (!project.value?.id) {
    if (!options.silent) ElMessage.warning('项目详情尚未加载完成，请稍后再试')
    return
  }
  pageAssistantSessionsLoading.value = true
  try {
    const { data } = await getPageAssistantAccessSessions(project.value.id)
    pageAssistantSessions.value = data || []
    if (selectedPageAssistantAccess.value) {
      selectedPageAssistantAccess.value = pageAssistantSessions.value.find((item) =>
        item.sessionId === selectedPageAssistantAccess.value?.sessionId) || selectedPageAssistantAccess.value
    }
  } catch (error) {
    if (!options.silent) {
      ElMessage.warning((error as Error).message || '加载页面接入进度失败')
    }
  } finally {
    pageAssistantSessionsLoading.value = false
  }
}

async function loadPageAssistantManifest(options: { silent?: boolean } = {}) {
  if (!project.value?.id) {
    if (!options.silent) ElMessage.warning('项目详情尚未加载完成，请稍后再试')
    return
  }
  pageAssistantManifestLoading.value = true
  try {
    const { data } = await getPageAssistantOnboardingManifest(project.value.id, pageAssistantSessionRequest())
    pageAssistantManifest.value = data
    pageAssistantSession.value = data.session
    await loadPageAssistantSessions({ silent: true })
  } catch (error) {
    if (!options.silent) {
      ElMessage.warning((error as Error).message || '获取页面助手 AI 接入会话失败')
    }
  } finally {
    pageAssistantManifestLoading.value = false
  }
}

async function refreshPageAssistantSession() {
  if (!project.value?.id) {
    ElMessage.warning('项目详情尚未加载完成，请稍后再试')
    return
  }
  pageAssistantManifestLoading.value = true
  try {
    const { data } = await getLatestPageAssistantAccessSession(project.value.id, selectedPage.value?.pageKey || selectedPageKey.value)
    pageAssistantSession.value = data
    await loadPageAssistantSessions({ silent: true })
  } catch {
    await loadPageAssistantManifest({ silent: true })
  } finally {
    pageAssistantManifestLoading.value = false
  }
}

async function runPageAssistantSelfCheck() {
  const session = pageAssistantSession.value
  if (!project.value?.id || !session?.sessionId) {
    ElMessage.warning('请先创建页面助手 AI 接入会话')
    return
  }
  pageAssistantCheckRunning.value = true
  try {
    const { data } = await runPageAssistantAccessSessionChecks(project.value.id, session.sessionId, {
      pageKey: selectedPage.value?.pageKey || selectedPageKey.value || undefined,
      routePattern: selectedPage.value?.routePattern || undefined,
      actionKeys: pageAssistantActionKeys.value,
    })
    pageAssistantSession.value = data.session
    await loadPageAssistantSessions({ silent: true })
    ElMessage.success(`页面助手自检完成：${data.checkResult.overallStatus}`)
  } catch (error) {
    ElMessage.error((error as Error).message || '页面助手自检失败')
  } finally {
    pageAssistantCheckRunning.value = false
  }
}

async function runPageAssistantCardCheck(session: PageAssistantSessionSummary) {
  if (!project.value?.id) {
    ElMessage.warning('项目详情尚未加载完成，请稍后再试')
    return
  }
  pageAssistantCheckRunning.value = true
  try {
    const { data } = await runPageAssistantAccessSessionChecks(project.value.id, session.sessionId, {
      pageKey: session.targetPageKey || undefined,
      routePattern: session.targetRoute || undefined,
      actionKeys: pageActionKeysForSession(session),
    })
    pageAssistantSession.value = data.session
    await loadPageAssistantSessions({ silent: true })
    ElMessage.success(`页面助手自检完成：${data.checkResult.overallStatus}`)
  } catch (error) {
    ElMessage.error((error as Error).message || '页面助手自检失败')
  } finally {
    pageAssistantCheckRunning.value = false
  }
}

function usePageAssistantAccess(session: PageAssistantSessionSummary) {
  const pageKey = session.targetPageKey || ''
  if (!pageKey) {
    ElMessage.warning('该接入任务还没有绑定 pageKey')
    return
  }
  const page = pageRegistry.value.find((item) => item.pageKey === pageKey)
  selectedPageKey.value = pageKey
  selectedPageIdentity.value = page ? pageIdentity(page) : ''
  selectedActions.value = pageActions.value.filter((action) => action.pageKey === pageKey && action.status === 'ACTIVE')
  if (!selectedActions.value.length) {
    selectedActions.value = pageActions.value.filter((action) => action.pageKey === pageKey)
  }
  agentName.value = agentName.value || `${page?.name || pageKey}页面助手`
  requirement.value = defaultRequirement()
  selectedPageAssistantAccess.value = null
  resetWizardProgressFromDraft()
  selectStep(selectedActions.value.length ? 'draft' : 'action')
  ElMessage.success('已带入页面和动作，可继续创建页面助手')
}

async function generateDraft() {
  if (!selectedActions.value.length) {
    ElMessage.warning('请至少选择一个页面动作')
    return
  }
  if (!modelInstanceId.value) {
    ElMessage.warning('请选择模型实例')
    return
  }
  if (draftSource.value === 'AI_CODING_RETURNED' && createdWorkflowId.value) {
    const confirmed = await confirmSwitchToPlatformGeneration()
    if (!confirmed) return
  }
  generating.value = true
  try {
    const { data } = await generateWorkflowDraft({
      agentId: 'new',
      agentName: agentName.value,
      projectCode: projectCode.value,
      modelInstanceId: modelInstanceId.value,
      draftScenario: 'PAGE_ASSISTANT',
      requirement: requirement.value || defaultRequirement(),
      pageActions: selectedActions.value.map(pageActionToResource),
      tools: selectedApiAssets.value.map(apiAssetToResource),
      currentCanvas: { version: 2, nodes: [], edges: [] },
    })
    draftPreview.value = data
    draftSource.value = 'PLATFORM_GENERATED'
    createdWorkflowId.value = ''
    bindingResult.value = null
    pageCopilotAgent.value = null
    selectStep('confirm')
    if (data.validationErrors?.length) {
      ElMessage.warning('草稿已返回，但仍有校验问题，请查看提示')
    } else {
      ElMessage.success('Workflow 草稿已生成')
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '生成 Workflow 草稿失败')
  } finally {
    generating.value = false
  }
}

async function loadPageCopilotAgent() {
  if (!project.value?.id && !projectCode.value) return
  try {
    const { data } = await listAgentEntries({
      projectId: project.value?.id ?? undefined,
      projectCode: projectCode.value,
      agentKind: 'PAGE_COPILOT',
    })
    pageCopilotAgent.value = data[0] || null
  } catch {
    pageCopilotAgent.value = null
  }
}

async function confirmCreateWorkflow() {
  if (!draftPreview.value) return
  if (draftIssueCount.value) {
    ElMessage.warning('草稿仍有校验问题或占位节点，请修复后再创建 Workflow')
    return
  }
  creatingWorkflow.value = true
  try {
    const graphSpecJson = JSON.stringify(draftPreview.value.graphSpec)
    const canvasJson = JSON.stringify(draftPreview.value.canvasSnapshot || { version: 2, nodes: [], edges: [] })
    const extraJson = JSON.stringify({
      pageAssistant: {
        source: 'PAGE_ASSISTANT_WIZARD',
        pageKey: selectedPageKey.value,
        pageName: selectedPage.value?.name || selectedPageKey.value,
        routePattern: selectedPage.value?.routePattern || '',
        actionKeys: selectedActions.value.map((item) => item.actionKey),
      },
    })
    const workflowDraft: WorkflowDefinitionDraft = {
      name: pageAssistantWorkflowName(),
      keySlug: pageAssistantWorkflowKeySlug(),
      description: requirement.value || defaultRequirement(),
      projectId: project.value?.id ?? null,
      projectCode: projectCode.value,
      workflowType: 'PAGE_ASSISTANT',
      runtimeType: 'LANGGRAPH4J',
      graphSpec: draftPreview.value.graphSpec,
      graphSpecJson,
      canvasJson,
      defaultModelInstanceId: modelInstanceId.value,
      status: 'DRAFT',
      managedBy: 'PAGE_ASSISTANT',
      extraJson,
    }
    const { data: workflow } = await createWorkflow(workflowDraft)
    await saveWorkflowStudio(workflow.id, { graphSpecJson, canvasJson, extraJson })
    draftSource.value = 'PLATFORM_GENERATED'
    createdWorkflowId.value = workflow.id
    bindingResult.value = null
    await loadPageCopilotAgent()
    ElMessage.success('页面助手 Workflow 草稿已创建')
    selectStep('bind')
  } catch (error) {
    ElMessage.error((error as Error).message || '创建页面助手 Workflow 失败')
  } finally {
    creatingWorkflow.value = false
  }
}

async function bindToPageCopilot() {
  if (!createdWorkflowId.value) return
  bindingAgent.value = true
  try {
    const { data } = await bindPageAssistantWorkflow(createdWorkflowId.value, {
      projectId: project.value?.id ?? null,
      projectCode: projectCode.value,
      agentId: pageCopilotAgent.value?.id ?? null,
      pageKey: selectedPageKey.value,
      routePattern: selectedPage.value?.routePattern || '',
      actionKeys: selectedActions.value.map((item) => item.actionKey).filter(Boolean),
    })
    bindingResult.value = data
    if (!pageCopilotAgent.value) {
      pageCopilotAgent.value = {
        id: data.agentId,
        keySlug: data.agentKeySlug,
        name: `${project.value?.name || projectCode.value} Page Copilot`,
        agentKind: 'PAGE_COPILOT',
      }
    }
    ElMessage.success('页面助手 Workflow 已挂载到页面副驾驶 Agent')
    selectStep('studio')
  } catch (error) {
    ElMessage.error((error as Error).message || '挂载页面副驾驶 Agent 失败')
  } finally {
    bindingAgent.value = false
  }
}

function enterWorkflowStudio() {
  const workflowId = bindingResult.value?.workflowId || createdWorkflowId.value
  if (!workflowId) {
    ElMessage.warning('缺少 Workflow ID，无法进入 Studio')
    return
  }
  router.push(`/workflows/${workflowId}/studio`)
}

watch(displayedStep, (step) => {
  if (step === 'bind' && createdWorkflowId.value) {
    void loadPageCopilotAgent()
  }
})

async function copySdkTemplate() {
  try {
    await navigator.clipboard.writeText(sdkTemplate.value)
    sdkTemplateCopied.value = true
    if (sdkCopyTimer) {
      clearTimeout(sdkCopyTimer)
    }
    sdkCopyTimer = setTimeout(() => {
      sdkTemplateCopied.value = false
      sdkCopyTimer = undefined
    }, 1600)
    ElMessage.success('模板已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择模板')
  }
}

async function copyPageAssistantPrompt() {
  try {
    if (!pageAssistantSession.value && project.value?.id) {
      await loadPageAssistantManifest({ silent: true })
    }
    await navigator.clipboard.writeText(pageAssistantOnboardingPrompt.value)
    aiPromptCopied.value = true
    if (aiPromptCopyTimer) {
      clearTimeout(aiPromptCopyTimer)
    }
    aiPromptCopyTimer = setTimeout(() => {
      aiPromptCopied.value = false
      aiPromptCopyTimer = undefined
    }, 1600)
    ElMessage.success('页面助手接入提示词已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择提示词')
  }
}

async function copyText(value: string, successMessage: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(successMessage)
  } catch {
    ElMessage.warning('复制失败，请手动选择内容')
  }
}

function goBack() {
  router.push({ name: 'RegistryProjectDetail', params: { projectCode: projectCode.value } })
}

onMounted(loadAll)
</script>

<style scoped lang="scss">
.page-assistant {
  box-sizing: border-box;
  min-height: calc(100vh - 72px);
  padding: 18px 32px 28px;
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at 48% 84%, rgba(91, 124, 255, 0.18), transparent 30%),
    radial-gradient(circle at 78% 18%, rgba(124, 92, 255, 0.13), transparent 32%),
    linear-gradient(135deg, #f9fbff 0%, #eef5ff 52%, #f7fbff 100%);
  color: #10203f;

  &::before {
    content: '';
    position: absolute;
    inset: auto -80px -120px 220px;
    height: 260px;
    pointer-events: none;
    background:
      repeating-linear-gradient(170deg, rgba(91, 124, 255, 0.16) 0 1px, transparent 1px 20px),
      radial-gradient(circle at 50% 50%, rgba(255, 255, 255, 0.8), transparent 68%);
    opacity: 0.5;
    transform: rotate(-2deg);
  }
}

.bind-intro {
  margin: 0 0 18px;
  color: #53627d;
  line-height: 1.6;
}

.page-header,
.panel-head,
.manual-grid,
.draft-actions {
  display: flex;
  gap: 16px;
}

.page-header {
  position: relative;
  z-index: 1;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;

  h1 {
    margin: 10px 0 0;
    font-size: 28px;
    line-height: 1.2;
    letter-spacing: 0;
    color: #10203f;
  }

  p {
    margin: 0;
    color: #53627d;
    font-size: 15px;
  }
}

.draft-actions {
  align-items: center;
}

.step-progress,
.focus-panel,
.template-box {
  border: 1px solid rgba(167, 190, 230, 0.52);
  background: rgba(255, 255, 255, 0.64);
  backdrop-filter: blur(18px);
  box-shadow:
    0 22px 52px rgba(44, 75, 130, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
}

.step-progress {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 170px;
  min-height: 500px;
  padding: 24px 20px;
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(245, 249, 255, 0.54)),
    radial-gradient(circle at 50% 0%, rgba(91, 124, 255, 0.1), transparent 46%);
}

.progress-step {
  position: relative;
  display: flex;
  width: 100%;
  gap: 10px;
  align-items: flex-start;
  min-width: 0;
  padding: 8px 12px 8px 6px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: #5d6c86;
  text-align: left;
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease, background 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;

  &::after {
    content: '';
    position: absolute;
    left: 23px;
    top: 42px;
    width: 1px;
    height: 28px;
    border-left: 1px dashed rgba(91, 124, 255, 0.28);
  }

  &:last-child::after {
    display: none;
  }

  &:hover {
    color: #10203f;
    background: rgba(255, 255, 255, 0.42);
  }

  &.active {
    color: #10203f;
    border-color: rgba(123, 97, 255, 0.16);
    background:
      linear-gradient(135deg, rgba(255, 255, 255, 0.74), rgba(243, 247, 255, 0.58)),
      radial-gradient(circle at 8% 50%, rgba(91, 124, 255, 0.16), transparent 48%);
    box-shadow:
      0 10px 26px rgba(64, 93, 180, 0.1),
      inset 0 1px 0 rgba(255, 255, 255, 0.92);
    transform: translateX(2px);

    &::before {
      opacity: 1;
      transform: scaleY(1);
    }
  }

  &::before {
    content: '';
    position: absolute;
    left: -1px;
    top: 10px;
    width: 3px;
    height: calc(100% - 20px);
    border-radius: 999px;
    background: linear-gradient(180deg, #6d4cff, #2f80ff);
    box-shadow: 0 0 16px rgba(91, 92, 255, 0.35);
    opacity: 0;
    transform: scaleY(0.45);
    transition: opacity 0.2s ease, transform 0.2s ease;
  }

  &:focus-visible {
    outline: 2px solid var(--brand-hover);
    outline-offset: 2px;
  }

  &.done .step-index {
    border-color: rgba(34, 197, 94, 0.48);
    background: linear-gradient(135deg, #22c55e, #16a34a);
    color: #fff;
  }

  strong,
  small {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: #10203f;
    font-size: 15px;

    .active & {
      color: #10203f;
      font-weight: 800;
    }
  }

  small {
    margin-top: 6px;
    color: #6a7891;
    font-size: 12px;

    .active & {
      color: #53627d;
    }
  }
}

.step-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 1px solid rgba(91, 124, 255, 0.34);
  background: rgba(255, 255, 255, 0.72);
  color: #4e5f79;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;

  .active & {
    border-color: transparent;
    background: linear-gradient(135deg, #6d4cff, #2f80ff);
    color: #fff;
    box-shadow:
      0 8px 18px rgba(91, 92, 255, 0.24),
      inset 0 1px 0 rgba(255, 255, 255, 0.32);
  }
}

.wizard-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 170px minmax(760px, 1fr);
  gap: 34px;
  align-items: flex-start;
}

.stage-shell {
  position: relative;
  min-width: 0;
  padding: 58px 22px 50px;
}

.stack-card {
  position: absolute;
  left: 50%;
  width: calc(100% - 130px);
  height: 540px;
  border: 1px solid rgba(167, 190, 230, 0.46);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.42);
  backdrop-filter: blur(18px);
  box-shadow: 0 20px 42px rgba(51, 87, 150, 0.1);
  transform: translateX(-50%);
}

.stack-card-back {
  top: 18px;
  opacity: 0.62;
}

.stack-card-middle {
  top: 38px;
  width: calc(100% - 84px);
  opacity: 0.82;
}

.focus-panel {
  position: relative;
  z-index: 2;
  min-width: 0;
  height: 520px;
  min-height: 520px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 30px;
  border-radius: 16px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.82), rgba(245, 249, 255, 0.68)),
    radial-gradient(circle at 18% 0%, rgba(91, 124, 255, 0.12), transparent 36%);
  box-shadow:
    0 28px 64px rgba(45, 75, 130, 0.16),
    0 0 0 1px rgba(255, 255, 255, 0.55) inset;
}

.step-screen {
  display: flex;
  flex-direction: column;
  min-height: 460px;
}

.step-footer-note {
  margin-top: auto;
  padding-top: 30px;

  :deep(.el-alert) {
    align-items: center;
    border: 1px solid rgba(167, 190, 230, 0.36);
    border-radius: 10px;
    background:
      linear-gradient(135deg, rgba(255, 255, 255, 0.72), rgba(246, 250, 255, 0.5)),
      radial-gradient(circle at 0% 50%, rgba(91, 124, 255, 0.1), transparent 34%);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86);
  }

  :deep(.el-alert__icon) {
    font-size: 22px;
  }

  :deep(.el-alert__title) {
    color: #10203f;
    font-size: 15px;
    font-weight: 800;
  }

  :deep(.el-alert__description) {
    color: #53627d;
    font-size: 14px;
  }
}

.panel-head {
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 22px;

  &.compact {
    margin-bottom: 10px;
  }

  h2 {
    margin: 0 0 10px;
    color: #10203f;
    font-size: 26px;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: #53627d;
    font-size: 15px;
  }

  :deep(.el-button) {
    border-radius: 8px;
  }
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;

  :deep(.el-button) {
    height: 36px;
    border-radius: 8px;
    font-weight: 800;
  }
}

.access-template-button.el-button {
  height: 36px;
  border: 0;
  border-radius: 999px;
  background:
    linear-gradient(135deg, rgba(53, 109, 255, 0.96), rgba(109, 76, 255, 0.96)),
    radial-gradient(circle at 18% 0%, rgba(255, 255, 255, 0.34), transparent 42%);
  padding: 0 18px;
  color: #fff;
  font-weight: 800;
  box-shadow:
    0 14px 28px rgba(91, 92, 255, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.42);

  &:hover,
  &:focus {
    color: #fff;
    transform: translateY(-1px);
    box-shadow:
      0 18px 34px rgba(91, 92, 255, 0.3),
      inset 0 1px 0 rgba(255, 255, 255, 0.48);
  }
}

.step-kicker {
  display: inline-flex;
  align-items: center;
  height: 24px;
  margin-bottom: 8px;
  border-radius: 999px;
  background: rgba(79, 103, 255, 0.1);
  padding: 0 10px;
  color: var(--brand-active);
  font-size: 13px;
  font-weight: 800;
}

.step-kicker-row {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;

  .step-kicker {
    margin-bottom: 0;
  }

  :deep(.el-button) {
    height: 24px;
    padding: 0 8px;
    color: var(--brand-active);
    font-weight: 700;
  }
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 20px;
}

.health-card {
  min-height: 112px;
  border: 1px solid rgba(167, 190, 230, 0.48);
  border-radius: 10px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.76), rgba(246, 250, 255, 0.54)),
    radial-gradient(circle at 16% 18%, rgba(91, 124, 255, 0.14), transparent 34%);
  padding: 18px;
  box-shadow:
    0 16px 34px rgba(47, 79, 136, 0.11),
    inset 0 1px 0 rgba(255, 255, 255, 0.95);

  &.stat-action {
    background:
      linear-gradient(145deg, rgba(255, 255, 255, 0.76), rgba(246, 250, 255, 0.54)),
      radial-gradient(circle at 16% 18%, rgba(34, 197, 94, 0.15), transparent 34%);
  }

  &.stat-active {
    background:
      linear-gradient(145deg, rgba(255, 255, 255, 0.76), rgba(246, 250, 255, 0.54)),
      radial-gradient(circle at 16% 18%, rgba(124, 92, 255, 0.17), transparent 34%);
  }

  span {
    display: block;
  }

  .stat-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    margin-bottom: 12px;
    border-radius: 10px;
    background: linear-gradient(135deg, #2f80ff, #6d4cff);
    color: #fff;
    font-size: 13px;
    font-weight: 800;
    box-shadow: 0 10px 20px rgba(91, 92, 255, 0.25);
  }

  &.stat-action .stat-icon {
    background: linear-gradient(135deg, #22c55e, #16a34a);
    box-shadow: 0 10px 20px rgba(34, 197, 94, 0.22);
  }

  &.stat-active .stat-icon {
    background: linear-gradient(135deg, #7c5cff, #5b3ff2);
  }

  .stat-label {
    color: #10203f;
    font-weight: 700;
  }

  strong {
    display: block;
    margin-top: 14px;
    color: #10203f;
    font-size: 40px;
    line-height: 1;
    text-align: center;
    text-shadow: 0 8px 18px rgba(16, 32, 63, 0.16);
  }
}

.page-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
  margin: 14px 0;
}

.page-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  border: 1px solid rgba(180, 195, 220, 0.46);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.72);
  padding: 16px;
  text-align: left;
  cursor: pointer;
  box-shadow: 0 10px 22px rgba(47, 79, 136, 0.08);

  small {
    display: block;
    margin-top: 4px;
    color: #667085;
  }

  &.selected {
    border-color: rgba(91, 92, 255, 0.82);
    background:
      linear-gradient(135deg, rgba(255, 255, 255, 0.86), rgba(240, 244, 255, 0.72)),
      radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.18), transparent 42%);
    box-shadow:
      0 16px 34px rgba(91, 92, 255, 0.14),
      0 0 0 2px rgba(91, 92, 255, 0.1);
  }
}

.draft-result {
  margin-top: 16px;
}

.studio-ready {
  display: grid;
  gap: 18px;
  margin-top: 20px;
}

.studio-ready-hero {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  min-height: 150px;
  border: 1px solid rgba(91, 124, 255, 0.18);
  border-radius: 20px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.82), rgba(240, 247, 255, 0.62)),
    radial-gradient(circle at 12% 0%, rgba(51, 150, 105, 0.18), transparent 38%),
    radial-gradient(circle at 100% 0%, rgba(91, 92, 255, 0.14), transparent 40%);
  padding: 28px 32px;
  overflow: hidden;
  box-shadow:
    0 24px 54px rgba(45, 75, 130, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);

  &::after {
    content: '';
    position: absolute;
    right: -68px;
    bottom: -84px;
    width: 220px;
    height: 220px;
    border-radius: 999px;
    background:
      radial-gradient(circle, rgba(91, 124, 255, 0.16), transparent 64%);
    pointer-events: none;
  }

  &.warning {
    border-color: rgba(245, 158, 11, 0.28);
    background:
      linear-gradient(135deg, rgba(255, 255, 255, 0.84), rgba(255, 251, 235, 0.62)),
      radial-gradient(circle at 12% 0%, rgba(245, 158, 11, 0.18), transparent 38%),
      radial-gradient(circle at 100% 0%, rgba(91, 92, 255, 0.12), transparent 40%);
  }
}

.studio-ready-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 58px;
  height: 58px;
  border-radius: 18px;
  background: linear-gradient(135deg, #2fb56d, #35a7d6);
  color: #fff;
  box-shadow:
    0 18px 34px rgba(47, 181, 109, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.34);

  .el-icon {
    font-size: 28px;
  }

  .warning &,
  &.warning {
    background: linear-gradient(135deg, #f59e0b, #7c5cff);
    box-shadow:
      0 18px 34px rgba(245, 158, 11, 0.22),
      inset 0 1px 0 rgba(255, 255, 255, 0.34);
  }
}

.studio-ready-copy {
  min-width: 0;

  span,
  strong,
  p {
    display: block;
  }

  span {
    color: #5b5cff;
    font-size: 13px;
    font-weight: 900;
  }

  strong {
    margin-top: 7px;
    color: #10203f;
    font-size: 28px;
    line-height: 1.2;
  }

  p {
    max-width: 720px;
    margin: 10px 0 0;
    color: #53627d;
    font-size: 15px;
    line-height: 1.7;
  }
}

.studio-ready-state {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  border: 1px solid rgba(91, 124, 255, 0.16);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  padding: 0 14px;
  color: var(--brand-active);
  font-size: 13px;
  font-weight: 900;

  em {
    font-style: normal;
  }

  .warning & {
    border-color: rgba(245, 158, 11, 0.28);
    color: #b45309;
  }
}

.studio-ready-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;

  div {
    min-width: 0;
    border: 1px solid rgba(167, 190, 230, 0.38);
    border-radius: 16px;
    background:
      linear-gradient(145deg, rgba(255, 255, 255, 0.72), rgba(246, 250, 255, 0.52)),
      radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.09), transparent 42%);
    padding: 16px 18px;
    box-shadow:
      0 14px 30px rgba(47, 79, 136, 0.07),
      inset 0 1px 0 rgba(255, 255, 255, 0.86);
  }

  span,
  strong {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: #7a88a3;
    font-size: 12px;
    font-weight: 800;
  }

  strong {
    margin-top: 8px;
    color: #10203f;
    font-size: 16px;
  }
}

.studio-ready-issues {
  display: grid;
  gap: 8px;
  border: 1px solid rgba(248, 113, 113, 0.2);
  border-radius: 14px;
  background: rgba(255, 247, 247, 0.72);
  padding: 14px;

  span {
    color: #b42318;
    font-size: 13px;
    line-height: 1.6;
  }
}

.studio-ready-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding-top: 4px;

  button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-width: 144px;
    height: 44px;
    border-radius: 12px;
    padding: 0 20px;
    cursor: pointer;
    font-size: 15px;
    font-weight: 900;
    transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease, background 0.18s ease;

    &:hover:not(:disabled) {
      transform: translateY(-1px);
    }

    &:disabled {
      cursor: not-allowed;
      opacity: 0.56;
    }
  }

  .secondary {
    border: 1px solid rgba(167, 190, 230, 0.52);
    background: rgba(255, 255, 255, 0.68);
    color: #53627d;
  }

  .primary {
    border: 0;
    background: linear-gradient(135deg, #356dff, #6d4cff);
    color: #fff;
    box-shadow: 0 16px 34px rgba(91, 92, 255, 0.28);
  }
}

.studio-ready-empty {
  display: grid;
  justify-items: center;
  gap: 12px;
  margin: 28px auto 0;
  max-width: 520px;
  border: 1px solid rgba(167, 190, 230, 0.36);
  border-radius: 20px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.72), rgba(246, 250, 255, 0.5)),
    radial-gradient(circle at 50% 0%, rgba(91, 124, 255, 0.12), transparent 42%);
  padding: 36px;
  text-align: center;
  box-shadow:
    0 20px 44px rgba(45, 75, 130, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);

  strong {
    color: #10203f;
    font-size: 22px;
  }

  span {
    color: #667085;
    font-size: 14px;
  }

  button {
    height: 40px;
    border: 0;
    border-radius: 10px;
    background: linear-gradient(135deg, #356dff, #6d4cff);
    padding: 0 18px;
    color: #fff;
    cursor: pointer;
    font-weight: 900;
    box-shadow: 0 12px 24px rgba(91, 92, 255, 0.24);
  }
}

.action-select-area {
  display: grid;
  gap: 14px;
  margin-top: 18px;
}

.action-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  border: 1px solid rgba(167, 190, 230, 0.36);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.46);
  padding: 12px 14px;
  color: #61728d;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);

  strong {
    color: var(--brand-active);
    font-size: 18px;
  }
}

.action-toolbar-actions {
  display: flex;
  gap: 8px;

  button {
    height: 28px;
    border: 1px solid rgba(91, 124, 255, 0.22);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.62);
    padding: 0 12px;
    color: #4f5f7d;
    cursor: pointer;
    transition: border-color 0.18s ease, color 0.18s ease, background 0.18s ease;

    &:hover {
      border-color: rgba(91, 124, 255, 0.46);
      background: rgba(244, 247, 255, 0.9);
      color: var(--brand-active);
    }
  }
}

.action-card-list {
  display: grid;
  gap: 10px;
  max-height: 360px;
  overflow: auto;
  padding-right: 4px;
}

.action-card {
  display: grid;
  grid-template-columns: 28px minmax(180px, 1fr) minmax(190px, 0.85fr) auto;
  gap: 14px;
  align-items: center;
  width: 100%;
  min-width: 0;
  border: 1px solid rgba(167, 190, 230, 0.42);
  border-radius: 12px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.72), rgba(245, 249, 255, 0.5)),
    radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.12), transparent 42%);
  padding: 14px 16px;
  color: #10203f;
  text-align: left;
  cursor: pointer;
  box-shadow:
    0 12px 28px rgba(47, 79, 136, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  transition: border-color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;

  &:hover {
    border-color: rgba(91, 124, 255, 0.48);
    transform: translateY(-1px);
    box-shadow:
      0 16px 34px rgba(47, 79, 136, 0.12),
      inset 0 1px 0 rgba(255, 255, 255, 0.96);
  }

  &.selected {
    border-color: rgba(91, 92, 255, 0.72);
    background:
      linear-gradient(135deg, rgba(255, 255, 255, 0.84), rgba(239, 244, 255, 0.68)),
      radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.24), transparent 44%);
    box-shadow:
      0 18px 38px rgba(91, 92, 255, 0.16),
      inset 4px 0 0 rgba(91, 92, 255, 0.76),
      inset 0 1px 0 rgba(255, 255, 255, 0.98);
  }
}

.action-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 1px solid rgba(91, 124, 255, 0.28);
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.7);
  color: #fff;
  font-size: 13px;
  font-weight: 800;

  .selected & {
    border-color: transparent;
    background: linear-gradient(135deg, #356dff, #6d4cff);
    box-shadow: 0 8px 16px rgba(91, 92, 255, 0.24);
  }
}

.action-main,
.action-meta,
.action-flags {
  min-width: 0;
}

.action-main,
.action-meta {
  strong,
  small {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.action-main {
  strong {
    color: #10203f;
    font-size: 15px;
  }

  small {
    margin-top: 5px;
    color: #667085;
    font-size: 12px;
  }
}

.action-meta {
  small {
    color: #8a96ad;
    font-size: 11px;
  }

  strong {
    margin-top: 4px;
    color: #53627d;
    font-family: Consolas, 'Courier New', monospace;
    font-size: 12px;
  }
}

.action-flags {
  display: grid;
  justify-items: end;
  gap: 6px;
  color: #8a96ad;
  font-size: 12px;
}

.draft-summary-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: -6px 0 18px;

  span {
    display: inline-flex;
    align-items: center;
    min-height: 30px;
    border: 1px solid rgba(91, 124, 255, 0.16);
    border-radius: 999px;
    background:
      linear-gradient(135deg, rgba(255, 255, 255, 0.78), rgba(242, 247, 255, 0.58)),
      radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.14), transparent 38%);
    padding: 0 12px;
    color: #4f5f7d;
    font-size: 12px;
    font-weight: 700;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
  }
}

.draft-form {
  max-width: none;
}

.draft-console {
  display: grid;
  grid-template-columns: minmax(320px, 0.92fr) minmax(360px, 1.08fr);
  gap: 16px;
}

.draft-config-card {
  min-width: 0;
  border: 1px solid rgba(167, 190, 230, 0.42);
  border-radius: 14px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.72), rgba(246, 250, 255, 0.52)),
    radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.11), transparent 40%);
  padding: 16px;
  box-shadow:
    0 16px 34px rgba(47, 79, 136, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);

  :deep(.el-form-item) {
    margin-bottom: 14px;
  }

  :deep(.el-form-item:last-child) {
    margin-bottom: 0;
  }

  :deep(.el-form-item__label) {
    color: #53627d;
    font-weight: 800;
  }

  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper),
  :deep(.el-textarea__inner) {
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.72);
    box-shadow:
      0 0 0 1px rgba(167, 190, 230, 0.36) inset,
      inset 0 1px 0 rgba(255, 255, 255, 0.88);
  }
}

.draft-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;

  h3 {
    margin: 0;
    color: #10203f;
    font-size: 16px;
    line-height: 1.3;
  }

  small {
    display: block;
    margin-top: 4px;
    color: #7a88a3;
    font-size: 12px;
  }
}

.goal-card-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.goal-card {
  min-width: 0;
  border: 1px solid rgba(167, 190, 230, 0.42);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.58);
  padding: 12px 10px;
  color: #10203f;
  text-align: left;
  cursor: pointer;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease, background 0.18s ease;

  strong,
  small {
    display: block;
  }

  strong {
    margin-top: 10px;
    font-size: 13px;
    line-height: 1.3;
  }

  small {
    margin-top: 5px;
    color: #667085;
    font-size: 11px;
    line-height: 1.5;
  }

  &:hover {
    border-color: rgba(91, 124, 255, 0.42);
    transform: translateY(-1px);
    box-shadow:
      0 12px 24px rgba(47, 79, 136, 0.1),
      inset 0 1px 0 rgba(255, 255, 255, 0.92);
  }

  &.selected {
    border-color: rgba(91, 92, 255, 0.72);
    background:
      linear-gradient(135deg, rgba(255, 255, 255, 0.84), rgba(239, 244, 255, 0.68)),
      radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.22), transparent 42%);
    box-shadow:
      0 16px 32px rgba(91, 92, 255, 0.14),
      inset 0 0 0 2px rgba(91, 92, 255, 0.1),
      inset 4px 0 0 rgba(91, 92, 255, 0.76);
  }
}

.goal-card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 1px solid rgba(135, 161, 224, 0.26);
  border-radius: 12px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.9), rgba(236, 243, 255, 0.72)),
    radial-gradient(circle at 28% 18%, rgba(255, 255, 255, 0.9), transparent 42%);
  color: #4f63ff;
  box-shadow:
    0 10px 22px rgba(70, 92, 170, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  transition: color 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease, background 0.18s ease;

  .el-icon {
    font-size: 18px;
  }

  &.tone-operate {
    color: #35a857;
    background:
      linear-gradient(145deg, rgba(255, 255, 255, 0.9), rgba(236, 252, 244, 0.72)),
      radial-gradient(circle at 28% 18%, rgba(255, 255, 255, 0.9), transparent 42%);
  }

  &.tone-link {
    color: #7257ff;
    background:
      linear-gradient(145deg, rgba(255, 255, 255, 0.9), rgba(243, 239, 255, 0.72)),
      radial-gradient(circle at 28% 18%, rgba(255, 255, 255, 0.9), transparent 42%);
  }
}

.goal-card.selected .goal-card-icon {
  border-color: rgba(119, 103, 255, 0.36);
  background:
    linear-gradient(135deg, #3f73ff, #7354ff),
    radial-gradient(circle at 22% 16%, rgba(255, 255, 255, 0.36), transparent 38%);
  color: #fff;
  box-shadow:
    0 14px 28px rgba(91, 92, 255, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.32);
}

.prompt-card {
  display: flex;
  flex-direction: column;

  :deep(.prompt-editor) {
    flex: 1;
  }

  :deep(.prompt-editor),
  :deep(.prompt-editor .el-textarea__inner) {
    height: 100%;
  }

  :deep(.prompt-editor .el-textarea__inner) {
    min-height: 210px !important;
    padding: 16px;
    color: #10203f;
    font-size: 14px;
    line-height: 1.7;
  }
}

.prompt-head {
  button {
    height: 28px;
    border: 1px solid rgba(91, 124, 255, 0.22);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.62);
    padding: 0 12px;
    color: var(--brand-active);
    font-size: 12px;
    font-weight: 800;
    cursor: pointer;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.8);
  }
}

.api-resource-card {
  grid-column: 1 / -1;
}

.api-empty-card {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 82px;
  border: 1px dashed rgba(91, 124, 255, 0.28);
  border-radius: 12px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.54), rgba(246, 250, 255, 0.38)),
    radial-gradient(circle at 0% 50%, rgba(91, 124, 255, 0.1), transparent 34%);
  padding: 16px;

  strong,
  small {
    display: block;
  }

  strong {
    color: #10203f;
    font-size: 14px;
  }

  small {
    margin-top: 5px;
    color: #667085;
    font-size: 12px;
  }
}

.api-empty-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(53, 109, 255, 0.92), rgba(109, 76, 255, 0.92));
  color: #fff;
  font-size: 12px;
  font-weight: 900;
  box-shadow: 0 12px 24px rgba(91, 92, 255, 0.2);
  flex-shrink: 0;
}

.draft-generate-row {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 18px;
  padding: 8px 0 2px;

  :deep(.el-button--primary) {
    min-width: 210px;
    height: 46px;
    border: 0;
    border-radius: 10px;
    background: linear-gradient(135deg, #356dff, #6d4cff);
    box-shadow:
      0 16px 32px rgba(91, 92, 255, 0.28),
      inset 0 1px 0 rgba(255, 255, 255, 0.36);
    flex-shrink: 0;
    font-weight: 900;
  }
}

.stage-cue {
  position: absolute;
  left: 50%;
  z-index: 4;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 24px;
  border: 1px solid rgba(118, 143, 210, 0.22);
  border-radius: 999px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.48), rgba(236, 243, 255, 0.28));
  backdrop-filter: blur(14px);
  color: rgba(73, 103, 255, 0.66);
  cursor: pointer;
  box-shadow:
    0 8px 22px rgba(47, 79, 136, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  animation: cue-breathe 2.1s ease-in-out infinite;
  transition: border-color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;

  &:hover:not(:disabled) {
    border-color: rgba(91, 124, 255, 0.42);
    background:
      linear-gradient(145deg, rgba(255, 255, 255, 0.72), rgba(236, 243, 255, 0.46));
    box-shadow:
      0 10px 24px rgba(47, 79, 136, 0.12),
      inset 0 1px 0 rgba(255, 255, 255, 0.9);
  }

  &:disabled {
    cursor: default;
    opacity: 0.28;
    animation: none;
  }
}

.stage-cue-up {
  top: 22px;
  transform: translateX(-50%);
  animation-delay: 0.22s;

  &:hover:not(:disabled) {
    transform: translateX(-50%) translateY(-2px);
  }

  .cue-chevron {
    transform: rotate(-45deg) translateY(1px);
  }
}

.stage-cue-down {
  bottom: 54px;
  transform: translateX(-50%);

  &:hover:not(:disabled) {
    transform: translateX(-50%) translateY(2px);
  }

  .cue-chevron {
    transform: rotate(135deg) translateY(1px);
  }
}

.cue-chevron {
  width: 8px;
  height: 8px;
  border-top: 2px solid currentColor;
  border-right: 2px solid currentColor;
  border-radius: 1px;
}

.focus-panel.step-slide-down {
  animation: card-roll-down 0.38s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.focus-panel.step-slide-up {
  animation: card-roll-up 0.38s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.focus-panel.step-shake {
  animation: card-attention 0.48s cubic-bezier(0.36, 0.07, 0.19, 0.97) both;
}

@keyframes card-roll-down {
  from {
    opacity: 0;
    filter: blur(10px);
    transform: translateY(56px) scale(0.975);
    box-shadow:
      0 10px 28px rgba(45, 75, 130, 0.08),
      0 0 0 1px rgba(255, 255, 255, 0.42) inset;
  }
  to {
    opacity: 1;
    filter: blur(0);
    transform: translateY(0) scale(1);
    box-shadow:
      0 28px 64px rgba(45, 75, 130, 0.16),
      0 0 0 1px rgba(255, 255, 255, 0.55) inset;
  }
}

@keyframes card-roll-up {
  from {
    opacity: 0;
    filter: blur(10px);
    transform: translateY(-56px) scale(0.975);
    box-shadow:
      0 10px 28px rgba(45, 75, 130, 0.08),
      0 0 0 1px rgba(255, 255, 255, 0.42) inset;
  }
  to {
    opacity: 1;
    filter: blur(0);
    transform: translateY(0) scale(1);
    box-shadow:
      0 28px 64px rgba(45, 75, 130, 0.16),
      0 0 0 1px rgba(255, 255, 255, 0.55) inset;
  }
}

@keyframes card-attention {
  0%,
  100% {
    transform: translateX(0);
    box-shadow:
      0 28px 64px rgba(45, 75, 130, 0.16),
      0 0 0 1px rgba(255, 255, 255, 0.55) inset;
  }
  18% {
    transform: translateX(-10px);
  }
  36% {
    transform: translateX(8px);
  }
  54% {
    transform: translateX(-5px);
    box-shadow:
      0 30px 68px rgba(80, 91, 255, 0.2),
      0 0 0 2px rgba(104, 92, 255, 0.28) inset;
  }
  72% {
    transform: translateX(3px);
  }
}

@keyframes cue-breathe {
  0%,
  100% {
    box-shadow:
      0 8px 22px rgba(47, 79, 136, 0.07),
      0 0 0 0 rgba(91, 124, 255, 0),
      inset 0 1px 0 rgba(255, 255, 255, 0.82);
  }
  50% {
    box-shadow:
      0 10px 24px rgba(47, 79, 136, 0.1),
      0 0 0 6px rgba(91, 124, 255, 0.035),
      inset 0 1px 0 rgba(255, 255, 255, 0.9);
  }
}

.template-box {
  padding: 0;
}

.inline-template {
  margin-top: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.template-modal {
  position: relative;
  overflow: hidden;
  border-radius: 18px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.9), rgba(246, 250, 255, 0.74)),
    radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.16), transparent 36%),
    radial-gradient(circle at 92% 8%, rgba(124, 92, 255, 0.12), transparent 32%);
  padding: 22px;
  box-shadow:
    0 30px 70px rgba(44, 75, 130, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    inset 0 0 0 1px rgba(167, 190, 230, 0.42);
}

.template-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 22px;
  margin-bottom: 18px;
}

.template-title-block {
  display: flex;
  min-width: 0;
  gap: 16px;

  h3 {
    margin: 0 0 6px;
    color: #10203f;
    font-size: 20px;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: #53627d;
    font-size: 13px;
    line-height: 1.6;
  }
}

.template-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background:
    linear-gradient(135deg, #356dff, #6d4cff),
    radial-gradient(circle at 20% 0%, rgba(255, 255, 255, 0.5), transparent 42%);
  color: #fff;
  font-size: 24px;
  box-shadow:
    0 14px 28px rgba(91, 92, 255, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.38);
  flex-shrink: 0;
}

.template-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;

  span {
    display: inline-flex;
    align-items: center;
    height: 24px;
    border: 1px solid rgba(91, 124, 255, 0.2);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.56);
    padding: 0 10px;
    color: var(--brand-active);
    font-size: 12px;
    font-weight: 700;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.76);
  }
}

.template-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.copy-template-button.el-button {
  height: 42px;
  border: 0;
  border-radius: 12px;
  background:
    linear-gradient(135deg, rgba(53, 109, 255, 0.98), rgba(109, 76, 255, 0.98)),
    radial-gradient(circle at 18% 0%, rgba(255, 255, 255, 0.34), transparent 42%);
  padding: 0 18px;
  color: #fff;
  font-weight: 800;
  box-shadow:
    0 14px 30px rgba(91, 92, 255, 0.28),
    0 0 0 3px rgba(91, 124, 255, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.42);

  &:hover,
  &:focus {
    color: #fff;
    transform: translateY(-1px);
    box-shadow:
      0 18px 34px rgba(91, 92, 255, 0.32),
      0 0 0 4px rgba(91, 124, 255, 0.14),
      inset 0 1px 0 rgba(255, 255, 255, 0.5);
  }
}

.template-close-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: 1px solid rgba(167, 190, 230, 0.38);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.58);
  color: #10203f;
  cursor: pointer;
  box-shadow:
    0 10px 22px rgba(47, 79, 136, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  transition: border-color 0.18s ease, color 0.18s ease, transform 0.18s ease, background 0.18s ease;

  &:hover {
    border-color: rgba(91, 124, 255, 0.34);
    background: rgba(255, 255, 255, 0.82);
    color: var(--brand-active);
    transform: translateY(-1px);
  }
}

.template-code-shell {
  overflow: hidden;
  border: 1px solid rgba(91, 124, 255, 0.22);
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgba(244, 247, 255, 0.72), rgba(236, 242, 255, 0.56)),
    radial-gradient(circle at 0% 0%, rgba(91, 124, 255, 0.12), transparent 42%);
  padding: 8px;
  box-shadow:
    0 18px 42px rgba(47, 79, 136, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
}

.template-code-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  height: 32px;
  padding: 0 10px;
  color: #53627d;
  font-size: 12px;
  font-weight: 700;

  span + span {
    position: relative;
    padding-left: 14px;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 50%;
      width: 4px;
      height: 4px;
      border-radius: 50%;
      background: rgba(91, 124, 255, 0.42);
      transform: translateY(-50%);
    }
  }
}

.template-code-shell pre {
  margin: 0;
  max-height: 330px;
  overflow: auto;
  border-radius: 10px;
  background:
    linear-gradient(135deg, rgba(15, 23, 42, 0.98), rgba(24, 34, 62, 0.98)),
    radial-gradient(circle at 0% 0%, rgba(47, 128, 255, 0.18), transparent 42%);
  padding: 18px 20px;
  color: #d9e6ff;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.72;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.08),
    inset 0 0 0 1px rgba(255, 255, 255, 0.04);
}

.template-code-shell pre :deep(.code-function) {
  color: #65b8ff;
}

.template-code-shell pre :deep(.code-property) {
  color: #d9e6ff;
}

.template-code-shell pre :deep(.code-string) {
  color: #9ef07a;
}

.template-code-shell pre :deep(.code-keyword) {
  color: #8aa7ff;
}

.template-code-shell pre :deep(.code-comment) {
  color: #6f819f;
}

.template-code-shell pre :deep(.code-punctuation) {
  color: #ffbd6e;
}

:global(.sdk-template-popover) {
  border: 0 !important;
  border-radius: 18px !important;
  background: transparent !important;
  padding: 0 !important;
  box-shadow: none !important;
  animation: template-pop-in 0.2s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

@keyframes template-pop-in {
  from {
    opacity: 0;
    transform: translateY(4px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.api-assets {
  width: 100%;
  border: 1px solid rgba(167, 190, 230, 0.36);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.44);
  padding: 10px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.76);
}

.manual-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.ai-prompt-dialog {
  display: grid;
  gap: 14px;
}

.draft-entry-section {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid rgba(167, 190, 230, 0.42);
  border-radius: 12px;
  background: rgba(252, 253, 255, 0.88);
}

.draft-entry-section.platform-entry {
  margin-top: 14px;
}

.draft-entry-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.draft-entry-head h3 {
  margin: 0;
  font-size: 15px;
}

.draft-entry-head small {
  display: block;
  margin-top: 4px;
  color: #718096;
  font-size: 12px;
}

.draft-source-banner {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
}

.draft-source-banner.ai-coding {
  border: 1px solid rgba(103, 194, 58, 0.35);
  background: rgba(240, 249, 235, 0.82);
}

.draft-source-banner strong {
  font-size: 14px;
}

.workflow-ai-coding-result-card {
  display: grid;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid rgba(167, 190, 230, 0.42);
  border-radius: 10px;
  background: rgba(248, 251, 255, 0.82);
}

.workflow-ai-coding-result-card.dialog {
  margin-top: 2px;
}

.workflow-ai-coding-result-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.workflow-ai-coding-result-head strong {
  flex: 1 1 auto;
  font-size: 14px;
}

.workflow-ai-coding-result-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.workflow-ai-coding-result-empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(247, 250, 252, 0.9);
  color: #718096;
  font-size: 13px;
}

.studio-ready-metrics.compact {
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
}

.ai-prompt-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ai-helper-command-list {
  display: grid;
  gap: 10px;
}

.ai-helper-command {
  display: grid;
  grid-template-columns: minmax(150px, 0.5fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid rgba(167, 190, 230, 0.42);
  border-radius: 8px;
  background: rgba(248, 251, 255, 0.72);
}

.ai-helper-command span {
  display: grid;
  gap: 2px;
}

.ai-helper-command small {
  color: #718096;
}

.ai-helper-command code {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #334155;
  font-size: 12px;
}

.ai-access-session-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid rgba(167, 190, 230, 0.42);
  border-radius: 8px;
  background: rgba(248, 251, 255, 0.74);
}

.ai-access-session-head,
.ai-access-session-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.ai-access-session-head span,
.ai-access-meta-grid small,
.ai-access-step small {
  color: #63718f;
  font-size: 12px;
}

.ai-access-session-head strong {
  display: block;
  margin-top: 2px;
  color: #14233b;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
}

.ai-access-meta-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.ai-access-meta-grid span {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(167, 190, 230, 0.34);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.62);
}

.ai-access-meta-grid strong {
  overflow: hidden;
  color: #20314f;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-access-step-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.ai-access-step {
  display: grid;
  grid-template-columns: auto minmax(86px, 0.7fr) minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(167, 190, 230, 0.3);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.56);
}

.ai-access-step span,
.ai-access-step small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-access-step span {
  color: #20314f;
  font-weight: 700;
}

.page-access-board {
  display: grid;
  gap: 12px;
  margin-top: 12px;
  padding: 14px;
  border: 1px solid rgba(167, 190, 230, 0.42);
  border-radius: 8px;
  background: rgba(248, 251, 255, 0.68);
}

.page-access-board-head,
.page-access-card-head,
.page-access-card-actions,
.page-access-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.page-access-board-head h3 {
  margin: 0;
  color: #14233b;
  font-size: 15px;
}

.page-access-board-head small,
.page-access-card small,
.page-access-status-pill small,
.page-access-empty,
.page-access-detail small,
.page-access-detail-meta,
.page-access-step-detail small {
  color: #63718f;
  font-size: 12px;
}

.page-access-progress {
  display: grid;
  gap: 10px;
}

.page-access-status-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.page-access-status-pill {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  padding: 7px 10px;
  border: 1px solid rgba(167, 190, 230, 0.34);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.5);
  color: #20314f;
  font-size: 13px;

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border-radius: 999px;
    background: rgba(238, 242, 255, 0.78);
    color: var(--brand-active);
    font-weight: 800;
  }
}

.page-access-card-list {
  display: grid;
  gap: 8px;
}

.page-access-card {
  display: grid;
  grid-template-columns: minmax(180px, 1.1fr) minmax(180px, 0.8fr) minmax(150px, 0.9fr) auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid rgba(167, 190, 230, 0.34);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.62);
}

.page-access-card-head span,
.page-access-detail-head span {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.page-access-card strong,
.page-access-detail strong {
  overflow: hidden;
  color: #20314f;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-access-card-meta,
.page-access-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.page-access-card-meta span,
.page-access-detail-meta span {
  padding: 3px 6px;
  border-radius: 6px;
  background: rgba(238, 242, 255, 0.7);
}

.page-access-card p {
  margin: 0;
  overflow: hidden;
  color: #4f5f81;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-access-card-actions {
  flex-shrink: 0;

  :deep(.el-button) {
    padding: 0 4px;
  }
}

@media (max-width: 1420px) {
  .page-access-status-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .page-access-card {
    grid-template-columns: minmax(180px, 1fr) minmax(170px, 0.7fr);
  }

  .page-access-card p,
  .page-access-card-actions {
    grid-column: 1 / -1;
  }
}

.page-access-empty {
  min-height: 76px;
  padding: 12px;
  border: 1px dashed rgba(167, 190, 230, 0.5);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.38);
}

.page-access-detail {
  display: grid;
  gap: 12px;
}

.page-access-step-detail-list {
  display: grid;
  gap: 10px;
  max-height: 460px;
  overflow: auto;
}

.page-access-step-detail {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid rgba(167, 190, 230, 0.34);
  border-radius: 8px;
  background: rgba(248, 251, 255, 0.78);
}

.page-access-step-detail > div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-access-step-detail p {
  margin: 0;
  color: #4f5f81;
  font-size: 13px;
}

.page-access-step-detail pre {
  max-height: 160px;
  margin: 0;
  overflow: auto;
  border-radius: 8px;
  background: #111827;
  padding: 10px;
  color: #d9e6ff;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
}

.ai-prompt-editor {
  :deep(.el-textarea__inner) {
    min-height: 520px !important;
    border-radius: 12px;
    background: rgba(248, 251, 255, 0.92);
    color: #20314f;
    font-family: Consolas, 'Courier New', monospace;
    font-size: 13px;
    line-height: 1.62;
    box-shadow:
      0 0 0 1px rgba(167, 190, 230, 0.4) inset,
      inset 0 1px 0 rgba(255, 255, 255, 0.88);
  }
}

@media (max-width: 1180px) {
  .step-progress {
    width: auto;
    min-height: auto;
  }

  .wizard-shell {
    grid-template-columns: 1fr;
  }

  .stage-shell {
    padding: 34px 0 62px;
  }

  .draft-console {
    grid-template-columns: 1fr;
  }

}

@media (max-width: 720px) {
  .page-assistant {
    padding: 14px;
  }

  .page-header {
    flex-direction: column;
    align-items: stretch;
  }

  .ai-prompt-toolbar {
    flex-wrap: wrap;
  }

  .step-progress,
  .health-grid {
    grid-template-columns: 1fr;
  }

  .step-progress {
    gap: 14px;
  }

  .focus-panel {
    padding: 22px;
  }

  .draft-generate-row {
    align-items: center;
  }

  .goal-card-grid {
    grid-template-columns: 1fr;
  }

  .draft-summary-strip {
    gap: 8px;
  }

  .action-toolbar,
  .action-card {
    grid-template-columns: 1fr;
  }

  .action-toolbar {
    align-items: stretch;
  }

  .action-card {
    display: flex;
    flex-direction: column;
    align-items: stretch;
  }

  .action-flags {
    justify-items: start;
  }

  .health-card strong {
    text-align: left;
    font-size: 36px;
  }

  .manual-grid {
    grid-template-columns: 1fr;
  }
}

/* SDK wizard aligned final skin: tech-violet glass for the frontend page access flow. */
:global(.main-layout.registry-shell:has(.page-assistant) .main-content) {
  background:
    radial-gradient(circle at 78% 0%, rgb(var(--brand-selected-rgb) / 0.92), transparent 28%),
    radial-gradient(circle at 20% 18%, rgb(var(--brand-selected-rgb) / 0.38), transparent 34%),
    radial-gradient(circle at 72% 72%, rgb(var(--brand-hover-rgb) / 0.16), transparent 32%),
    var(--brand-page-bg) !important;
}

:global(.main-layout.registry-shell:has(.page-assistant) .main-content::before) {
  opacity: 0 !important;
}

:global(.main-layout.registry-shell:has(.page-assistant) .topbar) {
  border-bottom-color: rgba(255, 255, 255, 0.5) !important;
  background:
    var(--brand-topbar-bg) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.5), 0 16px 34px rgb(var(--brand-primary-rgb) / 0.18) !important;
  backdrop-filter: blur(22px) saturate(1.1) !important;
}

:global(.main-layout.registry-shell:has(.page-assistant) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.page-assistant) .breadcrumb-area .el-breadcrumb__separator) {
  color: rgba(255, 255, 255, 0.84) !important;
}

:global(.main-layout.registry-shell:has(.page-assistant) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #ffffff !important;
}

:global(.main-layout.registry-shell:has(.page-assistant) .topbar-btn) {
  border-color: rgba(255, 255, 255, 0.7) !important;
  background: rgba(255, 255, 255, 0.48) !important;
  color: #4338ca !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 8px 20px rgb(var(--brand-primary-rgb) / 0.14) !important;
  backdrop-filter: blur(16px) !important;
}

:global(.main-layout.registry-shell:has(.page-assistant) .user-avatar) {
  box-shadow: 0 10px 24px rgb(var(--brand-active-rgb) / 0.24) !important;
}

.page-assistant {
  min-height: calc(100vh - 72px);
  padding: 8px 22px 12px;
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
  overflow-y: visible;
  background:
    linear-gradient(rgba(255, 255, 255, 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.13) 1px, transparent 1px),
    radial-gradient(circle at 78% 0%, rgb(var(--brand-selected-rgb) / 0.92), transparent 28%),
    radial-gradient(circle at 20% 18%, rgb(var(--brand-selected-rgb) / 0.38), transparent 34%),
    radial-gradient(circle at 72% 72%, rgb(var(--brand-hover-rgb) / 0.16), transparent 32%),
    var(--brand-page-bg) !important;
  background-size: 56px 56px, 56px 56px, auto, auto, auto, auto;
  color: #11183a;
}

.page-assistant::before {
  inset: 0;
  height: auto;
  background:
    radial-gradient(circle at 90% 18%, rgba(255, 255, 255, 0.78), transparent 23%),
    linear-gradient(110deg, transparent 0%, rgb(var(--brand-hover-rgb) / 0.12) 38%, transparent 66%);
  opacity: 0.9;
  transform: none;
}

.page-assistant::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  z-index: 0;
  width: min(520px, 42vw);
  height: 380px;
  background:
    linear-gradient(rgb(var(--brand-hover-rgb) / 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgb(var(--brand-primary-rgb) / 0.1) 1px, transparent 1px);
  background-size: 28px 28px;
  mask-image: linear-gradient(135deg, transparent 0%, #000 45%, #000 100%);
  opacity: 0.68;
  pointer-events: none;
}

.page-header {
  align-items: center;
  min-height: 88px;
  margin-bottom: 10px;
  padding: 11px 24px;
  overflow: hidden;
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.66) !important;
  border-radius: 8px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.6), rgb(var(--brand-selected-rgb) / 0.38)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 22px 48px rgb(var(--brand-primary-rgb) / 0.14) !important;
  backdrop-filter: blur(24px) saturate(1.08) !important;
}

.page-header::after {
  content: '';
  position: absolute;
  right: 5%;
  top: 30%;
  width: 48%;
  height: 62%;
  background:
    radial-gradient(circle at 12% 36%, rgba(255, 255, 255, 0.95) 0 2px, transparent 3px),
    radial-gradient(circle at 42% 20%, rgba(255, 255, 255, 0.86) 0 2px, transparent 3px),
    radial-gradient(circle at 68% 44%, rgba(255, 255, 255, 0.8) 0 2px, transparent 3px),
    linear-gradient(168deg, transparent 0%, rgba(255, 255, 255, 0.5) 47%, transparent 53%),
    repeating-linear-gradient(168deg, transparent 0 10px, rgba(255, 255, 255, 0.16) 11px 12px);
  opacity: 0.82;
  pointer-events: none;
}

.page-header h1 {
  margin: 4px 0 0;
  color: #11183a !important;
  font-size: 27px;
  font-weight: 850;
  line-height: 1.12;
  text-shadow: none !important;
}

.page-header p {
  color: #4f5f81 !important;
  font-size: 14px;
}

.page-header :deep(.el-button.is-link) {
  height: 24px;
  padding: 0 8px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.34);
  color: #4f5f81 !important;
  font-weight: 700;
}

.wizard-shell {
  grid-template-columns: minmax(300px, 315px) minmax(0, 1fr);
  gap: 12px;
  flex: 1;
  min-height: calc(100vh - 208px);
  align-items: stretch;
}

.step-progress,
.stage-shell,
.focus-panel,
.template-box {
  border-color: rgb(var(--brand-selected-rgb) / 0.66) !important;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.58), rgb(var(--brand-selected-rgb) / 0.36)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 22px 48px rgb(var(--brand-primary-rgb) / 0.14) !important;
  backdrop-filter: blur(24px) saturate(1.08) !important;
}

.step-progress {
  width: auto;
  min-height: calc(100vh - 208px);
  padding: 10px 8px 12px;
  border-radius: 8px;
}

.progress-step {
  min-height: 58px;
  padding: 5px 16px 5px 22px;
  border: 0 !important;
  border-radius: 8px;
  background: transparent !important;
  color: #203653 !important;
  box-shadow: none !important;
}

.progress-step::before {
  left: 37px;
  top: -10px;
  bottom: -10px;
  width: 1px;
  border-left: 0;
  background: rgba(109, 88, 181, 0.24);
}

.progress-step::after {
  display: none;
}

.progress-step:first-of-type::before {
  top: 28px;
}

.progress-step:last-of-type::before {
  bottom: 28px;
}

.progress-step:hover {
  background: rgba(255, 255, 255, 0.32) !important;
}

.progress-step.active {
  border: 1px solid rgba(255, 255, 255, 0.72) !important;
  background:
    linear-gradient(120deg, rgba(255, 255, 255, 0.78), rgb(var(--brand-selected-rgb) / 0.62)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 0 0 1px rgb(var(--brand-hover-rgb) / 0.32),
    0 0 24px rgb(var(--brand-hover-rgb) / 0.24),
    0 0 30px rgb(var(--brand-primary-rgb) / 0.18) !important;
  transform: none;
}

.progress-step.active::before {
  opacity: 1;
  transform: none;
}

.step-index {
  width: 26px;
  height: 26px;
  border-color: rgba(106, 133, 170, 0.34) !important;
  background: rgba(239, 246, 255, 0.86) !important;
  color: #53657f !important;
}

.progress-step.active .step-index,
.progress-step.done .step-index {
  border-color: rgb(var(--brand-primary-rgb) / 0.34) !important;
  background: rgba(238, 242, 255, 0.95) !important;
  color: var(--brand-active) !important;
  box-shadow: 0 0 18px rgb(var(--brand-hover-rgb) / 0.2) !important;
}

.progress-step.done.active .step-index {
  border-color: rgba(34, 197, 94, 0.32) !important;
  background: rgba(220, 252, 231, 0.96) !important;
  color: #16a34a !important;
  box-shadow: 0 0 18px rgba(34, 197, 94, 0.24) !important;
}

.progress-step strong {
  color: #14233b !important;
  font-size: 13px;
}

.progress-step small {
  color: #4f5f81 !important;
  font-size: 12px;
}

.stage-shell {
  min-height: calc(100vh - 208px);
  overflow: visible;
  border-radius: 8px;
  padding: 34px 18px 28px;
}

.stack-card {
  height: calc(100% - 58px);
  border-color: rgb(var(--brand-selected-rgb) / 0.46);
  background: rgba(255, 255, 255, 0.28);
  box-shadow: 0 18px 38px rgb(var(--brand-primary-rgb) / 0.08);
}

.focus-panel {
  height: auto;
  min-height: calc(100vh - 270px);
  overflow: visible;
  padding: 22px 26px 20px;
  border-radius: 8px;
  background: transparent !important;
}

.step-screen {
  min-height: calc(100vh - 318px);
}

.panel-head {
  margin-bottom: 14px;
}

.panel-head h2 {
  margin-top: 5px;
  color: #11183a !important;
  font-size: 28px;
  line-height: 1.18;
  text-shadow: none !important;
}

.panel-head p,
.step-footer-note :deep(.el-alert__description) {
  color: #4f5f81 !important;
}

.step-kicker {
  background: rgba(238, 242, 255, 0.68);
  color: var(--brand-active) !important;
  text-shadow: none !important;
}

.panel-head :deep(.el-tag),
.studio-ready-state,
.draft-summary-strip span,
.template-badges span {
  border-color: rgb(var(--brand-hover-rgb) / 0.28) !important;
  background: rgba(238, 242, 255, 0.68) !important;
  color: var(--brand-active) !important;
}

.health-grid {
  gap: 12px;
  margin-bottom: 14px;
}

.health-card {
  position: relative;
  display: grid;
  grid-template-areas:
    "icon label"
    "icon value";
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  column-gap: 10px;
  row-gap: 2px;
  min-height: 76px;
  overflow: hidden;
  padding: 12px 16px;
  border-color: rgb(var(--brand-hover-rgb) / 0.26) !important;
  border-radius: 8px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.74), rgb(var(--brand-selected-rgb) / 0.4)) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 16px 34px rgb(var(--brand-primary-rgb) / 0.09) !important;
}

.health-card::after {
  content: '';
  position: absolute;
  right: -12px;
  bottom: -50px;
  width: 82px;
  height: 82px;
  border-radius: 18px;
  background:
    linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.18), rgb(var(--brand-hover-rgb) / 0.1)),
    linear-gradient(rgb(var(--brand-primary-rgb) / 0.18) 1px, transparent 1px),
    linear-gradient(90deg, rgb(var(--brand-hover-rgb) / 0.14) 1px, transparent 1px);
  background-size: auto, 18px 18px, 18px 18px;
  transform: rotate(-25deg);
  opacity: 0.46;
}

.health-card .stat-icon {
  position: relative;
  z-index: 1;
  grid-area: icon;
  width: 34px;
  height: 34px;
  margin-bottom: 0;
  border: 1px solid rgb(var(--brand-hover-rgb) / 0.24);
  border-radius: 8px;
  background: rgba(238, 242, 255, 0.72);
  color: var(--brand-primary);
  box-shadow: none;
}

.health-card.stat-action .stat-icon {
  border-color: rgba(34, 197, 94, 0.22);
  background: rgba(220, 252, 231, 0.74);
  color: #16a34a;
}

.health-card.stat-active .stat-icon,
.health-card.stat-api .stat-icon {
  border-color: rgb(var(--brand-hover-rgb) / 0.24);
  background: rgba(238, 242, 255, 0.72);
  color: var(--brand-primary);
}

.health-card .stat-label,
.health-card small,
.health-label {
  position: relative;
  z-index: 1;
  grid-area: label;
  color: #4f5f81 !important;
  font-size: 12px !important;
  line-height: 1.2;
}

.health-card strong {
  position: relative;
  z-index: 1;
  grid-area: value;
  margin: 0;
  color: #071a35 !important;
  font-size: 24px;
  line-height: 1;
  text-align: left;
  text-shadow: none;
}

.page-row,
.action-toolbar,
.action-card,
.draft-config-card,
.goal-card,
.api-empty-card,
.studio-ready-hero,
.studio-ready-metrics div,
.studio-ready-empty,
.step-footer-note :deep(.el-alert) {
  border-color: rgb(var(--brand-selected-rgb) / 0.44) !important;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.7), rgb(var(--brand-selected-rgb) / 0.34)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 14px 30px rgb(var(--brand-primary-rgb) / 0.08) !important;
}

.page-row.selected,
.action-card.selected,
.goal-card.selected {
  border-color: rgb(var(--brand-hover-rgb) / 0.58) !important;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.82), rgb(var(--brand-selected-rgb) / 0.58)),
    radial-gradient(circle at 0% 0%, rgb(var(--brand-hover-rgb) / 0.18), transparent 42%) !important;
  box-shadow:
    0 16px 32px rgb(var(--brand-primary-rgb) / 0.14),
    inset 4px 0 0 rgb(var(--brand-primary-rgb) / 0.7) !important;
}

.goal-card-icon,
.api-empty-icon,
.template-icon {
  border-color: rgb(var(--brand-hover-rgb) / 0.24);
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.88), rgba(238, 242, 255, 0.72)),
    radial-gradient(circle at 28% 18%, rgba(255, 255, 255, 0.9), transparent 42%);
  color: var(--brand-primary);
  box-shadow: 0 10px 22px rgb(var(--brand-primary-rgb) / 0.12);
}

.goal-card.selected .goal-card-icon,
.draft-generate-row :deep(.el-button--primary),
.panel-actions :deep(.el-button--primary),
.studio-ready-actions .primary,
.studio-ready-empty button,
.copy-template-button.el-button,
.access-template-button.el-button {
  border-color: rgb(var(--brand-primary-rgb) / 0.34) !important;
  background:
    linear-gradient(135deg, var(--brand-primary), var(--brand-hover)),
    radial-gradient(circle at 18% 0%, rgba(255, 255, 255, 0.34), transparent 42%) !important;
  color: #ffffff !important;
  box-shadow: 0 12px 28px rgb(var(--brand-primary-rgb) / 0.24) !important;
}

.panel-actions .access-template-button.el-button {
  border: 1px solid rgb(var(--brand-selected-rgb) / 0.58) !important;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.76), rgb(var(--brand-selected-rgb) / 0.38)) !important;
  color: #27364f !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.88),
    0 10px 22px rgb(var(--brand-primary-rgb) / 0.1) !important;
}

.action-check {
  border-color: rgb(var(--brand-hover-rgb) / 0.28);
}

.action-card.selected .action-check {
  border-color: transparent;
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-hover));
  box-shadow: 0 8px 16px rgb(var(--brand-primary-rgb) / 0.24);
}

.draft-config-card :deep(.el-input__wrapper),
.draft-config-card :deep(.el-select__wrapper),
.draft-config-card :deep(.el-textarea__inner) {
  background: rgba(255, 255, 255, 0.72);
  box-shadow:
    0 0 0 1px rgb(var(--brand-selected-rgb) / 0.42) inset,
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
}

.template-modal {
  border-radius: 18px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.9), rgb(var(--brand-selected-rgb) / 0.74)),
    radial-gradient(circle at 0% 0%, rgb(var(--brand-hover-rgb) / 0.16), transparent 36%),
    radial-gradient(circle at 92% 8%, rgb(var(--brand-primary-rgb) / 0.12), transparent 32%);
  box-shadow:
    0 30px 70px rgb(var(--brand-primary-rgb) / 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    inset 0 0 0 1px rgb(var(--brand-selected-rgb) / 0.42);
}

.template-code-shell {
  border-color: rgb(var(--brand-hover-rgb) / 0.22);
  background:
    linear-gradient(180deg, rgba(244, 247, 255, 0.72), rgb(var(--brand-selected-rgb) / 0.56)),
    radial-gradient(circle at 0% 0%, rgb(var(--brand-hover-rgb) / 0.12), transparent 42%);
}

.template-code-shell pre {
  background:
    linear-gradient(135deg, rgba(15, 23, 42, 0.98), rgba(33, 29, 64, 0.98)),
    radial-gradient(circle at 0% 0%, rgb(var(--brand-primary-rgb) / 0.18), transparent 42%);
}

.stage-cue {
  border-color: rgb(var(--brand-hover-rgb) / 0.24);
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.56), rgb(var(--brand-selected-rgb) / 0.34));
  color: rgb(var(--brand-primary-rgb) / 0.72);
}

@media (min-width: 1600px) {
  .page-header {
    min-height: 88px;
  }

  .health-card {
    min-height: 76px;
  }
}
</style>

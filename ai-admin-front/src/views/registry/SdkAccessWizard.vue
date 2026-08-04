<template>
  <div class="sdk-access-page project-workbench-page workbench-page--list" :class="theme === 'dark' ? 'is-dark-skin' : 'is-light-skin'">
    <AppPageBackground local />
    <span class="page-ambient-center" aria-hidden="true" />
    <PageHeader variant="workbench" domain="project" title="项目接入工作台">
      <template #meta>
        <span>{{ project?.name || projectCode }} · {{ project?.projectCode || projectCode }} · SDK 接入</span>
      </template>
      <template #mode>
        <HeaderModeSwitch
          v-if="!projectMissing && !loadError"
          v-model="accessMode"
          :options="accessModeOptions"
          aria-label="SDK 接入方式"
        />
      </template>
    </PageHeader>

    <el-alert
      v-if="project && !isSdkBackedProject"
      class="page-alert"
      type="warning"
      show-icon
      :closable="false"
      title="当前项目不是 SDK 接入项目"
      description="项目接入工作台仅适用于 SDK 接入或混合接入项目；扫描方式项目请继续使用 API 目录和扫描项目工作台。"
    />

    <ProjectRouteMissingState
      v-if="projectMissing"
      :project-code="projectCode"
    />

    <ProjectWorkbenchLoadErrorState
      v-else-if="loadError"
      title="项目接入工作台加载失败"
      :message="loadError"
      :loading="loading"
      @retry="loadAll"
    />

    <main v-else class="access-workbench">
      <section v-show="accessMode === 'manual'" class="manual-access-pane wizard-shell">
        <section class="step-progress access-progress--refined" aria-label="SDK 接入步骤">
          <div class="access-progress">
            <span>
              接入进度
              <strong>{{ completedStepCount }}/{{ steps.length }}</strong>
              已完成
            </span>
            <div class="access-progress-track" aria-hidden="true">
              <i :style="{ width: `${completedPercent}%` }" />
            </div>
          </div>
          <button
            v-for="step in steps"
            :key="step.key"
            class="progress-step"
            :class="{ active: activeStep === step.key, done: step.done }"
            type="button"
            @click="activeStep = step.key"
          >
            <span class="step-index">
              <el-icon v-if="step.done"><Check /></el-icon>
              <span v-else class="step-dot" />
            </span>
            <span class="step-copy">
              <span class="step-title-line">
                <span class="step-number">{{ step.index }}</span>
                <strong>{{ step.title }}</strong>
              </span>
              <small>{{ step.status }}</small>
            </span>
            <el-icon v-if="activeStep === step.key" class="step-caret"><ArrowRight /></el-icon>
          </button>
        </section>

        <section class="stage-shell">
          <section class="focus-panel">
            <div v-if="activeStep === 'starter'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 1 / 5</span>
                <h2>后端 Starter</h2>
                <p>将 SDK Starter 引入到你的业务服务中，并完成基础配置。</p>
              </div>
              <el-tag type="danger" effect="light">必填</el-tag>
            </div>

            <section class="config-section">
              <h3>1. 下载并安装 Java SDK 制品</h3>
              <p>无需 ReachAI 源码仓库。平台给出固定版本的 JAR、独立 POM 和双 SHA-256；先安装 capability-sdk，再安装 Starter。</p>
              <CodeSnippetBlock
                v-if="javaSdkInstallSnippet"
                title="PowerShell · 平台制品安装"
                :code="javaSdkInstallSnippet"
                @copy="copyText"
              />
              <el-alert
                v-else
                type="warning"
                :closable="false"
                title="平台尚未返回 Java SDK 制品链接"
                description="请刷新项目接入信息；不要猜测 /maven、/repository 等下载地址。"
              />
            </section>

            <section class="config-section">
              <h3>2. 引入依赖（Maven）</h3>
              <p>制品安装完成后，将以下依赖添加到业务服务的 pom.xml。平台下载 API 不是 Maven 仓库，无需添加 repository。</p>
              <CodeSnippetBlock
                title="pom.xml"
                :code="starterDependencySnippet"
                :highlighted-code="highlightedStarterDependencySnippet"
                @copy="copyText"
              />
            </section>

            <section class="config-section">
              <h3>3. 配置文件（application.yml）</h3>
              <p>在 application.yml 中添加以下配置；项目密钥只通过环境变量注入。可用隐藏输入脚本写入当前 Windows 用户环境，随后用新终端启动业务服务。</p>
              <CodeSnippetBlock
                title="application.yml"
                :code="starterApplicationSnippet"
                :highlighted-code="highlightedStarterApplicationSnippet"
                @copy="copyText"
              />
              <CodeSnippetBlock
                v-if="registrySecretSetupSnippet"
                title="PowerShell · 隐藏输入 Secret"
                :code="registrySecretSetupSnippet"
                @copy="copyText"
              />
            </section>

            <section class="config-section config-check">
              <h3>4. 完成后请勾选</h3>
              <el-checkbox v-model="manualChecks.starter">我已完成 Starter 引入与配置</el-checkbox>
            </section>
          </div>

          <div v-else-if="activeStep === 'gateway'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 2</span>
                <h2>网关路由</h2>
              </div>
            </div>
            <el-form label-width="120px" class="inline-form">
              <el-form-item label="网关入口">
                <el-input v-model="gatewayBaseUrl" placeholder="例如 http://localhost:8080" />
              </el-form-item>
            </el-form>
            <CodeSnippetBlock
              title="application.yml"
              :code="gatewaySnippet"
              :highlighted-code="highlightedGatewaySnippet"
              @copy="copyText"
            />
            <el-checkbox v-model="manualChecks.gateway">我已配置网关路由并确认调用头会透传</el-checkbox>
          </div>

          <div v-else-if="activeStep === 'backend-check'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 3</span>
                <h2>业务服务校验</h2>
              </div>
              <el-tag effect="plain">{{ onlineInstanceCount }} 在线实例</el-tag>
            </div>
            <div class="check-list">
              <div v-for="item in backendChecks" :key="item.label" class="check-row" :class="item.status">
                <el-icon><component :is="item.icon" /></el-icon>
                <span>
                  <strong>{{ item.label }}</strong>
                  <small>{{ item.desc }}</small>
                </span>
              </div>
            </div>
          </div>

          <div v-else-if="activeStep === 'frontend'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 4</span>
                <h2>前端 Embed Token</h2>
              </div>
            </div>
            <el-form label-width="120px" class="inline-form">
              <el-form-item label="Token Broker">
                <el-input v-model="embedTokenPath" placeholder="/api/reachai/embed-token" />
              </el-form-item>
            </el-form>
            <CodeSnippetBlock
              title="frontend embed token"
              :code="frontendSnippet"
              :highlighted-code="highlightedFrontendSnippet"
              @copy="copyText"
            />
            <el-checkbox v-model="manualChecks.frontend">我已在业务前端接入短期 embed token，不在浏览器保存项目 secret</el-checkbox>
          </div>

          <div v-else class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 5</span>
                <h2>平台接入自检</h2>
              </div>
              <el-button type="primary" :loading="checking" @click="runCheck">发起自检</el-button>
            </div>
            <el-alert
              title="自检范围：项目配置、实例心跳与 SDK 签名回调"
              description="真实浏览器 Embed 会话由 AI Coding 任务的“浏览器 Embed 闭环”单独验收，不会由本自检代替。"
              type="info"
              :closable="false"
              show-icon
            />

            <div v-if="checkResult" class="check-result">
              <div class="result-head" :class="checkResult.overallStatus.toLowerCase()">
                <strong>整体结果：{{ statusLabel(checkResult.overallStatus) }}</strong>
                <span>{{ checkResult.projectCode }}</span>
              </div>
              <div v-if="checkResult.readiness?.length" class="readiness-list">
                <div
                  v-for="item in checkResult.readiness"
                  :key="item.key"
                  class="readiness-row"
                  :class="item.status.toLowerCase()"
                >
                  <strong>{{ item.label }}</strong>
                  <span>{{ statusLabel(item.status) }}</span>
                  <small>{{ item.message }}</small>
                </div>
              </div>
              <div class="result-list">
                <div v-for="item in checkResult.checks" :key="item.key" class="result-row" :class="item.status.toLowerCase()">
                  <span class="result-status">{{ statusLabel(item.status) }}</span>
                  <span>
                    <strong>{{ item.label }}</strong>
                    <small>{{ item.message }}</small>
                    <em v-if="item.evidence">{{ item.evidence }}</em>
                  </span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <footer class="wizard-footer">
          <span class="footer-actions">
            <el-button :disabled="activeStepIndex === 0" @click="goPrev">上一步</el-button>
            <el-button type="primary" :disabled="activeStepIndex === steps.length - 1" @click="goNext">下一步</el-button>
          </span>
          </footer>
        </section>
      </section>

      <section v-show="accessMode === 'ai-coding'" class="ai-coding-access-pane">
        <aside class="ai-coding-side step-progress ai-progress-panel access-progress--refined" aria-label="AI Coding 接入进度">
          <div class="access-progress">
            <span>
              AI 回传进度
              <strong class="ai-coding-progress-value">{{ aiAccessCompletedSteps }}/{{ aiAccessTotalSteps }}</strong>
              项已报告
            </span>
            <div class="access-progress-track" aria-hidden="true">
              <i :style="{ width: `${aiAccessProgressPercent}%` }" />
            </div>
          </div>
          <div class="ai-progress-meta">
            <span>
              <strong>AI 接入会话</strong>
              <small>{{ onboardingTask?.taskId || '等待创建任务' }}</small>
            </span>
            <el-tag size="small" effect="plain" :type="accessSessionTagType">
              {{ aiTaskStateLabel }}
            </el-tag>
            <el-button
              v-if="onboardingTask"
              text
              size="small"
              @click="openOnboardingTaskDetail"
            >
              任务详情
            </el-button>
          </div>
          <button
            v-for="(item, index) in aiDisplaySteps"
            :key="item.stepKey"
            class="progress-step ai-progress-step"
            :class="{
              active: item.status === 'RUNNING' || (index === 0 && item.status === 'TODO'),
              done: item.status === 'PASS',
              warn: item.status === 'WARN',
              fail: item.status === 'FAIL',
              skipped: item.status === 'SKIPPED',
            }"
            type="button"
            tabindex="-1"
          >
            <span class="step-index">
              <el-icon v-if="item.status === 'PASS'"><Check /></el-icon>
              <span v-else class="step-dot" />
            </span>
            <span class="step-copy">
              <span class="step-title-line">
                <span class="step-number">{{ index + 1 }}</span>
                <strong>{{ item.title }}</strong>
              </span>
              <small>{{ accessStatusLabel(item.status) }}</small>
            </span>
          </button>
        </aside>

        <section class="ai-coding-main">
          <span class="panel-glass-highlight" aria-hidden="true" />
          <el-alert
            v-if="aiTaskLoadError"
            class="page-alert"
            type="error"
            show-icon
            :closable="false"
            :title="aiTaskLoadError"
          >
            <el-button
              link
              type="primary"
              :loading="aiOnboardingPromptLoading"
              @click="retryOnboardingTaskLoad"
            >
              重新加载任务状态
            </el-button>
          </el-alert>
          <div class="panel-head">
            <div>
              <h2>将接入任务交给AI 编程工具</h2>
              <p>生成一次性交接包并复制到所选 AI 编程工具；进度、问题和结果通过当前任务回到 ReachAI。</p>
            </div>
          </div>

          <section class="ai-coding-grid">
            <div class="ai-coding-card">
              <span class="card-glass-highlight" aria-hidden="true" />
              <div class="ai-coding-card-inner">
                <div class="ai-coding-card-head">
                  <div>
                    <h3>1. 复制给 AI 工具</h3>
                  </div>
                  <button
                    type="button"
                    class="btn-copy-prompt"
                    :disabled="aiOnboardingPromptLoading || Boolean(aiTaskLoadError)"
                    :aria-busy="aiOnboardingPromptLoading"
                    :title="aiOnboardingPromptUnavailableReason"
                    @click="
                      aiOnboardingPromptReady
                        ? copyAiOnboardingPrompt()
                        : prepareAiOnboardingTask()
                    "
                  >
                    {{
                      aiOnboardingPromptLoading
                        ? '生成中…'
                        : aiOnboardingPromptReady
                          ? '复制交接包'
                          : '生成交接包'
                    }}
                  </button>
                </div>
                <AiCodingProviderSelector
                  v-model="aiPromptTool"
                  class="sdk-ai-provider-selector"
                  aria-label="选择 AI 编程工具"
                />
                <div
                  v-if="aiOnboardingPromptReady"
                  class="compact-handoff-meta"
                  aria-live="polite"
                >
                  <span>紧凑交接包</span>
                  <strong>{{ aiOnboardingPromptCharacterSummary }}</strong>
                </div>
                <el-input
                  class="ai-prompt-input ai-prompt-preview"
                  :model-value="aiOnboardingPromptPreview"
                  type="textarea"
                  :rows="14"
                  readonly
                />
              </div>
            </div>

            <div class="ai-coding-card">
              <span class="card-glass-highlight" aria-hidden="true" />
              <div class="ai-coding-card-inner">
                <div class="ai-coding-card-head">
                  <div>
                    <h3>2. SDK 回调与平台自检</h3>
                    <p>核对项目配置、实例心跳和签名回调；AI 回传报告仍沉淀到当前任务。</p>
                  </div>
                  <el-tooltip
                    :content="checking ? '自检中…' : (checkResult ? '重新发起手动自检' : '发起手动自检')"
                    placement="top"
                  >
                    <span class="btn-self-check-wrap">
                      <button
                        type="button"
                        class="btn-self-check"
                        :disabled="checking"
                        :aria-label="checking ? '自检中…' : (checkResult ? '重新发起手动自检' : '发起手动自检')"
                        @click="runCheck"
                      >
                        <el-icon :class="{ 'is-loading': checking }"><Refresh /></el-icon>
                      </button>
                    </span>
                  </el-tooltip>
                </div>
                <div
                  v-if="selfCheckSource !== 'NONE'"
                  class="ai-self-check-summary"
                  :class="{ pass: selfCheckVerified }"
                >
                  <strong>
                    {{
                      selfCheckSource === 'TASK'
                        ? (selfCheckVerified ? '当前任务平台验证已通过' : '当前任务平台验证仍有待处理项')
                        : (selfCheckVerified ? '最近一次手动自检已通过' : '最近一次手动自检仍有待处理项')
                    }}
                  </strong>
                  <span>
                    {{ selfCheckSource === 'TASK' ? '结果来自当前 AI Coding 任务' : '结果来自最近一次手动自检' }}
                  </span>
                </div>
                <div v-if="selfCheckReadiness.length" class="readiness-list ai-readiness-list">
                  <div
                    v-for="item in selfCheckReadiness"
                    :key="item.key"
                    class="readiness-row"
                    :class="item.status.toLowerCase()"
                  >
                    <strong>{{ item.label }}</strong>
                    <span>{{ statusLabel(item.status) }}</span>
                    <small>{{ item.message }}</small>
                  </div>
                </div>
                <div v-else class="ai-self-check-empty">
                  <span class="ai-self-check-glow" aria-hidden="true" />
                  <img class="ai-self-check-shield" src="/sdk-access-self-check-shield.png" alt="" />
                  <strong>尚无平台验证结果</strong>
                  <p>完成 AI 接入后，任务验证结果会自动显示在这里；也可以发起一次手动自检。</p>
                </div>
                <div v-if="checkResult?.checks?.length" class="result-list ai-result-list">
                  <div v-for="item in checkResult.checks" :key="item.key" class="result-row" :class="item.status.toLowerCase()">
                    <span class="result-status">{{ statusLabel(item.status) }}</span>
                    <span>
                      <strong>{{ item.label }}</strong>
                      <small>{{ item.message }}</small>
                      <em v-if="item.evidence">{{ item.evidence }}</em>
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </section>
      </section>
    </main>

    <AppDialog
      v-model="aiPromptDialogVisible"
      title="使用 AI 编程工具快速接入"
      width="880px"
      class="ai-onboarding-dialog"
      destroy-on-close
    >
      <el-alert
        class="ai-onboarding-alert"
        type="info"
        show-icon
        :closable="false"
        title="复制交接包到所选 AI 编程工具，让 AI 在业务系统代码仓库里完成 SDK 接入。"
        description="提示词不会包含 App Secret；AI 只会被要求使用本机环境变量或密钥管理器。"
      />
      <AiCodingProviderSelector
        v-model="aiPromptTool"
        class="ai-onboarding-provider-selector"
        aria-label="选择 AI 编程工具"
        compact
      />
      <div
        v-if="aiOnboardingPromptReady"
        class="compact-handoff-meta"
        aria-live="polite"
      >
        <span>紧凑交接包</span>
        <strong>{{ aiOnboardingPromptCharacterSummary }}</strong>
      </div>
      <el-input
        class="ai-prompt-input"
        :model-value="aiOnboardingPromptPreview"
        type="textarea"
        :rows="20"
        readonly
      />
      <template #footer>
        <el-button @click="aiPromptDialogVisible = false">关闭</el-button>
        <el-button
          type="primary"
          :icon="DocumentCopy"
          :loading="aiOnboardingPromptLoading"
          :disabled="Boolean(aiTaskLoadError)"
          @click="
            aiOnboardingPromptReady
              ? copyAiOnboardingPrompt()
              : prepareAiOnboardingTask()
          "
        >
          {{ aiOnboardingPromptReady ? '复制交接包' : '生成交接包' }}
        </el-button>
      </template>
    </AppDialog>

    <AppDrawer
      v-model="taskDrawerVisible"
      title="AI Coding 任务详情"
      size="720px"
      class="ai-onboarding-task-drawer"
    >
      <template #header="{ titleId, titleClass }">
        <div class="ai-task-drawer-header">
          <h4 :id="titleId" :class="titleClass">AI Coding 任务详情</h4>
          <el-button
            :icon="Refresh"
            :loading="taskActionBusy"
            :disabled="!onboardingTaskDetail?.task?.taskId"
            @click="handleOnboardingTaskRefresh"
          >
            刷新
          </el-button>
        </div>
      </template>
      <AiCodingTaskDetailPanel
        :detail="onboardingTaskDetail"
        :loading="taskActionBusy"
        :busy="taskActionBusy"
        @refresh="handleOnboardingTaskRefresh"
        @answer="handleOnboardingTaskAnswer"
        @reissue="handleOnboardingTaskReissue"
        @verify="handleOnboardingTaskVerification"
        @acceptance="handleOnboardingTaskAcceptance"
        @cancel="handleOnboardingTaskCancel"
      />
    </AppDrawer>
  </div>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import AiCodingProviderSelector from '@/components/ai-coding/AiCodingProviderSelector.vue'
import AiCodingTaskDetailPanel from '@/components/ai-coding/AiCodingTaskDetailPanel.vue'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Check,
  DocumentCopy,
  MagicStick,
  Pointer,
  Refresh,
} from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import AppPageBackground from '@/components/common/AppPageBackground.vue'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'
import HeaderModeSwitch, { type HeaderModeOption } from '@/components/common/HeaderModeSwitch.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import ProjectRouteMissingState from '@/views/registry/components/ProjectRouteMissingState.vue'
import ProjectWorkbenchLoadErrorState from '@/views/registry/components/ProjectWorkbenchLoadErrorState.vue'
import { useSdkAccessWizardActions } from '@/views/registry/composables/useSdkAccessWizardActions'
import { useSdkAccessWizardData } from '@/views/registry/composables/useSdkAccessWizardData'
import { useSdkAccessWizardNavigation } from '@/views/registry/composables/useSdkAccessWizardNavigation'
import { useSdkAccessWizardProgress } from '@/views/registry/composables/useSdkAccessWizardProgress'
import { useSdkAccessWizardSnippets } from '@/views/registry/composables/useSdkAccessWizardSnippets'
import { useSdkAccessWizardUiState } from '@/views/registry/composables/useSdkAccessWizardUiState'
import {
  aiAccessStepStatusLabel,
  sdkAccessCheckStatusLabel,
} from '@/views/registry/sdkAccessWizardViewModel'
import {
  aiCodingConnectionStatusLabel,
  aiCodingExecutionStatusLabel,
} from '@/utils/aiCodingPresentation'

const { theme } = useTheme()

const accessModeOptions: HeaderModeOption[] = [
  { value: 'manual', label: '手动接入', icon: Pointer },
  { value: 'ai-coding', label: 'AI Coding 接入', icon: MagicStick },
]

const {
  aiPromptTool,
  accessMode,
  activeStep,
  gatewayBaseUrl,
  embedTokenPath,
  manualChecks,
} = useSdkAccessWizardUiState()

const {
  projectCode,
  project,
  instances,
  loading,
  projectMissing,
  loadError,
  aiTaskLoadError,
  checking,
  aiPromptDialogVisible,
  aiOnboardingManifest,
  onboardingTask,
  onboardingTaskDetail,
  handoffPackage,
  selfCheckReadiness,
  selfCheckSource,
  selfCheckVerified,
  aiDisplaySteps,
  aiAccessCompletedSteps,
  aiAccessTotalSteps,
  aiOnboardingPrompt,
  aiOnboardingPromptLoading,
  aiOnboardingPromptReady,
  aiOnboardingPromptUnavailableReason,
  checkResult,
  isSdkBackedProject,
  onlineInstanceCount,
  loadAll,
  prepareAiOnboardingTask,
  runCheck,
  refreshOnboardingTask,
  retryOnboardingTaskLoad,
  reissueOnboardingHandoff,
  answerOnboardingQuestion,
  verifyOnboardingAcceptanceReadiness,
  finishOnboardingAcceptance,
  cancelOnboardingTask,
} = useSdkAccessWizardData({
  aiPromptTool,
})

const javaSdkInstallSnippet = computed(() =>
  (aiOnboardingManifest.value?.sdkArtifacts || [])
    .filter((artifact) => artifact.language === 'java')
    .map((artifact) => artifact.installCommandTemplate || '')
    .filter(Boolean)
    .join('\n\n'),
)

const registrySecretSetupSnippet = computed(
  () => aiOnboardingManifest.value?.security
    ?.secretSetupCommandTemplate || '',
)

const {
  steps,
  activeStepIndex,
  completedStepCount,
  completedPercent,
  accessSessionTagType,
  backendChecks,
} = useSdkAccessWizardProgress({
  activeStep,
  project,
  instances,
  onboardingTask,
  checkResult,
  isSdkBackedProject,
  onlineInstanceCount,
  gatewayBaseUrl,
  embedTokenPath,
  manualChecks,
})

const {
  starterDependencySnippet,
  starterApplicationSnippet,
  highlightedStarterDependencySnippet,
  highlightedStarterApplicationSnippet,
  gatewaySnippet,
  highlightedGatewaySnippet,
  frontendSnippet,
  highlightedFrontendSnippet,
} = useSdkAccessWizardSnippets({
  projectCode,
  project,
  aiOnboardingManifest,
  gatewayBaseUrl,
  embedTokenPath,
})

const {
  goPrev,
  goNext,
} = useSdkAccessWizardNavigation({
  activeStep,
  activeStepIndex,
  steps,
  projectCode,
})

const { copyText } = useSdkAccessWizardActions()

const taskDrawerVisible = ref(false)
const taskActionBusy = ref(false)

async function runTaskAction<T>(
  action: () => Promise<T>,
  errorMessage: string,
): Promise<T | null> {
  taskActionBusy.value = true
  try {
    return await action()
  } catch (error) {
    ElMessage.error((error as Error).message || errorMessage)
    return null
  } finally {
    taskActionBusy.value = false
  }
}

async function openOnboardingTaskDetail() {
  taskDrawerVisible.value = true
  await runTaskAction(
    () => refreshOnboardingTask(),
    '加载任务详情失败',
  )
}

async function handleOnboardingTaskRefresh() {
  await runTaskAction(
    () => refreshOnboardingTask(),
    '刷新任务详情失败',
  )
}

async function handleOnboardingTaskReissue(taskId: string) {
  const handoff = await runTaskAction(
    () => reissueOnboardingHandoff(taskId),
    '重新生成交接包失败',
  )
  if (handoff) {
    taskDrawerVisible.value = false
    aiPromptDialogVisible.value = true
  }
}

async function handleOnboardingTaskAnswer(
  taskId: string,
  questionId: string,
  answer: string,
) {
  const result = await runTaskAction(
    () => answerOnboardingQuestion(taskId, questionId, answer),
    '回答写回失败',
  )
  if (result) ElMessage.success('回答已写回当前任务')
}

async function handleOnboardingTaskAcceptance(
  taskId: string,
  passed: boolean,
  message: string,
) {
  const result = await runTaskAction(
    () => finishOnboardingAcceptance(taskId, passed, message),
    '写入验收结果失败',
  )
  if (result) {
    ElMessage[passed ? 'success' : 'warning'](
      passed ? '任务验收已通过' : '任务已标记为验收不通过',
    )
  }
}

async function handleOnboardingTaskVerification(taskId: string) {
  const result = await runTaskAction(
    () => verifyOnboardingAcceptanceReadiness(taskId),
    '平台验证失败',
  )
  if (result) {
    ElMessage[result.acceptanceReady ? 'success' : 'warning'](
      result.acceptanceReady
        ? '平台验证已通过，现在可以验收'
        : `仍有 ${result.blockers.length} 项未通过平台验证`,
    )
  }
}

async function handleOnboardingTaskCancel(taskId: string) {
  const result = await runTaskAction(
    () => cancelOnboardingTask(taskId),
    '取消任务失败',
  )
  if (result) ElMessage.success('任务已取消')
}

const aiOnboardingPromptPreview = computed(() =>
  aiOnboardingPromptReady.value
    ? aiOnboardingPrompt.value
    : aiOnboardingPromptUnavailableReason.value,
)

const aiOnboardingPromptCharacterSummary = computed(() => {
  const promptCharacters = handoffPackage.value?.promptCharacters
    ?? aiOnboardingPrompt.value.length
  const promptCharacterLimit = handoffPackage.value?.promptCharacterLimit
  return promptCharacterLimit
    ? `${promptCharacters} / ${promptCharacterLimit} 字符`
    : `${promptCharacters} 字符`
})

async function copyAiOnboardingPrompt() {
  if (!aiOnboardingPromptReady.value) return
  await copyText(aiOnboardingPrompt.value)
}

const statusLabel = sdkAccessCheckStatusLabel
const accessStatusLabel = aiAccessStepStatusLabel

const aiAccessProgressPercent = computed(() =>
  aiAccessTotalSteps.value
    ? Math.round((aiAccessCompletedSteps.value / aiAccessTotalSteps.value) * 100)
    : 0,
)

const aiTaskStateLabel = computed(() => {
  if (aiTaskLoadError.value) return '状态不可用'
  const task = onboardingTask.value
  if (!task) return '待创建'
  const connection = aiCodingConnectionStatusLabel(task.connection.status)
  const execution = aiCodingExecutionStatusLabel(task.executionStatus)
  return `${connection} · ${execution}`
})

onMounted(loadAll)
</script>

<style scoped lang="scss">
@use './styles/SdkAccessWizard.scss';
@use './styles/SdkAccessWizard.ai-coding.figma.scss' as aiCodingFigma;
</style>

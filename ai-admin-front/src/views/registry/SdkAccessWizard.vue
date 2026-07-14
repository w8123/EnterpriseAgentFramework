<template>
  <div class="sdk-access-page project-workbench-page" :class="theme === 'dark' ? 'is-dark-skin' : 'is-light-skin'">
    <AppPageBackground local />
    <span class="page-ambient-center" aria-hidden="true" />
    <PageHeader variant="workbench" domain="project" title="项目接入工作台">
      <template #meta>
        <span>{{ project?.name || projectCode }} · {{ project?.projectCode || projectCode }} · SDK 接入</span>
      </template>
      <template #mode>
        <HeaderModeSwitch
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
      description="SDK 接入向导仅适用于 SDK 接入或混合接入项目；扫描方式项目请继续使用 API 目录和扫描项目工作台。"
    />

    <main class="access-workbench">
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
              <h3>1. 引入依赖（Maven）</h3>
              <p>将以下依赖添加到业务服务的 pom.xml 中；依赖必须来自已有 Maven 仓库或本机 install，平台地址不是 Maven 仓库。</p>
              <CodeSnippetBlock
                title="pom.xml"
                :code="starterDependencySnippet"
                :highlighted-code="highlightedStarterDependencySnippet"
                @copy="copyText"
              />
            </section>

            <section class="config-section">
              <h3>2. 配置文件（application.yml）</h3>
              <p>在 application.yml 中添加以下配置；项目密钥只通过环境变量注入。</p>
              <CodeSnippetBlock
                title="application.yml"
                :code="starterApplicationSnippet"
                :highlighted-code="highlightedStarterApplicationSnippet"
                @copy="copyText"
              />
            </section>

            <section class="config-section config-check">
              <h3>3. 完成后请勾选</h3>
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
                <h2>最终自检</h2>
              </div>
              <el-button type="primary" :loading="checking" @click="runCheck">发起自检</el-button>
            </div>
            <el-form label-width="120px" class="inline-form">
              <el-form-item label="可选 API 调用">
                <el-select v-model="selectedScanToolId" filterable placeholder="完成 API 管理手动同步后，可选择项目接口做真实调用">
                  <el-option
                    v-for="tool in projectApiTools"
                    :key="tool.scanToolId"
                    :label="projectApiLabel(tool)"
                    :value="tool.scanToolId"
                  />
                </el-select>
                <div class="form-hint">接口扫描与同步请在 API 管理的“添加接口 / SDK 同步”中手动触发；这里不作为 SDK 接入完成条件。</div>
              </el-form-item>
              <el-form-item label="参数 JSON">
                <el-input v-model="argsText" type="textarea" :rows="7" placeholder='例如 { "teamName": "一班" }' />
              </el-form-item>
            </el-form>

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
              AI 接入进度
              <strong class="ai-coding-progress-value">{{ aiAccessCompletedSteps }}/{{ aiAccessTotalSteps }}</strong>
              已回传
            </span>
            <div class="access-progress-track" aria-hidden="true">
              <i :style="{ width: `${aiAccessProgressPercent}%` }" />
            </div>
          </div>
          <div class="ai-progress-meta">
            <span>
              <strong>AI 接入会话</strong>
              <small>{{ accessSession?.sessionId || '等待创建会话' }}</small>
            </span>
            <el-tag size="small" effect="plain" :type="accessSessionTagType">
              {{ accessStatusLabel(accessSession?.status || 'OPEN') }}
            </el-tag>
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
          <div class="panel-head">
            <div>
              <h2>将接入任务交给AI 编程工具</h2>
              <p>复制提示词到 Cursor、Claude Code 或 Codex，让它在业务系统仓库完成 Starter、网关、前端 Embed、Workflow 发布和平台自检。</p>
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
                  <button type="button" class="btn-copy-prompt" @click="copyText(aiOnboardingPrompt)">
                    复制提示词
                  </button>
                </div>
                <div class="ai-tool-tabs" role="tablist" aria-label="AI 工具类型">
                  <button
                    type="button"
                    role="tab"
                    :class="{ active: aiPromptTool === 'cursor' }"
                    :aria-selected="aiPromptTool === 'cursor'"
                    @click="aiPromptTool = 'cursor'"
                  >
                    Cursor
                  </button>
                  <button
                    type="button"
                    role="tab"
                    :class="{ active: aiPromptTool === 'claude' }"
                    :aria-selected="aiPromptTool === 'claude'"
                    @click="aiPromptTool = 'claude'"
                  >
                    Claude Code
                  </button>
                  <button
                    type="button"
                    role="tab"
                    :class="{ active: aiPromptTool === 'codex' }"
                    :aria-selected="aiPromptTool === 'codex'"
                    @click="aiPromptTool = 'codex'"
                  >
                    Codex
                  </button>
                  <span class="ai-tool-tabs-track" aria-hidden="true" />
                </div>
                <el-input
                  class="ai-prompt-input ai-prompt-preview"
                  :model-value="aiOnboardingPrompt"
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
                    <h3>2. 平台会话与最终自检</h3>
                    <p>AI 回传步骤会沉淀到左侧会话；自检仍复用原来的接入检查结果。</p>
                  </div>
                  <button type="button" class="btn-self-check" :disabled="checking" @click="runCheck">
                    {{ checking ? '自检中…' : '发起自检' }}
                  </button>
                </div>
                <div v-if="checkResult?.readiness?.length" class="readiness-list ai-readiness-list">
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
                <div v-else class="ai-self-check-empty">
                  <span class="ai-self-check-glow" aria-hidden="true" />
                  <img class="ai-self-check-shield" src="/sdk-access-self-check-shield.png" alt="" />
                  <strong>等待平台自检</strong>
                  <p>完成 AI 接入或手动配置后，可在这里或“最终自检”步骤发起同一套检查。</p>
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
        title="复制提示词到 Cursor、Claude Code 或 Codex，让 AI 在业务系统代码仓库里完成 SDK 接入。"
        description="提示词不会包含 App Secret；AI 只会被要求使用本机环境变量或密钥管理器。"
      />
      <section class="ai-coding-key-panel ai-coding-key-readonly">
        <div class="ai-coding-key-head">
          <div>
            <strong>AI Coding 接入秘钥</strong>
            <span>在项目详情中统一管理；此处只读展示，用于生成 AI 提示词 URL。</span>
          </div>
          <el-tag :type="aiCodingAccessEnabled ? 'success' : 'info'" effect="plain">
            {{ aiCodingAccessEnabled ? '已启用' : '未启用' }}
          </el-tag>
        </div>
        <div class="ai-coding-key-form">
          <el-input
            :model-value="aiCodingAccessDisplayKey"
            disabled
            show-password
            placeholder="未启用或未生成；请前往项目详情启用"
          />
          <el-button link type="primary" @click="goProjectDetail">前往项目详情管理</el-button>
        </div>
      </section>
      <el-tabs v-model="aiPromptTool" class="ai-tool-tabs">
        <el-tab-pane label="Cursor" name="cursor" />
        <el-tab-pane label="Claude Code" name="claude" />
        <el-tab-pane label="Codex" name="codex" />
      </el-tabs>
      <el-input
        class="ai-prompt-input"
        :model-value="aiOnboardingPrompt"
        type="textarea"
        :rows="20"
        readonly
      />
      <template #footer>
        <el-button @click="aiPromptDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="DocumentCopy" @click="copyText(aiOnboardingPrompt)">复制提示词</el-button>
      </template>
    </AppDialog>
  </div>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { computed, onMounted } from 'vue'
import {
  ArrowRight,
  Check,
  DocumentCopy,
  MagicStick,
  Pointer,
} from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import AppPageBackground from '@/components/common/AppPageBackground.vue'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'
import HeaderModeSwitch, { type HeaderModeOption } from '@/components/common/HeaderModeSwitch.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { formatProjectKindLabel } from '@/utils/projectLabels'
import { useSdkAccessWizardActions } from '@/views/registry/composables/useSdkAccessWizardActions'
import { useSdkAccessWizardData } from '@/views/registry/composables/useSdkAccessWizardData'
import { useSdkAccessWizardNavigation } from '@/views/registry/composables/useSdkAccessWizardNavigation'
import { useSdkAccessWizardProgress } from '@/views/registry/composables/useSdkAccessWizardProgress'
import { useSdkAccessWizardSnippets } from '@/views/registry/composables/useSdkAccessWizardSnippets'
import { useSdkAccessWizardUiState } from '@/views/registry/composables/useSdkAccessWizardUiState'
import {
  AI_ACCESS_DISPLAY_STEP_TITLES,
  aiAccessStepStatusLabel,
  projectApiToolLabel,
  sdkAccessCheckStatusLabel,
} from '@/views/registry/sdkAccessWizardViewModel'

const { theme } = useTheme()

const accessModeOptions: HeaderModeOption[] = [
  { value: 'manual', label: '手动接入', icon: Pointer },
  { value: 'ai-coding', label: 'AI Coding 接入', icon: MagicStick },
]

const {
  aiPromptTool,
  accessMode,
  activeStep,
  selectedScanToolId,
  argsText,
  gatewayBaseUrl,
  embedTokenPath,
  manualChecks,
} = useSdkAccessWizardUiState()

const {
  projectCode,
  project,
  instances,
  projectApiTools,
  loading,
  checking,
  aiPromptDialogVisible,
  aiOnboardingManifest,
  accessSession,
  aiCodingAccessEnabled,
  aiCodingAccessKey,
  aiCodingAccessDisplayKey,
  checkResult,
  isSdkBackedProject,
  onlineInstanceCount,
  callableProjectApiTools,
  loadAll,
  runCheck,
} = useSdkAccessWizardData({
  selectedScanToolId,
  argsText,
  gatewayBaseUrl,
  embedTokenPath,
  aiPromptTool,
})

const {
  steps,
  activeStepIndex,
  completedStepCount,
  completedPercent,
  accessSessionTagType,
  overviewCards,
  backendChecks,
} = useSdkAccessWizardProgress({
  activeStep,
  project,
  instances,
  projectApiTools,
  accessSession,
  checkResult,
  isSdkBackedProject,
  onlineInstanceCount,
  callableProjectApiTools,
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
  aiOnboardingPrompt,
} = useSdkAccessWizardSnippets({
  projectCode,
  project,
  aiOnboardingManifest,
  accessSession,
  aiCodingAccessEnabled,
  aiCodingAccessKey,
  aiPromptTool,
  gatewayBaseUrl,
  embedTokenPath,
})

const {
  goProjectDetail,
  goPrev,
  goNext,
} = useSdkAccessWizardNavigation({
  activeStep,
  activeStepIndex,
  steps,
  projectCode,
})

const { copyText } = useSdkAccessWizardActions()

const statusLabel = sdkAccessCheckStatusLabel
const accessStatusLabel = aiAccessStepStatusLabel
const projectApiLabel = projectApiToolLabel

const aiAccessTotalSteps = computed(() =>
  accessSession.value?.totalSteps || AI_ACCESS_DISPLAY_STEP_TITLES.length,
)

const aiAccessCompletedSteps = computed(() => accessSession.value?.completedSteps || 0)

const aiAccessProgressPercent = computed(() =>
  aiAccessTotalSteps.value
    ? Math.round((aiAccessCompletedSteps.value / aiAccessTotalSteps.value) * 100)
    : 0,
)

const aiDisplaySteps = computed(() => {
  if (accessSession.value?.steps?.length) {
    return accessSession.value.steps
  }
  return AI_ACCESS_DISPLAY_STEP_TITLES.map((title, index) => ({
    stepKey: `placeholder-${index}`,
    title,
    status: 'TODO' as const,
  }))
})

onMounted(loadAll)
</script>

<style scoped lang="scss">
@use './styles/SdkAccessWizard.scss';
@use './styles/SdkAccessWizard.ai-coding.figma.scss' as aiCodingFigma;
</style>

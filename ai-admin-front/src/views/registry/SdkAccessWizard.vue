<template>
  <div class="sdk-access-page" :class="theme === 'dark' ? 'is-dark-skin' : 'is-light-skin'">
    <header class="page-header">
      <div>
        <el-button link :icon="ArrowLeft" @click="goBack">返回项目详情</el-button>
        <h1>SDK 接入向导</h1>
        <p>{{ project?.name || projectCode }} · {{ project?.projectCode || projectCode }}</p>
      </div>
      <div class="header-actions">
        <el-tag v-if="project" effect="plain">{{ formatProjectKindLabel(project.projectKind || '-') }}</el-tag>
        <el-button :icon="Connection" :loading="aiPromptLoading" @click="openAiOnboardingPrompt">
          使用 AI 快速接入
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新状态</el-button>
      </div>
    </header>

    <el-alert
      v-if="project && !isSdkBackedProject"
      class="page-alert"
      type="warning"
      show-icon
      :closable="false"
      title="当前项目不是 SDK 接入项目"
      description="SDK 接入向导仅适用于 SDK 接入或混合接入项目；扫描方式项目请继续使用 API 目录和扫描项目工作台。"
    />

    <main class="wizard-shell">
      <section class="step-progress" aria-label="SDK 接入步骤">
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
        <div v-if="accessSession" class="ai-session-card">
          <div class="ai-session-head">
            <span>AI 接入会话</span>
            <el-tag size="small" effect="plain" :type="accessSessionTagType">
              {{ accessStatusLabel(accessSession.status) }}
            </el-tag>
          </div>
          <div class="ai-session-progress">
            <strong>{{ accessSession.completedSteps }}/{{ accessSession.totalSteps }}</strong>
            <span>{{ accessSession.sessionId }}</span>
          </div>
          <div class="ai-session-steps">
            <div
              v-for="item in accessSession.steps"
              :key="item.stepKey"
              class="ai-session-step"
              :class="item.status.toLowerCase()"
            >
              <i />
              <span>{{ item.title }}</span>
              <em>{{ accessStatusLabel(item.status) }}</em>
            </div>
          </div>
        </div>
      </section>

      <section class="stage-shell">
        <section class="focus-panel">
          <div v-if="activeStep === 'overview'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 1</span>
                <h2>项目识别</h2>
              </div>
              <el-tag :type="isSdkBackedProject ? 'success' : 'warning'" effect="plain">
                {{ isSdkBackedProject ? 'SDK 项目' : '不适用' }}
              </el-tag>
            </div>

            <div class="health-grid">
              <div v-for="item in overviewCards" :key="item.label" class="health-card" :class="[item.tone, item.accent]">
                <span class="health-icon">
                  <span v-if="item.iconText" class="health-icon-text">{{ item.iconText }}</span>
                  <el-icon v-else><component :is="item.icon" /></el-icon>
                </span>
                <span class="health-label">{{ item.label }}</span>
                <strong>{{ item.value }}</strong>
                <small>{{ item.desc }}</small>
              </div>
            </div>
          </div>

          <div v-else-if="activeStep === 'starter'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 2 / 6</span>
                <h2>后端 Starter</h2>
                <p>将 SDK Starter 引入到你的业务服务中，并完成基础配置。</p>
              </div>
              <el-tag type="danger" effect="light">必填</el-tag>
            </div>

            <section class="config-section">
              <h3>1. 引入依赖（Maven）</h3>
              <p>将以下依赖添加到业务服务的 pom.xml 中；依赖必须来自已有 Maven 仓库或本机 install，平台地址不是 Maven 仓库。</p>
              <div class="code-shell">
                <div class="code-toolbar">
                  <span>pom.xml</span>
                  <el-button size="small" text :icon="DocumentCopy" @click="copyText(starterDependencySnippet)">
                    复制
                  </el-button>
                </div>
                <pre class="code-panel"><code v-html="highlightedStarterDependencySnippet" /></pre>
              </div>
            </section>

            <section class="config-section">
              <h3>2. 配置文件（application.yml）</h3>
              <p>在 application.yml 中添加以下配置；项目密钥只通过环境变量注入。</p>
              <div class="code-shell">
                <div class="code-toolbar">
                  <span>application.yml</span>
                  <el-button size="small" text :icon="DocumentCopy" @click="copyText(starterApplicationSnippet)">
                    复制
                  </el-button>
                </div>
                <pre class="code-panel"><code v-html="highlightedStarterApplicationSnippet" /></pre>
              </div>
            </section>

            <section class="config-section config-check">
              <h3>3. 完成后请勾选</h3>
              <el-checkbox v-model="manualChecks.starter">我已完成 Starter 引入与配置</el-checkbox>
            </section>
          </div>

          <div v-else-if="activeStep === 'gateway'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 3</span>
                <h2>网关路由</h2>
              </div>
              <el-button :icon="DocumentCopy" @click="copyText(gatewaySnippet)">复制路由模板</el-button>
            </div>
            <el-form label-width="120px" class="inline-form">
              <el-form-item label="网关入口">
                <el-input v-model="gatewayBaseUrl" placeholder="例如 http://localhost:8080" />
              </el-form-item>
            </el-form>
            <pre class="code-panel"><code>{{ gatewaySnippet }}</code></pre>
            <el-checkbox v-model="manualChecks.gateway">我已配置网关路由并确认调用头会透传</el-checkbox>
          </div>

          <div v-else-if="activeStep === 'backend-check'" class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 4</span>
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
                <span class="step-kicker">步骤 5</span>
                <h2>前端 Embed Token</h2>
              </div>
              <el-button :icon="DocumentCopy" @click="copyText(frontendSnippet)">复制前端示例</el-button>
            </div>
            <el-form label-width="120px" class="inline-form">
              <el-form-item label="Token Broker">
                <el-input v-model="embedTokenPath" placeholder="/api/reachai/embed-token" />
              </el-form-item>
            </el-form>
            <pre class="code-panel"><code v-html="highlightedFrontendSnippet" /></pre>
            <el-checkbox v-model="manualChecks.frontend">我已在业务前端接入短期 embed token，不在浏览器保存项目 secret</el-checkbox>
          </div>

          <div v-else class="step-screen">
            <div class="panel-head">
              <div>
                <span class="step-kicker">步骤 6</span>
                <h2>最终自检</h2>
              </div>
              <el-button type="primary" :loading="checking" @click="runCheck">发起自检</el-button>
            </div>
            <el-form label-width="120px" class="inline-form">
              <el-form-item label="API 资产">
                <el-select v-model="selectedApiAssetId" filterable placeholder="选择一个接口做真实调用">
                  <el-option
                    v-for="asset in apiAssets"
                    :key="asset.apiId"
                    :label="assetLabel(asset)"
                    :value="asset.apiId"
                  />
                </el-select>
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
    </main>

    <el-dialog
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
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Connection,
  DocumentCopy,
  Refresh,
} from '@element-plus/icons-vue'
import { useTheme } from '@/composables/useTheme'
import { formatProjectKindLabel } from '@/utils/projectLabels'
import { useSdkAccessWizardActions } from '@/views/registry/composables/useSdkAccessWizardActions'
import { useSdkAccessWizardData } from '@/views/registry/composables/useSdkAccessWizardData'
import { useSdkAccessWizardNavigation } from '@/views/registry/composables/useSdkAccessWizardNavigation'
import { useSdkAccessWizardProgress } from '@/views/registry/composables/useSdkAccessWizardProgress'
import { useSdkAccessWizardSnippets } from '@/views/registry/composables/useSdkAccessWizardSnippets'
import { useSdkAccessWizardUiState } from '@/views/registry/composables/useSdkAccessWizardUiState'
import {
  aiAccessStepStatusLabel,
  apiAssetLabel,
  sdkAccessCheckStatusLabel,
} from '@/views/registry/sdkAccessWizardViewModel'

const { theme } = useTheme()

const {
  aiPromptTool,
  activeStep,
  selectedApiAssetId,
  argsText,
  gatewayBaseUrl,
  embedTokenPath,
  manualChecks,
} = useSdkAccessWizardUiState()

const {
  projectCode,
  project,
  instances,
  apiAssets,
  loading,
  checking,
  aiPromptLoading,
  aiPromptDialogVisible,
  aiOnboardingManifest,
  accessSession,
  aiCodingAccessEnabled,
  aiCodingAccessKey,
  aiCodingAccessDisplayKey,
  checkResult,
  isSdkBackedProject,
  onlineInstanceCount,
  callableApiAssets,
  loadAll,
  openAiOnboardingPrompt,
  runCheck,
} = useSdkAccessWizardData({
  selectedApiAssetId,
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
  apiAssets,
  accessSession,
  checkResult,
  isSdkBackedProject,
  onlineInstanceCount,
  callableApiAssets,
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
  goBack,
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
const assetLabel = apiAssetLabel

onMounted(loadAll)
</script>

<style scoped lang="scss">
@use './styles/SdkAccessWizard.scss';
</style>

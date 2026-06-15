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
              <p>将以下依赖添加到业务服务的 pom.xml 中。</p>
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
      <section class="ai-coding-key-panel">
        <div class="ai-coding-key-head">
          <div>
            <strong>AI Coding 接入秘钥</strong>
            <span>用于 Cursor、Claude Code、Codex 免登录读取本项目接入清单。</span>
          </div>
          <el-switch v-model="aiCodingAccessEnabled" active-text="启用" inactive-text="关闭" />
        </div>
        <div class="ai-coding-key-form">
          <el-input
            v-model="aiCodingAccessKey"
            :disabled="!aiCodingAccessEnabled"
            show-password
            placeholder="保存时为空会自动生成；清空并关闭后 AI 工具无法连接"
          />
          <el-button :loading="aiCodingAccessSaving" @click="saveAiCodingAccess">保存</el-button>
          <el-button @click="clearAiCodingAccess">清空并关闭</el-button>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  Box,
  Check,
  CircleCheck,
  Connection,
  DataBoard,
  DocumentCopy,
  Refresh,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listApiAssets } from '@/api/apiAsset'
import { listRegistryProjectInstances } from '@/api/registry'
import {
  getAiOnboardingManifest,
  getLatestAiAccessSession,
  getScanProjectDetail,
  getScanProjects,
  runAiAccessSessionChecks,
  startAiAccessSession,
  updateAiCodingAccess,
} from '@/api/scanProject'
import { useTheme } from '@/composables/useTheme'
import type { ApiAssetItem } from '@/types/apiAsset'
import type { ProjectInstance } from '@/types/registry'
import type {
  AiAccessSession,
  AiAccessStepStatus,
  AiOnboardingManifest,
  ScanProject,
  SdkAccessCheckResponse,
  SdkAccessCheckStatus,
} from '@/types/scanProject'
import { formatProjectKindLabel } from '@/utils/projectLabels'

type WizardStepKey = 'overview' | 'starter' | 'gateway' | 'backend-check' | 'frontend' | 'self-check'

const route = useRoute()
const router = useRouter()
const { theme } = useTheme()
const projectCode = computed(() => String(route.params.projectCode || ''))
const project = ref<ScanProject | null>(null)
const instances = ref<ProjectInstance[]>([])
const apiAssets = ref<ApiAssetItem[]>([])
const loading = ref(false)
const checking = ref(false)
const aiPromptLoading = ref(false)
const aiCodingAccessSaving = ref(false)
const aiPromptDialogVisible = ref(false)
const aiPromptTool = ref<'cursor' | 'claude' | 'codex'>('cursor')
const aiOnboardingManifest = ref<AiOnboardingManifest | null>(null)
const accessSession = ref<AiAccessSession | null>(null)
const aiCodingAccessEnabled = ref(false)
const aiCodingAccessKey = ref('')
const activeStep = ref<WizardStepKey>('overview')
const selectedApiAssetId = ref<number | null>(null)
const argsText = ref('{}')
const gatewayBaseUrl = ref('http://localhost:8080')
const embedTokenPath = ref('/api/reachai/embed-token')
const checkResult = ref<SdkAccessCheckResponse | null>(null)
const manualChecks = reactive({
  starter: false,
  gateway: false,
  frontend: false,
})

const isSdkBackedProject = computed(() => {
  const kind = project.value?.projectKind || ''
  return kind === 'REGISTERED' || kind === 'HYBRID'
})

const onlineInstanceCount = computed(() =>
  instances.value.filter((item) => item.status === 'ONLINE').length,
)

const callableApiAssets = computed(() =>
  apiAssets.value.filter((item) => item.enabled && item.agentVisible),
)

const activeStepIndex = computed(() => steps.value.findIndex((item) => item.key === activeStep.value))

const completedStepCount = computed(() => steps.value.filter((item) => item.done).length)

const completedPercent = computed(() =>
  steps.value.length ? Math.round((completedStepCount.value / steps.value.length) * 100) : 0,
)

const accessSessionTagType = computed(() => {
  const status = accessSession.value?.status
  if (status === 'PASS') return 'success'
  if (status === 'WARN' || status === 'RUNNING') return 'warning'
  if (status === 'FAIL') return 'danger'
  return 'info'
})

const steps = computed(() => [
  {
    index: 1,
    key: 'overview' as const,
    title: '项目识别',
    desc: '确认项目类型与基础状态',
    status: project.value && isSdkBackedProject.value ? '已完成' : '待确认',
    done: Boolean(project.value && isSdkBackedProject.value),
  },
  {
    index: 2,
    key: 'starter' as const,
    title: '后端 Starter',
    desc: '复制并确认后端配置',
    status: project.value?.registryCredentialConfigured || manualChecks.starter ? '已完成' : '进行中',
    done: Boolean(project.value?.registryCredentialConfigured || manualChecks.starter),
  },
  {
    index: 3,
    key: 'gateway' as const,
    title: '网关路由',
    desc: '配置业务网关转发规则',
    status: gatewayBaseUrl.value.trim() && manualChecks.gateway ? '已完成' : '待处理',
    done: Boolean(gatewayBaseUrl.value.trim() && manualChecks.gateway),
  },
  {
    index: 4,
    key: 'backend-check' as const,
    title: '业务服务校验',
    desc: '确认实例与 API 资产',
    status: onlineInstanceCount.value > 0 && callableApiAssets.value.length > 0 ? '已完成' : '待处理',
    done: onlineInstanceCount.value > 0 && callableApiAssets.value.length > 0,
  },
  {
    index: 5,
    key: 'frontend' as const,
    title: '前端 Embed Token',
    desc: '接入短期 token broker',
    status: embedTokenPath.value.trim() && manualChecks.frontend ? '已完成' : '待处理',
    done: Boolean(embedTokenPath.value.trim() && manualChecks.frontend),
  },
  {
    index: 6,
    key: 'self-check' as const,
    title: '最终自检',
    desc: '平台真实调用业务接口',
    status: checkResult.value?.overallStatus === 'PASS' ? '已完成' : '待处理',
    done: checkResult.value?.overallStatus === 'PASS',
  },
])

const overviewCards = computed(() => [
  {
    label: '接入方式',
    value: formatProjectKindLabel(project.value?.projectKind || '-'),
    desc: isSdkBackedProject.value ? '可使用 SDK 接入向导' : '请使用扫描项目工作台',
    tone: isSdkBackedProject.value ? 'good' : 'warn',
    icon: Box,
    accent: 'access',
  },
  {
    label: '服务端凭证',
    value: project.value?.registryCredentialConfigured ? '已配置' : '未配置',
    desc: '真实 secret 不会返回到浏览器',
    tone: project.value?.registryCredentialConfigured ? 'good' : 'warn',
    icon: CircleCheck,
    accent: 'credential',
  },
  {
    label: 'SDK 实例',
    value: `${onlineInstanceCount.value} 在线`,
    desc: `${instances.value.length} 个实例已登记`,
    tone: onlineInstanceCount.value > 0 ? 'good' : 'neutral',
    icon: DataBoard,
    accent: 'instances',
  },
  {
    label: 'API 资产',
    value: `${callableApiAssets.value.length} 可调用`,
    desc: `${apiAssets.value.length} 个接口已进入目录`,
    tone: callableApiAssets.value.length > 0 ? 'good' : 'neutral',
    icon: Connection,
    iconText: 'API',
    accent: 'assets',
  },
])

const backendChecks = computed(() => [
  {
    label: '项目类型',
    desc: isSdkBackedProject.value ? 'SDK / 混合接入项目' : '当前不是 SDK 项目',
    status: isSdkBackedProject.value ? 'pass' : 'fail',
    icon: isSdkBackedProject.value ? CircleCheck : Warning,
  },
  {
    label: '服务端凭证',
    desc: project.value?.registryCredentialConfigured ? '后端已保存对接凭证' : '需要先配置服务端对接凭证',
    status: project.value?.registryCredentialConfigured ? 'pass' : 'fail',
    icon: project.value?.registryCredentialConfigured ? CircleCheck : Warning,
  },
  {
    label: '实例心跳',
    desc: onlineInstanceCount.value > 0 ? `${onlineInstanceCount.value} 个在线实例` : '暂未检测到在线实例',
    status: onlineInstanceCount.value > 0 ? 'pass' : 'warn',
    icon: onlineInstanceCount.value > 0 ? CircleCheck : Warning,
  },
  {
    label: 'API 资产',
    desc: callableApiAssets.value.length > 0 ? `${callableApiAssets.value.length} 个可调用接口` : '暂未检测到可调用接口',
    status: callableApiAssets.value.length > 0 ? 'pass' : 'warn',
    icon: callableApiAssets.value.length > 0 ? CircleCheck : Warning,
  },
])

const starterDependencySnippet = computed(() => `<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-spring-boot2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>`)

const starterApplicationSnippet = computed(() => `reachai:
  registry:
    enabled: true
    url: \${REACHAI_REGISTRY_URL:http://localhost:18603}
    app-key: ${project.value?.registryAppKey || 'your-app-key'}
    app-secret: \${REACHAI_REGISTRY_APP_SECRET}
    heartbeat-interval-ms: 30000
  project:
    code: ${project.value?.projectCode || projectCode.value}
    name: ${project.value?.name || 'your-service-name'}
    base-url: ${project.value?.baseUrl || 'http://localhost:8080'}
    context-path: ${project.value?.contextPath || ''}
    environment: ${project.value?.environment || 'dev'}
  capability:
    scan-beans: true
    sync-on-startup: true`)

const highlightedStarterDependencySnippet = computed(() => highlightXmlCode(starterDependencySnippet.value))
const highlightedStarterApplicationSnippet = computed(() => highlightYamlCode(starterApplicationSnippet.value))

const gatewaySnippet = computed(() => `spring:
  cloud:
    gateway:
      routes:
        - id: ${project.value?.projectCode || projectCode.value}-reachai
          uri: ${project.value?.baseUrl || 'http://localhost:18089'}
          predicates:
            - Path=/reachai/capabilities/**
          filters:
            - PreserveHostHeader

# 必须透传：
# X-ReachAI-Invocation-Token
# X-ReachAI-Trace-Id / X-ReachAI-Run-Id
# 业务用户身份头或当前登录态`)

const defaultEmbedAgentId = computed(() =>
  aiOnboardingManifest.value?.agentWorkflow?.globalAgentKeySlug
  || aiOnboardingManifest.value?.embed?.defaultAgentKeySlug
  || aiOnboardingManifest.value?.embed?.defaultAgentId
  || 'manifest-embed-agent-not-configured',
)

const frontendSnippet = computed(() => `import { createEafChat } from '@reachai/frontend-sdk'

const pageInstanceId = sessionStorage.getItem('reachaiPageInstanceId') || crypto.randomUUID()
sessionStorage.setItem('reachaiPageInstanceId', pageInstanceId)

createEafChat({
  mount: '#reachai-chat',
  apiBase: '${gatewayBaseUrl.value || 'http://localhost:8080'}',
  agentId: '${defaultEmbedAgentId.value}',
  page: {
    pageKey: '<current-page-key>',
    name: '<current-page-name>',
    routePattern: window.location.pathname,
  },
  tokenProvider: async () => {
    const query = new URLSearchParams({
      projectCode: '${project.value?.projectCode || projectCode.value}',
      agentId: '${defaultEmbedAgentId.value}',
      pageKey: '<current-page-key>',
      pageInstanceId,
      route: window.location.pathname,
      origin: window.location.origin
    })
    const payload = await fetch('${embedTokenPath.value || '/api/reachai/embed-token'}?' + query).then((res) => res.json())
    const token = payload.data?.token || payload.token
    if (!token) throw new Error('ReachAI embed token missing')
    return token
  }
})

// SDK 接入项目不在浏览器保存 appSecret，也不使用 pageRegistry 自动上报密钥。`)

const highlightedFrontendSnippet = computed(() => highlightJsCode(frontendSnippet.value))

const aiOnboardingPrompt = computed(() => {
  const manifest = aiOnboardingManifest.value
  const p = project.value
  const projectId = manifest?.project.id || p?.id || ''
  const code = manifest?.project.projectCode || p?.projectCode || projectCode.value
  const name = manifest?.project.name || p?.name || code
  const appKey = manifest?.project.registryAppKey || p?.registryAppKey || 'your-app-key'
  const secretEnv = manifest?.security.appSecretEnv || 'REACHAI_REGISTRY_APP_SECRET'
  const skillPackageUrl = manifest?.endpoints.skillPackageUrl || '/api/ai-assist/skills/reachai-onboarding/latest.zip'
  const platformManifestUrl = manifest?.endpoints.manifestUrl || `/api/ai-assist/projects/${projectId}/onboarding-manifest`
  const embedAgentId = manifest?.embed?.defaultAgentKeySlug || manifest?.embed?.defaultAgentId || ''
  const embedAgentLine = embedAgentId
    ? `默认嵌入 Agent：${embedAgentId}`
    : '默认嵌入 Agent：平台尚未给该项目配置可嵌入 Agent；请先在 ReachAI 项目下创建/启用 Agent，再继续业务前端接入。'
  const allowedAgents = manifest?.embed?.allowedAgents || []
  const allowedAgentLine = allowedAgents.length
    ? `可嵌入 Agent 清单：${allowedAgents.map((item) => item.keySlug || item.id).join(', ')}`
    : '可嵌入 Agent 清单：空'
  const externalManifestUrl =
    aiCodingAccessEnabled.value && aiCodingAccessKey.value.trim()
      ? appendQuery(platformManifestUrl, 'aiCodingKey', aiCodingAccessKey.value.trim())
      : platformManifestUrl
  const platformUrl = manifest?.sdk.config.registryUrl || window.location.origin
  const agentWorkflow = manifest?.agentWorkflow
  const globalAgentKeySlug = agentWorkflow?.globalAgentKeySlug || embedAgentId || `${code}-global-ai-assistant`
  const agentWorkflowBlock = [
    `- Agent/Workflow model: ${agentWorkflow?.model || 'agent-workflow.decoupled.v1'}`,
    `- Global embedded agent keySlug: ${globalAgentKeySlug}`,
    `- Global embedded agent kind: ${agentWorkflow?.globalAgentKind || 'GLOBAL_EMBED'}`,
    `- Workflow storage target: ${agentWorkflow?.workflowStorage || 'ai_workflow'}`,
    `- SDK graph workflow type: ${agentWorkflow?.sdkGraphWorkflowType || 'SDK_GRAPH'}`,
    `- Binding strategy: ${agentWorkflow?.bindingStrategy || 'Bind page/action/intent workflows to the global embedded agent.'}`,
    `- Agents API: ${agentWorkflow?.endpoints?.agentsUrl || `${platformUrl}/api/agents`}`,
    `- Workflows API: ${agentWorkflow?.endpoints?.workflowsUrl || `${platformUrl}/api/workflows`}`,
    `- Bindings API: ${agentWorkflow?.endpoints?.globalAgentBindingsUrl || `${platformUrl}/api/agents/${globalAgentKeySlug}/workflow-bindings`}`,
  ].join('\n')
  const globalPageRoutingBlock = [
    `- Use one global embedded AI button with agentId/keySlug: ${globalAgentKeySlug}.`,
    '- Each business page must pass its stable pageKey to both the embed token broker and createEafChat({ page }).',
    '- The browser SDK creates /api/embed/chat/sessions with pageKey, route, pageInstanceId and bridgeActions.',
    '- ReachAI resolves the runnable Workflow from ai_agent_workflow_binding by current pageKey/action/intent.',
    '- Do not create one floating AI button per workflow. Page assistants are page workflows bound to the global Agent entry.'
  ].join('\n')
  const aiCodingKeyLine =
    aiCodingAccessEnabled.value && aiCodingAccessKey.value.trim()
      ? `AI Coding 接入秘钥：${aiCodingAccessKey.value.trim()}`
      : 'AI Coding 接入秘钥：已关闭，外部 AI 工具无法免登录读取 manifest'
  const toolName =
    aiPromptTool.value === 'cursor'
      ? 'Cursor'
      : aiPromptTool.value === 'claude'
        ? 'Claude Code'
        : 'Codex'
  const session = accessSession.value
  const sessionId = session?.sessionId || ''
  const reportUrlPattern = sessionId
    ? `${platformUrl}/api/ai-assist/projects/${projectId}/access-sessions/${sessionId}/steps/{stepKey}/report`
    : `${platformUrl}/api/ai-assist/projects/${projectId}/access-sessions/{sessionId}/steps/{stepKey}/report`
  const reportUrlPatternWithKey =
    aiCodingAccessEnabled.value && aiCodingAccessKey.value.trim()
      ? appendQuery(reportUrlPattern, 'aiCodingKey', aiCodingAccessKey.value.trim())
      : reportUrlPattern
  const latestSessionUrl =
    aiCodingAccessEnabled.value && aiCodingAccessKey.value.trim()
      ? appendQuery(`${platformUrl}/api/ai-assist/projects/${projectId}/access-sessions/latest`, 'aiCodingKey', aiCodingAccessKey.value.trim())
      : `${platformUrl}/api/ai-assist/projects/${projectId}/access-sessions/latest`
  const sessionCheckUrl = sessionId
    ? `${platformUrl}/api/ai-assist/projects/${projectId}/access-sessions/${sessionId}/checks/run`
    : `${platformUrl}/api/ai-assist/projects/${projectId}/access-sessions/{sessionId}/checks/run`
  const installHint =
    aiPromptTool.value === 'cursor'
      ? '如果当前工具不支持直接安装 Skill，请下载 zip 后读取其中的 SKILL.md，并把 references/、templates/、scripts/ 作为本次任务的工作资料。'
      : aiPromptTool.value === 'claude'
        ? '如果可以写入项目级 Skill，请把 zip 解压到当前业务仓库的 .claude/skills/reachai-onboarding/；否则读取 SKILL.md 后按其中流程执行。'
        : '如果当前 Codex 环境支持项目 skill，请安装或引用该 zip；否则读取 SKILL.md，并把它作为本次任务的最高优先级接入规则。'

  return `你现在要在当前业务系统代码仓库中接入 ReachAI AI 能力中台，请使用 ${toolName} 完成。

请先下载并使用 ReachAI AI 快速接入包：
- Skill 包地址：${skillPackageUrl}
- 项目接入清单：${externalManifestUrl}
- ReachAI 平台地址：${platformUrl}
- 项目 ID：${projectId}
- 项目编码：${code}
- 项目名称：${name}
- App Key：${appKey}
- ${aiCodingKeyLine}
- App Secret 环境变量：${secretEnv}
- AI 接入会话 ID：${sessionId || '请先用 latest session 接口获取'}
- AI 接入会话查询：${latestSessionUrl}
- 步骤进度回传 URL：${reportUrlPatternWithKey}
- 平台会话化自检 URL：${sessionCheckUrl}
- Embed Token Broker：${manifest?.embed?.tokenPath || embedTokenPath.value || '/api/reachai/embed-token'}
- ${embedAgentLine}
- ${allowedAgentLine}

Agent/Workflow target model:
${agentWorkflowBlock}

Global AI page routing contract:
${globalPageRoutingBlock}

安装/读取要求：
${installHint}

安全要求：
- 不要让我把 App Secret 粘贴到聊天上下文。
- 不要把 App Secret 写入 Git 仓库、Markdown、日志或最终总结。
- 如果需要密钥，请提示我在本机设置环境变量 ${secretEnv}。
- 不要修改与 ReachAI 接入无关的业务代码。

执行步骤：
1. 先检查当前项目的 Java 版本、Spring Boot 版本、Maven 模块结构、启动模块和配置文件位置。
2. 读取项目接入清单 manifest，确认 SDK 版本、Maven 依赖、registry url、project code、app key、base url。
3. 在正确的 Maven 模块中引入 reachai-capability-sdk 和 reachai-spring-boot2-starter。
4. 识别业务代码主包名，只扫描业务包下的 Controller / Service，不要把业务系统依赖的框架包、平台包、第三方包接口同步到 ReachAI。若启动类根包过宽，请优先选择实际业务包。
5. 在业务系统配置中增加 reachai.registry、reachai.project、reachai.capability 配置，并用 ${secretEnv} 引用密钥；同时配置 reachai.capability.scan-packages 与 reachai.capability.exclude-packages。
6. 根据现有 Controller / Service 代码，优先选择 1-2 个低风险查询能力，补充 @ReachCapability、@ReachParam、@ReachOutput 示例。
7. 检查业务系统是否有统一网关模块、Spring Cloud Gateway 配置、Nginx 配置或前端 dev proxy。若有网关，必须补上 ReachAI 相关路由；若没有网关，必须在计划里说明缺口，不要把 secret 下沉到浏览器。
8. 在业务网关或服务端 token broker 中实现前端获取 embed token 的接口，默认路径可用 ${embedTokenPath.value || '/api/reachai/embed-token'}。该接口必须从业务登录态解析当前用户，映射 principal.externalUserId，使用项目 appKey/appSecret 服务端签名调用 ReachAI 的 POST /api/embed/token/exchange，并按短期 token 策略缓存；appSecret 仍只能来自 ${secretEnv} 或密钥管理器。
9. 在业务前端接入 ReachAI Chat Embed：增加配置、组件或页面入口，agentId 使用 ${embedAgentId || 'manifest.embed.defaultAgentKeySlug / defaultAgentId'}，让前端通过业务网关 token broker 获取 embed token，再用 token 调用 ReachAI /api/embed/chat/sessions 与消息接口。前端不得保存 appSecret，不得使用 pageRegistry.appSecret 自动上报密钥。
10. 明确区分两类 Authorization：请求 ${embedTokenPath.value || '/api/reachai/embed-token'} 时使用业务系统登录 token；请求 /api/reachai/embed/**、/api/embed/chat/sessions 或消息接口时只能使用 ReachAI 返回的短期 embed token。不要把业务登录 token 当作 embed token 传给 ReachAI Chat API。
11. 修改业务网关白名单 / 安全链：${embedTokenPath.value || '/api/reachai/embed-token'} 继续使用业务登录 token；但 /api/reachai/embed/** 是 ReachAI embed token 代理流量，必须绕过业务 OAuth/JWT 认证并原样透传 Authorization: Bearer <embedToken> 给 ReachAI。
12. 如果业务系统用 Spring Cloud Gateway 代理 /api/reachai/embed/** 到 ReachAI /api/embed/**，检查是否会同时由网关和 ReachAI 返回 CORS 头；若会重复，请在该路由增加类似 DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST 的响应头去重配置，避免浏览器把真实 401/500 遮蔽成 status 0 Unknown Error。
13. 业务前端缓存 embed token 时必须按 expiresIn 提前失效；如果创建 session 或发送消息返回 embed token is expired，应清空缓存、重新调用 token broker 获取新 embed token 并重试一次。
14. 保证网关转发时透传 X-ReachAI-Invocation-Token、X-ReachAI-Trace-Id、X-ReachAI-Run-Id，以及业务身份所需的 Authorization / 用户上下文头；业务接口不能只凭普通 X-ReachAI-* 上下文头放行。
15. 分别运行业务后端、网关和业务前端的最小可行编译/构建/测试。
16. 如果业务系统、网关和 ReachAI 服务可访问，调用 manifest 中的 sdkAccessCheckUrl 做接入自检，body 中带 gatewayBaseUrl=${gatewayBaseUrl.value || 'http://localhost:8080'} 与 embedTokenPath=${embedTokenPath.value || '/api/reachai/embed-token'}；否则说明缺少的本地前置条件。
17. 最后给出修改文件清单、验证结果、仍需人工配置的密钥或环境变量。

进度回传要求：
- 每完成或卡住一个关键步骤，请 POST 到“步骤进度回传 URL”，把 {stepKey} 替换为下列 key 之一：project-manifest、backend-sdk、reachai-config、capability-scan、gateway-route、embed-token-broker、gateway-whitelist、frontend-embed、connectivity-check、handoff-summary。
- 请求体格式：{"status":"PASS|WARN|FAIL|RUNNING","message":"一句话说明","files":["相对路径"],"evidence":{"command":"执行过的命令","exitCode":0},"reportedBy":"${toolName}"}。
- 如果你不能访问平台接口，请在最终总结里说明无法回传；如果可以访问，不要只在聊天里报告进度。
- 做最终自检时优先调用“平台会话化自检 URL”，它会把检查结果同步写入当前会话。

业务网关要求：
- 网关需要暴露前端可调用的 embed token broker，例如 GET ${manifest?.embed?.tokenPath || embedTokenPath.value || '/api/reachai/embed-token'}?projectCode=${code}&agentId=${embedAgentId || '<manifest.embed.defaultAgentKeySlug>'}&pageInstanceId=...&route=...&origin=...。
- token broker 服务端再调用 ReachAI POST /api/embed/token/exchange，请求体至少包含 projectCode、agentId、pageInstanceId、route、origin、principal.externalUserId。
- 网关需要把 /api/reachai/embed/** 配成匿名代理或独立安全链，不能用业务 OAuth/JWT 校验 ReachAI embed token；如果有全局认证过滤器，也要跳过该路径。
- Spring Cloud Gateway 代理 /api/reachai/embed/** 时，若网关和 ReachAI 都会写 CORS 响应头，请在该路由配置 DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST，避免重复 CORS 头导致浏览器显示 status 0 Unknown Error。
- 如果项目已有 Spring Cloud Gateway 路由，请补充到对应 application.yml / bootstrap.yml / 配置中心文件；如果是 Nginx 或前端代理，请补充到实际使用的网关配置。

业务前端要求：
- 在真实业务页面接入对话入口，不要只写 README 示例。
- tokenProvider 只能请求业务网关 token broker，不能在浏览器拼 appSecret、registry 签名或项目级密钥。
- tokenProvider 请求业务网关 token broker 时使用业务登录 token；创建 ReachAI session 和发送消息时使用 token broker 返回的短期 embed token，两者不能混用。
- 前端缓存 embed token 必须按 expiresIn 提前失效；遇到 embed token is expired 时清缓存、重新获取并重试一次。
- pageInstanceId、route、origin 要由前端运行时生成并传给 token broker，便于 ReachAI 做会话隔离、审计和页面动作回传。

扫描边界要求：
- 必须先从启动类、业务 Controller / Service 包、Maven 模块名中推断业务代码主包，例如 com.company.order 或 com.xxx.biz。
- reachai.capability.scan-packages 只填写业务代码包；不要填写 org.springframework、springfox、org.springdoc、com.baomidou、框架基座包、通用平台包或 SDK 包。
- reachai.capability.exclude-packages 至少排除 org.springframework、springfox、org.springdoc、com.enterprise.ai.reach；如果项目有 hussar、framework、common-web、platform 等框架包，也要排除。
- 如果无法可靠判断业务包，先在计划里列出候选包并等待我确认，不要默认扫描整个根包。

请先输出你识别到的项目结构和接入计划，等我确认后再改代码。`
})

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function appendQuery(url: string, key: string, value: string) {
  const separator = url.includes('?') ? '&' : '?'
  return `${url}${separator}${encodeURIComponent(key)}=${encodeURIComponent(value)}`
}

function highlightXmlCode(value: string) {
  return escapeHtml(value).replace(
    /(&lt;\/?)([\w.-]+)(&gt;)|([A-Za-z0-9_.:-]+)(?=&lt;\/[\w.-]+&gt;)/g,
    (token, tagOpen, tagName, tagClose, textValue) => {
      if (tagName) {
        return `<span class="code-punctuation">${tagOpen}</span><span class="code-tag">${tagName}</span><span class="code-punctuation">${tagClose}</span>`
      }
      if (textValue) return `<span class="code-string">${textValue}</span>`
      return token
    }
  )
}

function highlightYamlCode(value: string) {
  return value
    .split('\n')
    .map((line) => {
      const match = /^(\s*)([A-Za-z0-9_.-]+)(:)(.*)$/.exec(line)
      if (!match) return escapeHtml(line)

      const [, indent, key, colon, rawRest] = match
      const rest = escapeHtml(rawRest).replace(
        /(\$\{[^}]+\})|\b(true|false)\b|\b(https?:\/\/[^\s<}]+)\b/g,
        (token, envToken, booleanToken, urlToken) => {
          if (envToken) return `<span class="code-env">${envToken}</span>`
          if (booleanToken) return `<span class="code-boolean">${booleanToken}</span>`
          if (urlToken) return `<span class="code-url">${urlToken}</span>`
          return token
        }
      )

      return `${escapeHtml(indent)}<span class="code-key">${key}</span><span class="code-punctuation">${colon}</span>${rest}`
    })
    .join('\n')
}

function highlightJsCode(value: string) {
  const escaped = escapeHtml(value)
  const tokenPattern = /(\/\/[^\n]*|'[^'\n]*'|"[^"\n]*")|\b(import|from|const|async|await|return|if|throw|new)\b|\b(createEafChat|URLSearchParams|fetch|Error)\b|([A-Za-z_$][\w$]*)(?=\s*:)|([A-Za-z_$][\w$]*)(?=\()|(\|\||=>|[{}()[\],.:?])/g

  return escaped.replace(
    tokenPattern,
    (token, literalToken, keywordToken, functionToken, propertyToken, callToken, punctuationToken) => {
      if (literalToken?.startsWith('//')) return `<span class="code-comment">${literalToken}</span>`
      if (literalToken) return `<span class="code-string">${literalToken}</span>`
      if (keywordToken) return `<span class="code-keyword">${keywordToken}</span>`
      if (functionToken || callToken) return `<span class="code-function">${functionToken || callToken}</span>`
      if (propertyToken) return `<span class="code-property">${propertyToken}</span>`
      if (punctuationToken) return `<span class="code-punctuation">${punctuationToken}</span>`
      return token
    }
  )
}

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    const { data: projects } = await getScanProjects()
    const matched = projects.find((item) => item.projectCode === projectCode.value)
    if (!matched?.id) {
      project.value = null
      return
    }
    const [{ data: detail }, { data: instanceRows }, { data: assetPage }] = await Promise.all([
      getScanProjectDetail(matched.id),
      listRegistryProjectInstances(matched.projectCode || projectCode.value),
      listApiAssets({ projectId: matched.id, page: 1, pageSize: 100, enabled: true }),
    ])
    project.value = detail
    instances.value = instanceRows
    apiAssets.value = assetPage.items || []
    await loadAccessSession(detail.id)
    if (!selectedApiAssetId.value) {
      selectedApiAssetId.value = callableApiAssets.value[0]?.apiId || apiAssets.value[0]?.apiId || null
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '加载 SDK 接入向导失败')
  } finally {
    loading.value = false
  }
}

async function openAiOnboardingPrompt() {
  const p = project.value
  if (!p?.id) {
    ElMessage.warning('请先加载 SDK 接入项目')
    return
  }
  aiPromptLoading.value = true
  try {
    await ensureAccessSession(p.id)
    const { data } = await getAiOnboardingManifest(p.id)
    aiOnboardingManifest.value = data
    aiCodingAccessEnabled.value = data.aiCodingAccess?.enabled ?? false
    aiCodingAccessKey.value = data.aiCodingAccess?.accessKey || ''
    aiPromptDialogVisible.value = true
  } catch (error) {
    ElMessage.error((error as Error).message || '加载 AI 快速接入提示词失败')
  } finally {
    aiPromptLoading.value = false
  }
}

async function loadAccessSession(projectId: number) {
  try {
    const { data } = await getLatestAiAccessSession(projectId)
    accessSession.value = data
  } catch {
    accessSession.value = null
  }
}

async function ensureAccessSession(projectId: number) {
  if (accessSession.value?.projectId === projectId) return accessSession.value
  const { data } = await startAiAccessSession(projectId, aiPromptTool.value)
  accessSession.value = data
  return data
}

async function saveAiCodingAccess() {
  const p = project.value
  if (!p?.id) return
  aiCodingAccessSaving.value = true
  try {
    const { data } = await updateAiCodingAccess(p.id, {
      enabled: aiCodingAccessEnabled.value,
      accessKey: aiCodingAccessKey.value.trim() || null,
    })
    aiCodingAccessEnabled.value = data.enabled
    aiCodingAccessKey.value = data.accessKey || ''
    if (aiOnboardingManifest.value) {
      aiOnboardingManifest.value.aiCodingAccess = {
        enabled: data.enabled,
        accessKey: data.accessKey || null,
      }
    }
    ElMessage.success(data.enabled ? 'AI Coding 接入秘钥已保存' : 'AI Coding 接入已关闭')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存 AI Coding 接入秘钥失败')
  } finally {
    aiCodingAccessSaving.value = false
  }
}

async function clearAiCodingAccess() {
  aiCodingAccessEnabled.value = false
  aiCodingAccessKey.value = ''
  await saveAiCodingAccess()
}

async function runCheck() {
  const p = project.value
  if (!p?.id) return
  let args: Record<string, unknown>
  try {
    const parsed = JSON.parse(argsText.value || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('参数 JSON 必须是对象')
    args = parsed as Record<string, unknown>
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '参数 JSON 格式不正确')
    return
  }
  checking.value = true
  try {
    const payload = {
      apiAssetId: selectedApiAssetId.value,
      args,
      gatewayBaseUrl: gatewayBaseUrl.value,
      embedTokenPath: embedTokenPath.value,
    }
    const session = await ensureAccessSession(p.id)
    const { data } = await runAiAccessSessionChecks(p.id, session.sessionId, payload)
    checkResult.value = data.checkResult
    if (data.session) accessSession.value = data.session
    ElMessage[data.checkResult.overallStatus === 'PASS' ? 'success' : 'warning']('SDK 接入自检已完成')
  } catch (error) {
    ElMessage.error((error as Error).message || 'SDK 接入自检失败')
  } finally {
    checking.value = false
  }
}

function goBack() {
  router.push({ name: 'RegistryProjectDetail', params: { projectCode: projectCode.value } })
}

function goPrev() {
  const nextIndex = Math.max(activeStepIndex.value - 1, 0)
  activeStep.value = steps.value[nextIndex].key
}

function goNext() {
  const nextIndex = Math.min(activeStepIndex.value + 1, steps.value.length - 1)
  activeStep.value = steps.value[nextIndex].key
}

function assetLabel(asset: ApiAssetItem) {
  const method = asset.httpMethod || 'API'
  const path = asset.endpointPath || asset.sourceLocation || asset.name
  return `${method} ${path}`
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

function statusLabel(status: SdkAccessCheckStatus) {
  if (status === 'PASS') return '通过'
  if (status === 'WARN') return '需确认'
  return '失败'
}

function accessStatusLabel(status: AiAccessStepStatus | 'OPEN') {
  if (status === 'PASS') return '完成'
  if (status === 'WARN') return '需确认'
  if (status === 'FAIL') return '失败'
  if (status === 'RUNNING') return '进行中'
  if (status === 'SKIPPED') return '跳过'
  return '待处理'
}
</script>

<style scoped lang="scss">
.sdk-access-page {
  min-height: 100%;
  padding: 24px;
  background: #f5f7fb;
}

.page-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;

  h1 {
    margin: 8px 0 6px;
    color: #101828;
    font-size: 28px;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: #667085;
    font-size: 13px;
  }
}

.header-actions {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.page-alert {
  margin-bottom: 16px;
}

.wizard-shell {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.step-progress {
  display: grid;
  gap: 0;
  position: sticky;
  top: 16px;
  overflow: hidden;
  border: 1px solid #e4e9f2;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.04);
}

.progress-step {
  min-height: 94px;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 18px;
  gap: 12px;
  align-items: center;
  padding: 16px 16px 16px 15px;
  border: 0;
  border-left: 2px solid transparent;
  border-bottom: 1px solid #eef2f7;
  border-radius: 0;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: background 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;

  &:last-child {
    border-bottom: 0;
  }

  &:hover {
    background: #f8fbff;
  }

  &.active {
    border-left-color: #3b63ff;
    background: linear-gradient(90deg, #f3f7ff 0%, #ffffff 100%);
    box-shadow: inset 12px 0 24px rgba(59, 99, 255, 0.08);

    .step-index {
      background: #e8efff;
      color: #3155db;
    }

    strong {
      color: #0f172a;
    }
  }

  &.done .step-index {
    background: #dcfce7;
    color: #15803d;
  }

  strong {
    display: block;
    color: #101828;
    font-size: 14px;
  }

  small {
    color: #667085;
    font-size: 12px;
    line-height: 1.4;
  }
}

.step-index {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #f1f5f9;
  color: #64748b;
  font-weight: 750;
}

.step-copy {
  min-width: 0;
}

.step-caret {
  color: #3155db;
  font-size: 15px;
}

.stage-shell {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #e1e7f0;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.05);
}

.focus-panel {
  min-height: 640px;
  padding: 28px;
  border: 0;
  border-radius: 0;
  background: #fff;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;

  h2 {
    margin: 5px 0 0;
    color: #101828;
    font-size: 28px;
    line-height: 1.25;
  }

  p {
    margin: 10px 0 0;
    color: #667085;
    font-size: 14px;
    line-height: 1.6;
  }
}

.step-kicker {
  color: #2563eb;
  font-size: 12px;
  font-weight: 750;
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.health-card {
  min-height: 116px;
  padding: 16px;
  border: 1px solid #e4e9f2;
  border-radius: 8px;
  background: #fbfcff;

  &.good {
    border-color: #abefc6;
    background: #f0fdf4;
  }

  &.warn {
    border-color: #fedf89;
    background: #fffbeb;
  }

  strong {
    display: block;
    margin: 8px 0 6px;
    color: #101828;
    font-size: 24px;
    line-height: 1.1;
  }

  small,
  .health-label {
    color: #667085;
    font-size: 12px;
  }
}

.inline-form {
  max-width: 760px;
}

.config-section {
  margin-top: 26px;

  h3 {
    margin: 0 0 8px;
    color: #101828;
    font-size: 16px;
    line-height: 1.4;
    font-weight: 750;
  }

  p {
    margin: 0 0 12px;
    color: #667085;
    font-size: 14px;
    line-height: 1.6;
  }
}

.config-check {
  margin-top: 24px;
}

.code-shell {
  overflow: hidden;
  border: 1px solid #111827;
  border-radius: 8px;
  background: #07111f;
}

.code-toolbar {
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  background: #0b1524;
  color: #d9e2ef;
  font-size: 13px;
  font-weight: 700;

  :deep(.el-button) {
    color: #d9e2ef;
  }
}

.code-panel {
  max-height: 260px;
  overflow: auto;
  margin: 0;
  padding: 18px 20px;
  border-radius: 0;
  background: #07111f;
  color: #e5e7eb;
  font-family: Consolas, "JetBrains Mono", monospace;
  font-size: 13px;
  line-height: 1.6;

  code {
    display: block;
    color: #dbeafe;
  }

  :deep(.code-tag),
  :deep(.code-key) {
    color: #7dd3fc;
    font-weight: 650;
  }

  :deep(.code-string) {
    color: #fcd34d;
  }

  :deep(.code-keyword) {
    color: #c4b5fd;
    font-weight: 650;
  }

  :deep(.code-function) {
    color: #67e8f9;
    font-weight: 650;
  }

  :deep(.code-property) {
    color: #93c5fd;
  }

  :deep(.code-comment) {
    color: #64748b;
    font-style: italic;
  }

  :deep(.code-env) {
    color: #f0abfc;
  }

  :deep(.code-boolean) {
    color: #86efac;
    font-weight: 650;
  }

  :deep(.code-url) {
    color: #93c5fd;
  }

  :deep(.code-punctuation) {
    color: #94a3b8;
  }
}

.check-list,
.result-list {
  display: grid;
  gap: 10px;
}

.check-row,
.result-row {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  padding: 13px;
  border: 1px solid #e4e9f2;
  border-radius: 8px;
  background: #fbfcff;

  &.pass {
    border-color: #abefc6;
    background: #f0fdf4;
  }

  &.warn {
    border-color: #fedf89;
    background: #fffbeb;
  }

  &.fail {
    border-color: #fecaca;
    background: #fef2f2;
  }

  strong {
    display: block;
    color: #101828;
    font-size: 14px;
  }

  small,
  em {
    display: block;
    margin-top: 3px;
    color: #667085;
    font-size: 12px;
    font-style: normal;
  }
}

.check-result {
  margin-top: 18px;
}

.result-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 14px 16px;
  border-radius: 8px;
  background: #eef4ff;
  color: #1d4ed8;

  &.pass {
    background: #ecfdf3;
    color: #067647;
  }

  &.warn {
    background: #fffaeb;
    color: #b54708;
  }

  &.fail {
    background: #fef2f2;
    color: #b42318;
  }
}

.result-status {
  width: 54px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.7);
  font-size: 12px;
  font-weight: 750;
}

.wizard-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 0;
  padding: 18px 28px;
  border-top: 1px solid #e4e9f2;
  background: #fff;
}

.footer-actions {
  display: inline-flex;
  gap: 10px;
}

@media (max-width: 980px) {
  .wizard-shell,
  .health-grid {
    grid-template-columns: 1fr;
  }

  .step-progress {
    position: static;
  }
}

/* Dark glassmorphism skin for the SDK access wizard. */
:global(.main-layout.registry-shell .main-content:has(.sdk-access-page)) {
  background:
    radial-gradient(circle at 18% 8%, rgba(68, 109, 255, 0.2), transparent 30%),
    radial-gradient(circle at 86% 18%, rgba(136, 92, 246, 0.16), transparent 28%),
    linear-gradient(135deg, #030712 0%, #07111f 48%, #020617 100%);
}

:global(.main-layout.registry-shell .main-content:has(.sdk-access-page)::before) {
  opacity: 0;
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .topbar) {
  border-bottom-color: rgba(148, 163, 184, 0.16);
  background: rgba(4, 10, 24, 0.72);
  box-shadow: 0 14px 34px rgba(0, 0, 0, 0.24);
  backdrop-filter: blur(18px);
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .breadcrumb-area) {
  border-bottom-color: rgba(148, 163, 184, 0.16);
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__separator) {
  color: rgba(226, 232, 240, 0.72);
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #f8fafc;
}

.sdk-access-page {
  position: relative;
  isolation: isolate;
  min-height: calc(100vh - 72px);
  padding: 28px 28px 36px;
  overflow: hidden;
  background:
    linear-gradient(rgba(125, 211, 252, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(125, 211, 252, 0.04) 1px, transparent 1px),
    radial-gradient(circle at 20% 10%, rgba(37, 99, 235, 0.24), transparent 32%),
    radial-gradient(circle at 85% 20%, rgb(var(--brand-hover-rgb) / 0.16), transparent 28%),
    linear-gradient(135deg, #030712 0%, #07101f 54%, #020617 100%);
  background-size: 48px 48px, 48px 48px, auto, auto, auto;
  color: #e5eefc;
}

.sdk-access-page::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -2;
  background:
    linear-gradient(115deg, transparent 0%, rgba(56, 189, 248, 0.07) 34%, transparent 56%),
    linear-gradient(72deg, transparent 18%, rgb(var(--brand-primary-rgb) / 0.08) 44%, transparent 70%);
  mask-image: linear-gradient(to bottom, #000 0%, transparent 76%);
  pointer-events: none;
}

.sdk-access-page::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  background: radial-gradient(circle at 50% 0%, rgba(255, 255, 255, 0.08), transparent 42%);
  opacity: 0.75;
  pointer-events: none;
}

.page-header {
  position: relative;
  z-index: 1;
  align-items: flex-start;
  margin-bottom: 22px;
  padding: 18px 20px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(15, 23, 42, 0.72), rgba(15, 23, 42, 0.42));
  box-shadow: 0 22px 58px rgba(0, 0, 0, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(18px);

  h1 {
    color: #f8fafc;
    text-shadow: 0 0 28px rgba(96, 165, 250, 0.28);
  }

  p {
    color: rgba(203, 213, 225, 0.78);
  }

  :deep(.el-button.is-link) {
    color: rgba(203, 213, 225, 0.86);
  }

  :deep(.el-button.is-link:hover) {
    color: #93c5fd;
  }
}

.header-actions {
  :deep(.el-tag) {
    border-color: rgba(129, 140, 248, 0.34);
    background: rgb(var(--brand-primary-rgb) / 0.14);
    color: var(--brand-selected-bg);
    box-shadow: 0 0 24px rgb(var(--brand-primary-rgb) / 0.18);
  }

  :deep(.el-button) {
    border-color: rgba(148, 163, 184, 0.26);
    background: rgba(15, 23, 42, 0.58);
    color: #dbeafe;
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
    backdrop-filter: blur(14px);
  }

  :deep(.el-button:hover) {
    border-color: rgba(96, 165, 250, 0.54);
    color: #eff6ff;
    box-shadow: 0 0 26px rgba(59, 130, 246, 0.18);
  }
}

.wizard-shell {
  position: relative;
  z-index: 1;
}

.step-progress,
.stage-shell {
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: linear-gradient(145deg, rgba(15, 23, 42, 0.7), rgba(15, 23, 42, 0.36));
  box-shadow:
    0 26px 70px rgba(0, 0, 0, 0.36),
    0 0 0 1px rgba(96, 165, 250, 0.05),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(20px);
}

.step-progress {
  overflow: hidden;
}

.progress-step {
  border-bottom-color: rgba(148, 163, 184, 0.12);
  background: rgba(15, 23, 42, 0.14);
  color: #dbeafe;

  &:hover {
    background: rgba(30, 41, 59, 0.46);
  }

  &.active {
    border-left-color: #60a5fa;
    background:
      linear-gradient(90deg, rgba(37, 99, 235, 0.26) 0%, rgba(15, 23, 42, 0.42) 58%, rgba(15, 23, 42, 0.16) 100%);
    box-shadow: inset 16px 0 34px rgba(59, 130, 246, 0.17), 0 0 34px rgba(59, 130, 246, 0.08);

    .step-index {
      border-color: rgba(147, 197, 253, 0.44);
      background: rgba(37, 99, 235, 0.18);
      color: #bfdbfe;
      box-shadow: 0 0 22px rgba(59, 130, 246, 0.24);
    }

    strong {
      color: #f8fafc;
    }
  }

  &.done .step-index {
    border-color: rgba(74, 222, 128, 0.34);
    background: rgba(22, 163, 74, 0.16);
    color: #86efac;
    box-shadow: 0 0 18px rgba(34, 197, 94, 0.18);
  }

  strong {
    color: rgba(241, 245, 249, 0.94);
  }

  small {
    color: rgba(203, 213, 225, 0.68);
  }
}

.step-index {
  border: 1px solid rgba(148, 163, 184, 0.16);
  background: rgba(30, 41, 59, 0.68);
  color: #cbd5e1;
}

.step-caret {
  color: #93c5fd;
  filter: drop-shadow(0 0 10px rgba(96, 165, 250, 0.6));
}

.stage-shell {
  min-height: 690px;
}

.focus-panel {
  min-height: 620px;
  background: transparent;
}

.panel-head {
  h2 {
    color: #f8fafc;
    text-shadow: 0 0 22px rgba(59, 130, 246, 0.22);
  }

  p {
    color: rgba(203, 213, 225, 0.74);
  }

  :deep(.el-tag) {
    border-color: rgba(125, 211, 252, 0.28);
    background: rgba(14, 165, 233, 0.12);
    color: #bae6fd;
  }

  :deep(.el-button) {
    border-color: rgba(148, 163, 184, 0.26);
    background: rgba(15, 23, 42, 0.5);
    color: #dbeafe;
    backdrop-filter: blur(12px);
  }
}

.step-kicker {
  color: #7dd3fc;
  text-shadow: 0 0 18px rgba(125, 211, 252, 0.32);
}

.health-card,
.check-row,
.result-row {
  border-color: rgba(148, 163, 184, 0.18);
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.08), rgba(148, 163, 184, 0.04));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08), 0 18px 34px rgba(0, 0, 0, 0.16);
  backdrop-filter: blur(14px);

  &.good,
  &.pass {
    border-color: rgba(74, 222, 128, 0.26);
    background: linear-gradient(145deg, rgba(22, 163, 74, 0.14), rgba(15, 23, 42, 0.18));
  }

  &.warn {
    border-color: rgba(251, 191, 36, 0.28);
    background: linear-gradient(145deg, rgba(180, 83, 9, 0.14), rgba(15, 23, 42, 0.18));
  }

  &.fail {
    border-color: rgba(248, 113, 113, 0.28);
    background: linear-gradient(145deg, rgba(185, 28, 28, 0.14), rgba(15, 23, 42, 0.18));
  }

  strong {
    color: #f8fafc;
  }

  small,
  em,
  .health-label {
    color: rgba(203, 213, 225, 0.68);
  }
}

.health-card {
  min-height: 126px;

  strong {
    color: #f8fafc;
    white-space: nowrap;
  }
}

.config-section {
  h3 {
    color: #f8fafc;
  }

  p {
    color: rgba(203, 213, 225, 0.72);
  }
}

.inline-form {
  :deep(.el-form-item__label) {
    color: rgba(203, 213, 225, 0.78);
  }

  :deep(.el-input__wrapper),
  :deep(.el-textarea__inner),
  :deep(.el-select__wrapper) {
    border: 1px solid rgba(148, 163, 184, 0.22);
    background: rgba(15, 23, 42, 0.52);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
    color: #e2e8f0;
    backdrop-filter: blur(12px);
  }

  :deep(.el-input__inner),
  :deep(.el-textarea__inner) {
    color: #e2e8f0;
  }
}

.code-shell,
.code-panel {
  border-color: rgba(96, 165, 250, 0.2);
  background: rgba(2, 6, 23, 0.72);
  box-shadow: 0 0 0 1px rgba(125, 211, 252, 0.05), 0 20px 52px rgba(0, 0, 0, 0.28);
}

.code-toolbar {
  border-bottom-color: rgba(148, 163, 184, 0.16);
  background: rgba(15, 23, 42, 0.76);
  color: #dbeafe;
}

.code-panel {
  background:
    linear-gradient(90deg, rgba(59, 130, 246, 0.05), transparent 16%),
    rgba(2, 6, 23, 0.78);
}

.check-row,
.result-row {
  :deep(.el-icon) {
    color: #93c5fd;
  }
}

.result-head {
  border: 1px solid rgba(96, 165, 250, 0.24);
  background: rgba(37, 99, 235, 0.14);
  color: #bfdbfe;

  &.pass {
    border-color: rgba(74, 222, 128, 0.28);
    background: rgba(22, 163, 74, 0.14);
    color: #bbf7d0;
  }

  &.warn {
    border-color: rgba(251, 191, 36, 0.3);
    background: rgba(180, 83, 9, 0.14);
    color: #fde68a;
  }

  &.fail {
    border-color: rgba(248, 113, 113, 0.3);
    background: rgba(185, 28, 28, 0.14);
    color: #fecaca;
  }
}

.result-status {
  background: rgba(255, 255, 255, 0.08);
}

.wizard-footer {
  border-top-color: rgba(148, 163, 184, 0.16);
  background: rgba(15, 23, 42, 0.46);
  backdrop-filter: blur(18px);
}

.wizard-footer,
.step-screen {
  :deep(.el-checkbox__label) {
    color: rgba(226, 232, 240, 0.82);
  }

  :deep(.el-checkbox__inner) {
    border-color: rgba(148, 163, 184, 0.34);
    background: rgba(15, 23, 42, 0.58);
  }

  :deep(.el-button) {
    border-color: rgba(148, 163, 184, 0.26);
    background: rgba(15, 23, 42, 0.58);
    color: #dbeafe;
  }

  :deep(.el-button--primary) {
    border-color: rgba(96, 165, 250, 0.58);
    background: linear-gradient(135deg, rgba(37, 99, 235, 0.9), rgb(var(--brand-active-rgb) / 0.78));
    box-shadow: 0 0 26px rgba(59, 130, 246, 0.24);
  }

  :deep(.el-button:not(.is-disabled):hover) {
    border-color: rgba(125, 211, 252, 0.58);
    color: #eff6ff;
    box-shadow: 0 0 26px rgba(59, 130, 246, 0.18);
  }
}

.page-alert {
  :deep(.el-alert) {
    border: 1px solid rgba(251, 191, 36, 0.28);
    background: rgba(120, 53, 15, 0.22);
    color: #fde68a;
    backdrop-filter: blur(12px);
  }
}

/* Daytime glassmorphism skin. It intentionally keeps the same productized
   structure while restoring light-mode contrast and theme switching semantics. */
:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)) .main-content:has(.sdk-access-page)) {
  background:
    radial-gradient(circle at 14% 6%, rgba(59, 130, 246, 0.16), transparent 32%),
    radial-gradient(circle at 88% 16%, rgb(var(--brand-hover-rgb) / 0.1), transparent 30%),
    linear-gradient(135deg, #edf5ff 0%, #f8fbff 52%, #edf2ff 100%);
}

:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)) .main-content:has(.sdk-access-page)::before) {
  opacity: 0;
}

:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)):has(.sdk-access-page) .topbar) {
  border-bottom-color: rgba(148, 163, 184, 0.24);
  background: rgba(248, 251, 255, 0.82);
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(18px);
}

:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)):has(.sdk-access-page) .breadcrumb-area) {
  border-bottom-color: rgba(148, 163, 184, 0.18);
}

:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)):has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)):has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__separator) {
  color: rgba(71, 85, 105, 0.78);
}

:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)):has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #0f172a;
}

:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)):has(.sdk-access-page) .topbar-btn) {
  border-color: rgba(148, 163, 184, 0.34);
  background: rgba(255, 255, 255, 0.72);
  color: #475569;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.08);
}

:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)):has(.sdk-access-page) .user-avatar) {
  box-shadow: 0 10px 24px rgb(var(--brand-primary-rgb) / 0.22);
}

:global(.main-layout.registry-shell:not(.is-dark):not(:has(.sdk-access-page.is-light-skin)):has(.sdk-access-page)) {
  .sdk-access-page {
    background:
      linear-gradient(rgba(59, 130, 246, 0.06) 1px, transparent 1px),
      linear-gradient(90deg, rgba(59, 130, 246, 0.06) 1px, transparent 1px),
      radial-gradient(circle at 18% 10%, rgba(59, 130, 246, 0.18), transparent 30%),
      radial-gradient(circle at 82% 18%, rgb(var(--brand-hover-rgb) / 0.11), transparent 28%),
      linear-gradient(135deg, #eef6ff 0%, #f8fbff 54%, #eef2ff 100%);
    color: #0f172a;
  }

  .sdk-access-page::before {
    background:
      linear-gradient(115deg, transparent 0%, rgba(14, 165, 233, 0.09) 34%, transparent 58%),
      linear-gradient(72deg, transparent 16%, rgb(var(--brand-primary-rgb) / 0.08) 46%, transparent 72%);
    opacity: 0.72;
  }

  .sdk-access-page::after {
    background: radial-gradient(circle at 50% 0%, rgba(255, 255, 255, 0.76), transparent 44%);
    opacity: 0.86;
  }

  .page-header {
    border-color: rgba(96, 165, 250, 0.22);
    background: linear-gradient(135deg, rgba(255, 255, 255, 0.74), rgba(219, 234, 254, 0.44));
    box-shadow: 0 22px 58px rgba(15, 23, 42, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.72);

    h1 {
      color: #0f172a;
      text-shadow: none;
    }

    p {
      color: #475569;
    }

    :deep(.el-button.is-link) {
      color: #475569;
    }

    :deep(.el-button.is-link:hover) {
      color: #2563eb;
    }
  }

  .header-actions {
    :deep(.el-tag) {
      border-color: rgb(var(--brand-primary-rgb) / 0.2);
      background: rgba(238, 242, 255, 0.78);
      color: var(--brand-active);
      box-shadow: 0 10px 24px rgb(var(--brand-primary-rgb) / 0.12);
    }

    :deep(.el-button) {
      border-color: rgba(148, 163, 184, 0.34);
      background: rgba(255, 255, 255, 0.74);
      color: #334155;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 12px 26px rgba(15, 23, 42, 0.08);
    }

    :deep(.el-button:hover) {
      border-color: rgba(37, 99, 235, 0.36);
      color: #1d4ed8;
      box-shadow: 0 0 26px rgba(59, 130, 246, 0.16);
    }
  }

  .step-progress,
  .stage-shell {
    border-color: rgba(96, 165, 250, 0.2);
    background: linear-gradient(145deg, rgba(255, 255, 255, 0.76), rgba(226, 236, 251, 0.46));
    box-shadow:
      0 26px 70px rgba(15, 23, 42, 0.11),
      0 0 0 1px rgba(96, 165, 250, 0.06),
      inset 0 1px 0 rgba(255, 255, 255, 0.76);
  }

  .progress-step {
    border-bottom-color: rgba(148, 163, 184, 0.16);
    background: rgba(255, 255, 255, 0.22);
    color: #334155;

    &:hover {
      background: rgba(239, 246, 255, 0.72);
    }

    &.active {
      border-left-color: #2563eb;
      background:
        linear-gradient(90deg, rgba(59, 130, 246, 0.16) 0%, rgba(255, 255, 255, 0.66) 58%, rgba(255, 255, 255, 0.32) 100%);
      box-shadow: inset 16px 0 34px rgba(37, 99, 235, 0.12), 0 0 34px rgba(59, 130, 246, 0.08);

      .step-index {
        border-color: rgba(37, 99, 235, 0.28);
        background: rgba(219, 234, 254, 0.84);
        color: #1d4ed8;
        box-shadow: 0 0 22px rgba(59, 130, 246, 0.18);
      }

      strong {
        color: #0f172a;
      }
    }

    &.done .step-index {
      border-color: rgba(22, 163, 74, 0.28);
      background: rgba(220, 252, 231, 0.88);
      color: #15803d;
      box-shadow: 0 0 18px rgba(34, 197, 94, 0.14);
    }

    strong {
      color: #0f172a;
    }

    small {
      color: #475569;
    }
  }

  .step-index {
    border-color: rgba(148, 163, 184, 0.2);
    background: rgba(241, 245, 249, 0.88);
    color: #475569;
  }

  .step-caret {
    color: #2563eb;
    filter: drop-shadow(0 0 9px rgba(37, 99, 235, 0.28));
  }

  .panel-head {
    h2 {
      color: #0f172a;
      text-shadow: none;
    }

    p {
      color: #475569;
    }

    :deep(.el-tag) {
      border-color: rgba(14, 165, 233, 0.22);
      background: rgba(224, 242, 254, 0.72);
      color: #0369a1;
    }

    :deep(.el-button) {
      border-color: rgba(148, 163, 184, 0.34);
      background: rgba(255, 255, 255, 0.76);
      color: #334155;
    }
  }

  .step-kicker {
    color: #2563eb;
    text-shadow: none;
  }

  .health-card,
  .check-row,
  .result-row {
    border-color: rgba(96, 165, 250, 0.18);
    background: linear-gradient(145deg, rgba(255, 255, 255, 0.74), rgba(226, 236, 251, 0.42));
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 18px 34px rgba(15, 23, 42, 0.08);

    &.good,
    &.pass {
      border-color: rgba(22, 163, 74, 0.22);
      background: linear-gradient(145deg, rgba(240, 253, 244, 0.78), rgba(235, 248, 255, 0.44));
    }

    &.warn {
      border-color: rgba(245, 158, 11, 0.26);
      background: linear-gradient(145deg, rgba(255, 251, 235, 0.82), rgba(255, 247, 237, 0.46));
    }

    &.fail {
      border-color: rgba(239, 68, 68, 0.24);
      background: linear-gradient(145deg, rgba(254, 242, 242, 0.82), rgba(255, 247, 247, 0.48));
    }

    strong {
      color: #0f172a;
    }

    small,
    em,
    .health-label {
      color: #475569;
    }
  }

  .health-card:not(.good):not(.warn) {
    border-color: rgba(100, 116, 139, 0.2);
    background: linear-gradient(145deg, rgba(248, 250, 252, 0.78), rgba(226, 232, 240, 0.42));
  }

  .config-section {
    h3 {
      color: #0f172a;
    }

    p {
      color: #475569;
    }
  }

  .inline-form {
    :deep(.el-form-item__label) {
      color: #475569;
    }

    :deep(.el-input__wrapper),
    :deep(.el-textarea__inner),
    :deep(.el-select__wrapper) {
      border-color: rgba(148, 163, 184, 0.32);
      background: rgba(255, 255, 255, 0.76);
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 10px 24px rgba(15, 23, 42, 0.06);
      color: #0f172a;
    }

    :deep(.el-input__inner),
    :deep(.el-textarea__inner) {
      color: #0f172a;
    }
  }

  .check-row,
  .result-row {
    :deep(.el-icon) {
      color: #2563eb;
    }
  }

  .result-head {
    border-color: rgba(37, 99, 235, 0.2);
    background: rgba(219, 234, 254, 0.62);
    color: #1d4ed8;

    &.pass {
      border-color: rgba(22, 163, 74, 0.24);
      background: rgba(220, 252, 231, 0.68);
      color: #15803d;
    }

    &.warn {
      border-color: rgba(245, 158, 11, 0.28);
      background: rgba(254, 243, 199, 0.72);
      color: #b45309;
    }

    &.fail {
      border-color: rgba(239, 68, 68, 0.26);
      background: rgba(254, 226, 226, 0.7);
      color: #b91c1c;
    }
  }

  .result-status {
    background: rgba(255, 255, 255, 0.64);
  }

  .wizard-footer {
    border-top-color: rgba(148, 163, 184, 0.18);
    background: rgba(248, 250, 252, 0.58);
  }

  .wizard-footer,
  .step-screen {
    :deep(.el-checkbox__label) {
      color: #334155;
    }

    :deep(.el-checkbox__inner) {
      border-color: rgba(148, 163, 184, 0.38);
      background: rgba(255, 255, 255, 0.78);
    }

    :deep(.el-button) {
      border-color: rgba(148, 163, 184, 0.34);
      background: rgba(255, 255, 255, 0.74);
      color: #334155;
    }

    :deep(.el-button--primary) {
      border-color: rgba(37, 99, 235, 0.44);
      background: linear-gradient(135deg, rgba(37, 99, 235, 0.92), rgb(var(--brand-active-rgb) / 0.82));
      color: #fff;
      box-shadow: 0 0 26px rgba(59, 130, 246, 0.2);
    }

    :deep(.el-button:not(.is-disabled):hover) {
      border-color: rgba(37, 99, 235, 0.4);
      color: #1d4ed8;
      box-shadow: 0 0 24px rgba(59, 130, 246, 0.16);
    }

    :deep(.el-button--primary:not(.is-disabled):hover) {
      color: #fff;
    }
  }

  .page-alert {
    :deep(.el-alert) {
      border-color: rgba(245, 158, 11, 0.26);
      background: rgba(255, 251, 235, 0.82);
      color: #92400e;
    }
  }
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .main-content) {
  background: #f5f7fb !important;
  color: #0f172a !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .main-content::before) {
  opacity: 0 !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .topbar) {
  border-bottom-color: rgba(148, 163, 184, 0.24) !important;
  background: rgba(255, 255, 255, 0.94) !important;
  color: #0f172a !important;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.04) !important;
  backdrop-filter: none !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .breadcrumb-area .el-breadcrumb__separator) {
  color: rgba(71, 85, 105, 0.78) !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #0f172a !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .topbar-btn) {
  border-color: rgba(148, 163, 184, 0.34) !important;
  background: rgba(255, 255, 255, 0.72) !important;
  color: #475569 !important;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.08) !important;
}

.sdk-access-page.is-light-skin {
  background: transparent;
  color: #0f172a;
}

.sdk-access-page.is-light-skin::before {
  display: none;
}

.sdk-access-page.is-light-skin::after {
  display: none;
}

.sdk-access-page.is-light-skin {
  .page-header {
    border-color: #e1e7f0;
    background: #fff;
    box-shadow: 0 12px 32px rgba(15, 23, 42, 0.045);
    backdrop-filter: none;

    h1 {
      color: #0f172a;
      text-shadow: none;
    }

    p {
      color: #475569;
    }

    :deep(.el-button.is-link) {
      color: #475569;
    }
  }

  .header-actions {
    :deep(.el-tag) {
      border-color: rgb(var(--brand-primary-rgb) / 0.2);
      background: rgba(238, 242, 255, 0.78);
      color: var(--brand-active);
      box-shadow: 0 10px 24px rgb(var(--brand-primary-rgb) / 0.12);
    }

    :deep(.el-button) {
      border-color: rgba(148, 163, 184, 0.34);
      background: rgba(255, 255, 255, 0.74);
      color: #334155;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 12px 26px rgba(15, 23, 42, 0.08);
    }
  }

  .step-progress,
  .stage-shell {
    border-color: #e1e7f0;
    background: #fff;
    box-shadow: 0 14px 36px rgba(15, 23, 42, 0.055);
    backdrop-filter: none;
  }

  .progress-step {
    border-bottom-color: rgba(148, 163, 184, 0.16);
    background: #fff;
    color: #334155;

    &:hover {
      background: rgba(239, 246, 255, 0.72);
    }

    &.active {
      border-left-color: #2563eb;
      background:
        linear-gradient(90deg, rgba(59, 130, 246, 0.16) 0%, rgba(255, 255, 255, 0.66) 58%, rgba(255, 255, 255, 0.32) 100%);
      box-shadow: inset 16px 0 34px rgba(37, 99, 235, 0.12), 0 0 34px rgba(59, 130, 246, 0.08);

      .step-index {
        border-color: rgba(37, 99, 235, 0.28);
        background: rgba(219, 234, 254, 0.84);
        color: #1d4ed8;
        box-shadow: 0 0 22px rgba(59, 130, 246, 0.18);
      }

      strong {
        color: #0f172a;
      }
    }

    &.done .step-index {
      border-color: rgba(22, 163, 74, 0.28);
      background: rgba(220, 252, 231, 0.88);
      color: #15803d;
      box-shadow: 0 0 18px rgba(34, 197, 94, 0.14);
    }

    strong {
      color: #0f172a;
    }

    small {
      color: #475569;
    }
  }

  .step-index {
    border-color: rgba(148, 163, 184, 0.2);
    background: rgba(241, 245, 249, 0.88);
    color: #475569;
  }

  .step-caret {
    color: #2563eb;
    filter: drop-shadow(0 0 9px rgba(37, 99, 235, 0.28));
  }

  .panel-head {
    h2 {
      color: #0f172a;
      text-shadow: none;
    }

    p {
      color: #475569;
    }

    :deep(.el-tag) {
      border-color: rgba(14, 165, 233, 0.22);
      background: rgba(224, 242, 254, 0.72);
      color: #0369a1;
    }

    :deep(.el-button) {
      border-color: rgba(148, 163, 184, 0.34);
      background: rgba(255, 255, 255, 0.76);
      color: #334155;
    }
  }

  .step-kicker {
    color: #2563eb;
    text-shadow: none;
  }

  .health-card,
  .check-row,
  .result-row {
    border-color: #e4e9f2;
    background: #fff;
    box-shadow: 0 10px 24px rgba(15, 23, 42, 0.045);
    backdrop-filter: none;

    &.good,
    &.pass {
      border-color: rgba(22, 163, 74, 0.22);
      background: rgba(240, 253, 244, 0.92);
    }

    &.warn {
      border-color: rgba(245, 158, 11, 0.26);
      background: rgba(255, 251, 235, 0.94);
    }

    &.fail {
      border-color: rgba(239, 68, 68, 0.24);
      background: rgba(254, 242, 242, 0.94);
    }

    strong {
      color: #0f172a;
    }

    small,
    em,
    .health-label {
      color: #475569;
    }
  }

  .health-card:not(.good):not(.warn) {
    border-color: #e4e9f2;
    background: #fbfcff;
  }

  .config-section {
    h3 {
      color: #0f172a;
    }

    p {
      color: #475569;
    }
  }

  .inline-form {
    :deep(.el-form-item__label) {
      color: #475569;
    }

    :deep(.el-input__wrapper),
    :deep(.el-textarea__inner),
    :deep(.el-select__wrapper) {
      border-color: rgba(148, 163, 184, 0.32);
      background: rgba(255, 255, 255, 0.96);
      box-shadow: 0 8px 18px rgba(15, 23, 42, 0.04);
      color: #0f172a;
    }

    :deep(.el-input__inner),
    :deep(.el-textarea__inner) {
      color: #0f172a;
    }
  }

  .check-row,
  .result-row {
    :deep(.el-icon) {
      color: #2563eb;
    }
  }

  .result-head {
    border-color: rgba(37, 99, 235, 0.2);
    background: rgba(219, 234, 254, 0.62);
    color: #1d4ed8;

    &.pass {
      border-color: rgba(22, 163, 74, 0.24);
      background: rgba(220, 252, 231, 0.68);
      color: #15803d;
    }

    &.warn {
      border-color: rgba(245, 158, 11, 0.28);
      background: rgba(254, 243, 199, 0.72);
      color: #b45309;
    }

    &.fail {
      border-color: rgba(239, 68, 68, 0.26);
      background: rgba(254, 226, 226, 0.7);
      color: #b91c1c;
    }
  }

  .result-status {
    background: rgba(255, 255, 255, 0.64);
  }

  .wizard-footer {
    border-top-color: rgba(148, 163, 184, 0.18);
    background: rgba(248, 250, 252, 0.92);
  }

  .wizard-footer,
  .step-screen {
    :deep(.el-checkbox__label) {
      color: #334155;
    }

    :deep(.el-checkbox__inner) {
      border-color: rgba(148, 163, 184, 0.38);
      background: rgba(255, 255, 255, 0.78);
    }

    :deep(.el-button) {
      border-color: rgba(148, 163, 184, 0.34);
      background: rgba(255, 255, 255, 0.74);
      color: #334155;
    }

    :deep(.el-button--primary) {
      border-color: rgba(37, 99, 235, 0.44);
      background: linear-gradient(135deg, rgba(37, 99, 235, 0.92), rgb(var(--brand-active-rgb) / 0.82));
      color: #fff;
      box-shadow: 0 0 26px rgba(59, 130, 246, 0.2);
    }
  }

  .page-alert {
    :deep(.el-alert) {
      border-color: rgba(245, 158, 11, 0.26);
      background: rgba(255, 251, 235, 0.82);
      color: #92400e;
    }
  }
}

@media (max-width: 980px) {
  .sdk-access-page {
    padding: 18px;
  }

  .page-header,
  .wizard-footer {
    flex-direction: column;
    align-items: stretch;
  }

  .footer-actions {
    justify-content: flex-end;
  }
}

/* Final SDK access art direction: light enterprise glass, matching the approved mock. */
:global(.main-layout.registry-shell:has(.sdk-access-page) .main-content) {
  background:
    radial-gradient(circle at 82% 3%, rgba(191, 219, 254, 0.9), transparent 34%),
    radial-gradient(circle at 16% 20%, rgba(147, 197, 253, 0.34), transparent 36%),
    linear-gradient(135deg, #dae9fb 0%, #eef6ff 44%, #dbeafe 100%) !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .main-content::before) {
  opacity: 0 !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .topbar) {
  border-bottom-color: rgba(255, 255, 255, 0.45) !important;
  background:
    linear-gradient(90deg, rgba(53, 76, 118, 0.76), rgba(147, 197, 253, 0.42)) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.42), 0 14px 38px rgba(30, 64, 175, 0.16) !important;
  backdrop-filter: blur(22px) saturate(1.1) !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__separator) {
  color: rgba(255, 255, 255, 0.82) !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #ffffff !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page) .topbar-btn) {
  border-color: rgba(255, 255, 255, 0.62) !important;
  background: rgba(255, 255, 255, 0.42) !important;
  color: #1e3a8a !important;
  box-shadow: 0 10px 24px rgba(30, 64, 175, 0.12) !important;
  backdrop-filter: blur(14px) !important;
}

.sdk-access-page,
.sdk-access-page.is-light-skin,
.sdk-access-page.is-dark-skin {
  position: relative;
  min-height: calc(100vh - 72px);
  padding: 14px 24px 36px;
  overflow: hidden;
  background:
    linear-gradient(rgba(255, 255, 255, 0.16) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.14) 1px, transparent 1px),
    radial-gradient(circle at 18% 16%, rgba(96, 165, 250, 0.28), transparent 30%),
    radial-gradient(circle at 84% 8%, rgba(224, 242, 254, 0.95), transparent 30%),
    linear-gradient(135deg, rgba(229, 241, 255, 0.92), rgba(207, 225, 250, 0.92)) !important;
  background-size: 56px 56px, 56px 56px, auto, auto, auto !important;
  color: #10213d;
}

.sdk-access-page::before,
.sdk-access-page.is-light-skin::before,
.sdk-access-page.is-dark-skin::before {
  display: block;
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  background:
    radial-gradient(circle at 90% 18%, rgba(255, 255, 255, 0.78), transparent 23%),
    linear-gradient(110deg, transparent 0%, rgba(125, 211, 252, 0.12) 38%, transparent 66%);
  opacity: 0.9;
  pointer-events: none;
}

.sdk-access-page::after,
.sdk-access-page.is-light-skin::after,
.sdk-access-page.is-dark-skin::after {
  display: block;
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  z-index: 0;
  width: min(520px, 42vw);
  height: 380px;
  background:
    linear-gradient(rgba(96, 165, 250, 0.13) 1px, transparent 1px),
    linear-gradient(90deg, rgba(96, 165, 250, 0.11) 1px, transparent 1px);
  background-size: 28px 28px;
  mask-image: linear-gradient(135deg, transparent 0%, #000 45%, #000 100%);
  opacity: 0.72;
  pointer-events: none;
}

.page-header {
  position: relative;
  z-index: 1;
  align-items: center;
  min-height: 152px;
  margin-bottom: 20px;
  padding: 28px 32px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.64) !important;
  border-radius: 8px;
  background:
    linear-gradient(120deg, rgba(255, 255, 255, 0.72), rgba(219, 234, 254, 0.34)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.86),
    inset 0 -1px 0 rgba(96, 165, 250, 0.08),
    0 20px 44px rgba(30, 64, 175, 0.14) !important;
  backdrop-filter: blur(24px) saturate(1.1) !important;
}

.page-header::after {
  content: '';
  position: absolute;
  right: 6%;
  top: 34%;
  width: 42%;
  height: 54%;
  background:
    radial-gradient(circle at 12% 36%, rgba(255, 255, 255, 0.9) 0 2px, transparent 3px),
    radial-gradient(circle at 46% 18%, rgba(255, 255, 255, 0.82) 0 2px, transparent 3px),
    radial-gradient(circle at 88% 42%, rgba(255, 255, 255, 0.74) 0 2px, transparent 3px),
    linear-gradient(165deg, transparent 0%, rgba(255, 255, 255, 0.48) 48%, transparent 52%);
  opacity: 0.78;
  pointer-events: none;
}

.page-header h1 {
  margin: 16px 0 12px;
  color: #0b1b36 !important;
  font-size: 36px;
  font-weight: 800;
  letter-spacing: 0;
  text-shadow: none !important;
}

.page-header p {
  color: #405475 !important;
  font-size: 15px;
}

.page-header :deep(.el-button.is-link) {
  height: 28px;
  padding: 0 8px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.34);
  color: #405475 !important;
  font-size: 15px;
  font-weight: 650;
}

.header-actions {
  align-items: center;
  gap: 18px;
}

.header-actions :deep(.el-tag) {
  height: 38px;
  padding: 0 24px;
  border-color: rgb(var(--brand-primary-rgb) / 0.22) !important;
  border-radius: 999px;
  background: rgba(238, 242, 255, 0.58) !important;
  color: var(--brand-active) !important;
  font-size: 14px;
  font-weight: 800;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 12px 28px rgb(var(--brand-active-rgb) / 0.12) !important;
}

.header-actions :deep(.el-button) {
  height: 46px;
  min-width: 140px;
  border-color: rgba(148, 163, 184, 0.28) !important;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.68) !important;
  color: #27364f !important;
  font-size: 15px;
  font-weight: 750;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86), 0 14px 32px rgba(30, 64, 175, 0.1) !important;
  backdrop-filter: blur(16px);
}

.wizard-shell {
  position: relative;
  z-index: 1;
  grid-template-columns: minmax(280px, 316px) minmax(0, 1fr);
  gap: 20px;
}

.step-progress,
.stage-shell {
  border: 1px solid rgba(255, 255, 255, 0.62) !important;
  border-radius: 8px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.56), rgba(223, 236, 253, 0.36)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.78),
    0 22px 48px rgba(30, 64, 175, 0.14) !important;
  backdrop-filter: blur(24px) saturate(1.08) !important;
}

.step-progress {
  padding: 18px 10px 18px;
}

.access-progress {
  padding: 8px 18px 20px;
  color: #304666;
  font-size: 14px;
  font-weight: 650;
}

.access-progress strong {
  margin: 0 8px;
  color: #0f2c57;
  font-size: 16px;
  font-weight: 800;
}

.access-progress-track {
  height: 7px;
  margin-top: 12px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.64);
  box-shadow: inset 0 1px 2px rgba(30, 64, 175, 0.08);
}

.access-progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #19c5bd, #60a5fa);
  box-shadow: 0 0 18px rgba(34, 211, 238, 0.42);
  transition: width 0.24s ease;
}

.progress-step {
  position: relative;
  min-height: 86px;
  margin: 0 0 2px;
  padding: 13px 8px 13px 26px;
  grid-template-columns: 34px minmax(0, 1fr) 20px;
  border: 0 !important;
  border-radius: 8px;
  background: transparent !important;
  box-shadow: none !important;
  color: #203653 !important;
}

.progress-step::before {
  content: '';
  position: absolute;
  left: 39px;
  top: -18px;
  bottom: -18px;
  width: 1px;
  background: rgba(92, 118, 153, 0.28);
}

.progress-step:first-of-type::before {
  top: 38px;
}

.progress-step:last-of-type::before {
  bottom: 38px;
}

.progress-step:hover {
  background: rgba(255, 255, 255, 0.32) !important;
}

.progress-step.active {
  background: linear-gradient(120deg, rgba(255, 255, 255, 0.72), rgba(224, 231, 255, 0.48)) !important;
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.86),
    0 0 0 1px rgb(var(--brand-hover-rgb) / 0.18),
    0 0 28px rgba(129, 140, 248, 0.28) !important;
}

.step-index {
  position: relative;
  z-index: 1;
  width: 26px;
  height: 26px;
  border: 1px solid rgba(106, 133, 170, 0.34) !important;
  background: rgba(239, 246, 255, 0.86) !important;
  color: #53657f !important;
  font-size: 13px;
}

.progress-step.done .step-index {
  border-color: rgba(34, 197, 94, 0.28) !important;
  background: rgba(220, 252, 231, 0.9) !important;
  color: #16a34a !important;
  box-shadow: 0 0 16px rgba(34, 197, 94, 0.2) !important;
}

.progress-step.active .step-index {
  border-color: rgb(var(--brand-primary-rgb) / 0.4) !important;
  background: rgba(238, 242, 255, 0.95) !important;
  color: var(--brand-active) !important;
}

.step-copy strong {
  color: #14233b !important;
  font-size: 15px;
  line-height: 1.35;
}

.step-copy small {
  color: #4f6380 !important;
  font-size: 13px;
}

.step-caret {
  color: #3157ff !important;
  filter: drop-shadow(0 0 9px rgba(49, 87, 255, 0.34)) !important;
}

.stage-shell {
  min-height: 622px;
  overflow: hidden;
}

.focus-panel {
  min-height: 540px;
  padding: 34px 32px;
  background: transparent !important;
}

.panel-head {
  margin-bottom: 26px;
}

.panel-head h2 {
  margin-top: 8px;
  color: #0b1b36 !important;
  font-size: 32px;
  font-weight: 800;
  letter-spacing: 0;
  text-shadow: none !important;
}

.step-kicker {
  color: #3157ff !important;
  font-size: 14px;
  font-weight: 800;
  text-shadow: none !important;
}

.panel-head :deep(.el-tag) {
  height: 34px;
  padding: 0 16px;
  border-color: rgba(96, 165, 250, 0.25) !important;
  border-radius: 8px;
  background: rgba(219, 234, 254, 0.56) !important;
  color: #1e40af !important;
  font-weight: 700;
}

.health-grid {
  grid-template-columns: repeat(4, minmax(190px, 1fr));
  gap: 14px;
}

.health-card {
  position: relative;
  min-height: 186px;
  overflow: hidden;
  padding: 24px 22px;
  border: 1px solid rgba(148, 163, 184, 0.28) !important;
  border-radius: 8px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.72), rgba(232, 241, 255, 0.42)) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 16px 34px rgba(30, 64, 175, 0.08) !important;
  backdrop-filter: blur(18px);
}

.health-card::after {
  content: '';
  position: absolute;
  right: -22px;
  bottom: -24px;
  width: 112px;
  height: 100px;
  border-radius: 24px;
  background:
    linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.16), rgba(96, 165, 250, 0.08)),
    linear-gradient(rgb(var(--brand-primary-rgb) / 0.18) 1px, transparent 1px),
    linear-gradient(90deg, rgb(var(--brand-primary-rgb) / 0.14) 1px, transparent 1px);
  background-size: auto, 18px 18px, 18px 18px;
  transform: rotate(-25deg);
  opacity: 0.72;
}

.health-card.good {
  border-color: rgba(34, 197, 94, 0.24) !important;
  background:
    radial-gradient(circle at 76% 66%, rgba(187, 247, 208, 0.42), transparent 34%),
    linear-gradient(145deg, rgba(240, 253, 244, 0.7), rgba(232, 241, 255, 0.36)) !important;
}

.health-card.warn {
  border-color: rgba(245, 158, 11, 0.28) !important;
  background:
    radial-gradient(circle at 76% 66%, rgba(254, 215, 170, 0.38), transparent 34%),
    linear-gradient(145deg, rgba(255, 251, 235, 0.72), rgba(232, 241, 255, 0.36)) !important;
}

.health-icon {
  position: relative;
  z-index: 1;
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  margin-bottom: 24px;
  border: 1px solid rgba(96, 165, 250, 0.24);
  border-radius: 8px;
  background: rgba(219, 234, 254, 0.62);
  color: #4f7cff;
  font-size: 24px;
}

.health-card.good .health-icon {
  border-color: rgba(34, 197, 94, 0.2);
  background: rgba(220, 252, 231, 0.72);
  color: #16a34a;
}

.health-label {
  position: relative;
  z-index: 1;
  display: block;
  color: #405475 !important;
  font-size: 14px !important;
}

.health-card strong {
  position: relative;
  z-index: 1;
  margin: 10px 0 12px;
  color: #071a35 !important;
  font-size: 28px;
  font-weight: 850;
  letter-spacing: 0;
}

.health-card small {
  position: relative;
  z-index: 1;
  color: #405475 !important;
  font-size: 13px !important;
}

.wizard-footer {
  border-top-color: rgba(255, 255, 255, 0.52) !important;
  background: rgba(255, 255, 255, 0.36) !important;
  backdrop-filter: blur(18px) !important;
}

.wizard-footer,
.step-screen {
  :deep(.el-button) {
    border-color: rgba(148, 163, 184, 0.32) !important;
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.62) !important;
    color: #27364f !important;
    font-weight: 700;
  }

  :deep(.el-button--primary) {
    border-color: rgba(49, 87, 255, 0.34) !important;
    background: linear-gradient(135deg, #3b82f6, #6254f3) !important;
    color: #fff !important;
    box-shadow: 0 12px 28px rgba(59, 130, 246, 0.22) !important;
  }
}

@media (max-width: 1280px) {
  .health-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 980px) {
  .wizard-shell,
  .health-grid {
    grid-template-columns: 1fr;
  }

  .page-header {
    min-height: auto;
  }

  .progress-step::before {
    display: none;
  }
}

/* Parity refinements for the approved design reference. */
:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .topbar),
:global(.main-layout.registry-shell:not(.is-dark):has(.sdk-access-page.is-light-skin) .topbar),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-dark-skin) .topbar) {
  border: 1px solid rgba(255, 255, 255, 0.48) !important;
  border-left: 0 !important;
  border-right: 0 !important;
  background:
    linear-gradient(90deg, rgba(31, 56, 92, 0.86) 0%, rgba(86, 125, 181, 0.62) 48%, rgba(191, 228, 255, 0.78) 100%) !important;
  color: #f8fbff !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.52), 0 16px 34px rgba(30, 64, 175, 0.18) !important;
  backdrop-filter: blur(24px) saturate(1.18) !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .breadcrumb-area .el-breadcrumb__separator),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-dark-skin) .breadcrumb-area .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-dark-skin) .breadcrumb-area .el-breadcrumb__separator) {
  color: rgba(255, 255, 255, 0.84) !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-dark-skin) .breadcrumb-area .el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #ffffff !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .topbar-btn),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-dark-skin) .topbar-btn) {
  border-color: rgba(255, 255, 255, 0.7) !important;
  background: rgba(255, 255, 255, 0.48) !important;
  color: #21416f !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 8px 20px rgba(37, 99, 235, 0.12) !important;
  backdrop-filter: blur(16px) !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .user-avatar),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-dark-skin) .user-avatar) {
  box-shadow: 0 10px 24px rgb(var(--brand-active-rgb) / 0.24) !important;
}

.sdk-access-page,
.sdk-access-page.is-light-skin,
.sdk-access-page.is-dark-skin {
  padding: 12px 30px 36px;
  background:
    linear-gradient(rgba(255, 255, 255, 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.13) 1px, transparent 1px),
    radial-gradient(circle at 76% 0%, rgba(224, 242, 254, 0.92), transparent 26%),
    radial-gradient(circle at 21% 20%, rgba(147, 197, 253, 0.28), transparent 34%),
    linear-gradient(135deg, #d6e6fb 0%, #eef6ff 46%, #d8e7fb 100%) !important;
}

.page-header {
  min-height: 174px;
  margin-bottom: 20px;
  padding: 22px 34px;
}

.page-header h1 {
  margin: 14px 0 10px;
  font-size: 34px;
}

.page-header p::before {
  content: '项目名称：';
}

.page-header::after {
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
}

.wizard-shell {
  grid-template-columns: minmax(300px, 315px) minmax(0, 1fr);
  gap: 22px;
}

.step-progress {
  padding: 18px 8px 20px;
}

.access-progress {
  padding: 8px 20px 20px;
}

.progress-step {
  min-height: 86px;
  padding: 13px 18px 13px 22px;
  grid-template-columns: 34px minmax(0, 1fr) 20px;
}

.progress-step.active {
  border: 1px solid rgba(255, 255, 255, 0.72) !important;
  background:
    linear-gradient(120deg, rgba(255, 255, 255, 0.76), rgba(228, 235, 255, 0.55)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 0 0 1px rgb(var(--brand-hover-rgb) / 0.22),
    0 0 22px rgba(94, 234, 212, 0.28),
    0 0 28px rgb(var(--brand-hover-rgb) / 0.28) !important;
}

.progress-step.done.active .step-index {
  border-color: rgba(34, 197, 94, 0.32) !important;
  background: rgba(220, 252, 231, 0.96) !important;
  color: #16a34a !important;
  box-shadow: 0 0 18px rgba(34, 197, 94, 0.24) !important;
}

.step-index {
  width: 30px;
  height: 30px;
}

.step-dot {
  width: 10px;
  height: 10px;
  display: block;
  border-radius: 50%;
  background: #8fa0b8;
  box-shadow: inset 0 0 0 2px rgba(255, 255, 255, 0.7);
}

.step-title-line {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.step-number {
  width: 22px;
  height: 22px;
  flex: 0 0 auto;
  display: inline-grid;
  place-items: center;
  border: 1px solid rgba(105, 126, 160, 0.34);
  border-radius: 50%;
  background: rgba(241, 245, 249, 0.72);
  color: #51617a;
  font-size: 12px;
  font-weight: 800;
}

.progress-step.done .step-number {
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  background: rgba(238, 242, 255, 0.84);
  color: var(--brand-active);
}

.step-copy small {
  padding-left: 34px;
}

.stage-shell {
  min-height: 622px;
}

.focus-panel {
  min-height: 526px;
  padding: 34px 28px 28px;
}

.panel-head {
  margin-bottom: 26px;
}

.panel-head h2 {
  font-size: 32px;
}

.health-grid {
  gap: 16px;
}

.health-card {
  min-height: 238px;
  padding: 24px 22px;
}

.health-card::after {
  right: -14px;
  bottom: -26px;
  width: 118px;
  height: 112px;
  border-radius: 18px;
}

.health-card.access .health-icon {
  border-color: rgb(var(--brand-primary-rgb) / 0.22);
  background: rgba(238, 242, 255, 0.72);
  color: var(--brand-primary);
}

.health-card.credential .health-icon {
  border-color: rgba(34, 197, 94, 0.2);
  background: rgba(220, 252, 231, 0.72);
  color: #16a34a;
}

.health-card.instances .health-icon {
  border-color: rgba(96, 165, 250, 0.28);
  background: rgba(219, 234, 254, 0.72);
  color: #4f7cff;
}

.health-card.assets .health-icon {
  border-color: rgba(34, 211, 238, 0.28);
  background: rgba(207, 250, 254, 0.64);
  color: #0891b2;
}

.health-icon-text {
  font-size: 12px;
  font-weight: 900;
  letter-spacing: 0;
}

.health-card strong {
  font-size: 27px;
}

.health-card.access::after {
  background:
    linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.18), rgba(129, 140, 248, 0.08)),
    linear-gradient(rgb(var(--brand-primary-rgb) / 0.2) 1px, transparent 1px),
    linear-gradient(90deg, rgb(var(--brand-primary-rgb) / 0.16) 1px, transparent 1px);
  background-size: auto, 18px 18px, 18px 18px;
}

.health-card.credential::after {
  background:
    radial-gradient(circle at 36% 42%, rgba(34, 197, 94, 0.22), transparent 28%),
    linear-gradient(135deg, rgba(34, 197, 94, 0.18), rgba(187, 247, 208, 0.08));
}

.health-card.instances::after {
  background:
    linear-gradient(rgba(96, 165, 250, 0.18) 1px, transparent 1px),
    linear-gradient(90deg, rgba(96, 165, 250, 0.14) 1px, transparent 1px),
    linear-gradient(135deg, rgba(96, 165, 250, 0.16), rgba(219, 234, 254, 0.08));
  background-size: 18px 18px, 18px 18px, auto;
}

.health-card.assets::after {
  background:
    radial-gradient(circle at 24% 26%, rgba(34, 211, 238, 0.28), transparent 10%),
    linear-gradient(135deg, rgba(34, 211, 238, 0.16), rgba(207, 250, 254, 0.1)),
    linear-gradient(rgba(34, 211, 238, 0.16) 1px, transparent 1px),
    linear-gradient(90deg, rgba(34, 211, 238, 0.12) 1px, transparent 1px);
  background-size: auto, auto, 18px 18px, 18px 18px;
}

@media (min-width: 1600px) {
  .page-header {
    min-height: 174px;
  }

  .health-card {
    min-height: 238px;
  }
}

/* Compact height pass: keep the glass style, reduce scrolling on first view. */
.sdk-access-page,
.sdk-access-page.is-light-skin,
.sdk-access-page.is-dark-skin {
  padding: 10px 24px 20px;
}

.page-header {
  min-height: 132px;
  margin-bottom: 14px;
  padding: 16px 28px;
}

.page-header h1 {
  margin: 10px 0 8px;
  font-size: 31px;
  line-height: 1.12;
}

.page-header p {
  font-size: 14px;
}

.page-header :deep(.el-button.is-link) {
  height: 24px;
}

.header-actions :deep(.el-tag) {
  height: 34px;
  padding: 0 22px;
}

.header-actions :deep(.el-button) {
  height: 42px;
  min-width: 132px;
}

.wizard-shell {
  gap: 14px;
}

.step-progress {
  padding: 12px 8px 14px;
}

.access-progress {
  padding: 4px 18px 12px;
}

.access-progress-track {
  height: 6px;
  margin-top: 9px;
}

.progress-step {
  min-height: 66px;
  padding: 7px 16px 7px 22px;
}

.progress-step::before {
  left: 37px;
  top: -12px;
  bottom: -12px;
}

.progress-step:first-of-type::before {
  top: 31px;
}

.progress-step:last-of-type::before {
  bottom: 31px;
}

.step-index {
  width: 28px;
  height: 28px;
}

.step-number {
  width: 20px;
  height: 20px;
}

.step-title-line {
  gap: 10px;
}

.step-copy strong {
  font-size: 14px;
}

.step-copy small {
  padding-left: 30px;
  font-size: 12px;
}

.stage-shell {
  min-height: 506px;
}

.focus-panel {
  min-height: 420px;
  padding: 27px 28px 16px;
}

.panel-head {
  margin-bottom: 18px;
}

.panel-head h2 {
  margin-top: 5px;
  font-size: 30px;
  line-height: 1.18;
}

.panel-head :deep(.el-tag) {
  height: 32px;
}

.health-grid {
  gap: 14px;
}

.health-card {
  min-height: 176px;
  padding: 18px 20px;
}

.health-icon {
  width: 42px;
  height: 42px;
  margin-bottom: 16px;
  font-size: 22px;
}

.health-card strong {
  margin: 8px 0 9px;
  font-size: 25px;
}

.health-card::after {
  right: -12px;
  bottom: -34px;
  width: 106px;
  height: 106px;
}

.wizard-footer {
  padding: 12px 28px;
}

@media (min-width: 1600px) {
  .page-header {
    min-height: 132px;
  }

  .health-card {
    min-height: 176px;
  }
}

/* Extra compact pass: keep the whole first step visible in the main scroll area. */
.sdk-access-page,
.sdk-access-page.is-light-skin,
.sdk-access-page.is-dark-skin {
  padding: 8px 22px 12px;
}

.page-header {
  min-height: 112px;
  margin-bottom: 12px;
  padding: 13px 26px;
}

.page-header h1 {
  margin: 8px 0 6px;
  font-size: 29px;
}

.header-actions :deep(.el-button) {
  height: 38px;
}

.header-actions :deep(.el-tag) {
  height: 32px;
}

.wizard-shell {
  gap: 12px;
}

.step-progress {
  padding: 10px 8px 12px;
}

.access-progress {
  padding: 2px 18px 10px;
}

.progress-step {
  min-height: 58px;
  padding-top: 5px;
  padding-bottom: 5px;
}

.progress-step:focus,
.progress-step:focus-visible {
  outline: none;
}

.progress-step::before {
  top: -10px;
  bottom: -10px;
}

.progress-step:first-of-type::before {
  top: 28px;
}

.progress-step:last-of-type::before {
  bottom: 28px;
}

.step-index {
  width: 26px;
  height: 26px;
}

.step-copy strong {
  font-size: 13px;
}

.step-copy small {
  font-size: 12px;
}

.stage-shell {
  min-height: 456px;
}

.focus-panel {
  min-height: 390px;
  padding: 22px 26px 12px;
}

.panel-head {
  margin-bottom: 14px;
}

.panel-head h2 {
  font-size: 28px;
}

.health-grid {
  gap: 12px;
}

.health-card {
  min-height: 150px;
  padding: 15px 18px;
}

.health-icon {
  width: 38px;
  height: 38px;
  margin-bottom: 12px;
  font-size: 20px;
}

.health-card strong {
  margin: 6px 0 7px;
  font-size: 23px;
}

.health-card small,
.health-label {
  font-size: 12px !important;
}

.health-card::after {
  bottom: -44px;
  width: 96px;
  height: 96px;
}

.wizard-footer {
  min-height: 48px;
  padding: 9px 26px;
}

@media (min-width: 1600px) {
  .page-header {
    min-height: 112px;
  }

  .health-card {
    min-height: 150px;
  }
}

/* Purple tone alignment: keep the glass layout, shift blue surfaces toward the global tech-violet palette. */
:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .topbar),
:global(.main-layout.registry-shell:not(.is-dark):has(.sdk-access-page.is-light-skin) .topbar),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-dark-skin) .topbar) {
  background:
    var(--brand-topbar-bg) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.5), 0 16px 34px rgb(var(--brand-primary-rgb) / 0.18) !important;
}

:global(.main-layout.registry-shell:has(.sdk-access-page.is-light-skin) .topbar-btn),
:global(.main-layout.registry-shell:has(.sdk-access-page.is-dark-skin) .topbar-btn) {
  color: #4338ca !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 8px 20px rgb(var(--brand-primary-rgb) / 0.14) !important;
}

.sdk-access-page,
.sdk-access-page.is-light-skin,
.sdk-access-page.is-dark-skin {
  background:
    linear-gradient(rgba(255, 255, 255, 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.13) 1px, transparent 1px),
    radial-gradient(circle at 78% 0%, rgb(var(--brand-selected-rgb) / 0.92), transparent 28%),
    radial-gradient(circle at 20% 18%, rgb(var(--brand-selected-rgb) / 0.38), transparent 34%),
    radial-gradient(circle at 72% 72%, rgb(var(--brand-hover-rgb) / 0.16), transparent 32%),
    var(--brand-page-bg) !important;
}

.page-header,
.step-progress,
.stage-shell {
  border-color: rgb(var(--brand-selected-rgb) / 0.66) !important;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.6), rgb(var(--brand-selected-rgb) / 0.38)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 22px 48px rgb(var(--brand-primary-rgb) / 0.14) !important;
}

.page-header h1,
.panel-head h2 {
  color: #11183a !important;
}

.page-header p,
.access-progress,
.step-copy small,
.health-card small,
.health-label {
  color: #4f5f81 !important;
}

.access-progress strong,
.step-kicker {
  color: var(--brand-active) !important;
}

.access-progress-track {
  background: rgba(255, 255, 255, 0.68);
  box-shadow: inset 0 1px 2px rgb(var(--brand-active-rgb) / 0.1);
}

.access-progress-track i {
  background: linear-gradient(90deg, var(--brand-primary), var(--brand-hover));
  box-shadow: 0 0 18px rgb(var(--brand-hover-rgb) / 0.38);
}

.progress-step::before {
  background: rgba(109, 88, 181, 0.24);
}

.progress-step.active {
  background:
    linear-gradient(120deg, rgba(255, 255, 255, 0.78), rgb(var(--brand-selected-rgb) / 0.62)) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 0 0 1px rgb(var(--brand-hover-rgb) / 0.32),
    0 0 24px rgb(var(--brand-hover-rgb) / 0.24),
    0 0 30px rgb(var(--brand-primary-rgb) / 0.18) !important;
}

.progress-step.active .step-index,
.progress-step.done .step-number {
  border-color: rgb(var(--brand-primary-rgb) / 0.34) !important;
  background: rgba(238, 242, 255, 0.95) !important;
  color: var(--brand-active) !important;
}

.step-caret {
  color: var(--brand-primary) !important;
  filter: drop-shadow(0 0 9px rgb(var(--brand-primary-rgb) / 0.32)) !important;
}

.header-actions :deep(.el-tag),
.panel-head :deep(.el-tag),
.status-chip.primary {
  border-color: rgb(var(--brand-hover-rgb) / 0.28) !important;
  background: rgba(238, 242, 255, 0.68) !important;
  color: var(--brand-active) !important;
}

.health-card {
  border-color: rgb(var(--brand-hover-rgb) / 0.26) !important;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.74), rgb(var(--brand-selected-rgb) / 0.4)) !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78), 0 16px 34px rgb(var(--brand-primary-rgb) / 0.09) !important;
}

.health-icon,
.health-card.access .health-icon,
.health-card.instances .health-icon,
.health-card.assets .health-icon {
  border-color: rgb(var(--brand-hover-rgb) / 0.24);
  background: rgba(238, 242, 255, 0.72);
  color: var(--brand-primary);
}

.health-card.access::after,
.health-card.instances::after,
.health-card.assets::after {
  background:
    linear-gradient(135deg, rgb(var(--brand-primary-rgb) / 0.18), rgb(var(--brand-hover-rgb) / 0.1)),
    linear-gradient(rgb(var(--brand-primary-rgb) / 0.18) 1px, transparent 1px),
    linear-gradient(90deg, rgb(var(--brand-hover-rgb) / 0.14) 1px, transparent 1px);
  background-size: auto, 18px 18px, 18px 18px;
}

.wizard-footer,
.step-screen {
  :deep(.el-button--primary) {
    border-color: rgb(var(--brand-primary-rgb) / 0.34) !important;
    background: linear-gradient(135deg, var(--brand-primary), var(--brand-hover)) !important;
    box-shadow: 0 12px 28px rgb(var(--brand-primary-rgb) / 0.24) !important;
  }
}

.ai-onboarding-alert {
  margin-bottom: 14px;
}

.ai-session-card {
  margin: 14px 10px 0;
  padding: 14px;
  border: 1px solid rgb(var(--brand-hover-rgb) / 0.2);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.58);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.ai-session-head,
.ai-session-progress,
.ai-session-step {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.ai-session-head span {
  color: #11183a;
  font-size: 13px;
  font-weight: 800;
}

.ai-session-progress {
  margin: 10px 0 12px;
}

.ai-session-progress strong {
  color: var(--brand-active);
  font-size: 18px;
}

.ai-session-progress span {
  min-width: 0;
  overflow: hidden;
  color: #64748b;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-session-steps {
  display: grid;
  gap: 7px;
}

.ai-session-step {
  justify-content: flex-start;
  min-height: 28px;
  padding: 0 8px;
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.76);
  color: #334155;
  font-size: 12px;
}

.ai-session-step i {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: #94a3b8;
}

.ai-session-step span {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-session-step em {
  flex: 0 0 auto;
  color: #64748b;
  font-style: normal;
}

.ai-session-step.pass i {
  background: #22c55e;
}

.ai-session-step.warn i,
.ai-session-step.running i {
  background: #f59e0b;
}

.ai-session-step.fail i {
  background: #ef4444;
}

.ai-coding-key-panel {
  margin-bottom: 14px;
  padding: 14px;
  border: 1px solid rgb(var(--brand-hover-rgb) / 0.18);
  border-radius: 8px;
  background: rgb(var(--brand-selected-rgb) / 0.18);
}

.ai-coding-key-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.ai-coding-key-head strong {
  display: block;
  color: #11183a;
}

.ai-coding-key-head span {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: #64748b;
}

.ai-coding-key-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 10px;
}

.ai-tool-tabs {
  margin-bottom: 10px;
}

.ai-prompt-input :deep(.el-textarea__inner) {
  font-family: "JetBrains Mono", "Fira Code", Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
}

@media (max-width: 768px) {
  .ai-coding-key-form {
    grid-template-columns: 1fr;
  }
}
</style>

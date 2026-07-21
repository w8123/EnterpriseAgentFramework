import { computed, type Ref } from 'vue'
import type {
  AiAccessSession,
  AiOnboardingManifest,
  ScanProject,
} from '@/types/scanProject'
import {
  highlightJsCode,
  highlightXmlCode,
  highlightYamlCode,
} from '@/views/registry/composables/sdkAccessWizardCodeHighlight'

export interface UseSdkAccessWizardSnippetsDeps {
  projectCode: Ref<string>
  project: Ref<ScanProject | null>
  aiOnboardingManifest: Ref<AiOnboardingManifest | null>
  accessSession: Ref<AiAccessSession | null>
  aiCodingAccessEnabled: Ref<boolean>
  aiCodingAccessKey: Ref<string>
  aiPromptTool: Ref<'cursor' | 'claude' | 'codex'>
  gatewayBaseUrl: Ref<string>
  embedTokenPath: Ref<string>
}

export function useSdkAccessWizardSnippets(deps: UseSdkAccessWizardSnippetsDeps) {
  const starterDependencySnippet = computed(() => `<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-spring-boot2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>`)

  const starterApplicationSnippet = computed(() => `reachai:
  registry:
    enabled: true
    url: \${REACHAI_REGISTRY_URL:http://localhost:18603}
    app-key: ${deps.project.value?.registryAppKey || 'your-app-key'}
    app-secret: \${REACHAI_REGISTRY_APP_SECRET}
    heartbeat-interval-ms: 30000
  project:
    code: ${deps.project.value?.projectCode || deps.projectCode.value}
    name: ${deps.project.value?.name || 'your-service-name'}
    base-url: ${deps.project.value?.baseUrl || 'http://localhost:8080'}
    # 必须填写 ReachAI 服务端实际可访问的业务系统地址；localhost 仅适用于同机、同网络命名空间联调。
    context-path: ${deps.project.value?.contextPath || ''}
    environment: ${deps.project.value?.environment || 'dev'}
  capability:
    scan-beans: true
    # SDK 接入阶段只注册项目与实例；接口扫描由 ReachAI API 管理手动触发。`)

  const highlightedStarterDependencySnippet = computed(() => highlightXmlCode(starterDependencySnippet.value))
  const highlightedStarterApplicationSnippet = computed(() => highlightYamlCode(starterApplicationSnippet.value))

  const gatewaySnippet = computed(() => `spring:
  cloud:
    gateway:
      routes:
        - id: ${deps.project.value?.projectCode || deps.projectCode.value}-reachai
          uri: ${deps.project.value?.baseUrl || 'http://localhost:18089'}
          predicates:
            - Path=/reachai/capabilities/**,/reachai/registry/**
          filters:
          - PreserveHostHeader

# 必须透传：
# X-ReachAI-Invocation-Token
# X-ReachAI-Trace-Id / X-ReachAI-Run-Id
# X-ReachAI-App-Key / X-ReachAI-Timestamp / X-ReachAI-Nonce / X-ReachAI-Signature
# 业务用户身份头或当前登录态`)
  const highlightedGatewaySnippet = computed(() => highlightYamlCode(gatewaySnippet.value))

  const frontendSnippet = computed(() => {
    const manifest = deps.aiOnboardingManifest.value
    const project = deps.project.value
    const code = manifest?.project.projectCode || project?.projectCode || deps.projectCode.value
    const platformUrl = manifest?.sdk?.config?.registryUrl || window.location.origin
    const expectedKeySlug = manifest?.agentProvisioning?.defaultKeySlug
      || manifest?.agentSupervisor?.globalAgentKeySlug
      || manifest?.embed?.defaultAgentKeySlug
      || `${code}-page-copilot`

    return `import { createEafChat, createEafPageBridge } from '@reachai/embed-chat'
import '@reachai/embed-chat/style.css'

const pageInstanceId = sessionStorage.getItem('reachaiPageInstanceId') || crypto.randomUUID()
sessionStorage.setItem('reachaiPageInstanceId', pageInstanceId)
const pageKey = '<current-page-key>'
const route = window.location.pathname || '/'
const pageBridge = createEafPageBridge({ pageInstanceId, route })

// Provisioning runs once during onboarding from AI tool / local shell / server-side integration.
// Browser runtime must NOT call /api/ai-coding/projects/** onboarding/provisioning/session APIs or store project signing secrets in front-end config.
// Use only the provisioned bare JSON agent.keySlug below as agentId.
const provisionedAgentKeySlug = '${expectedKeySlug}'

void createEafChat({
  mount: '#reachai-chat',
  apiBase: '${platformUrl}',
  // If the browser must use a business gateway path instead of direct ReachAI:
  // apiBase: window.location.origin,
  // embedPathPrefix: '/api/reachai/embed',
  agentId: provisionedAgentKeySlug,
  position: 'bottom-right',
  initialOpen: false,
  tokenTimeoutMs: 10000,
  bridge: pageBridge,
  page: {
    pageKey,
    name: '<current-page-name>',
    routePattern: route,
  },
  tokenProvider: async (tokenContext) => {
    const query = new URLSearchParams({
      projectCode: '${code}',
      agentId: provisionedAgentKeySlug,
      pageKey,
      pageInstanceId: pageBridge.pageInstanceId,
      route: pageBridge.route || route,
      origin: window.location.origin
    })
    const response = await fetch('${deps.embedTokenPath.value || '/api/reachai/embed-token'}?' + query, {
      signal: tokenContext?.signal
    })
    if (!response.ok) {
      throw Object.assign(new Error('embed token broker failed'), { status: response.status })
    }
    const payload = await response.json()
    if (payload.code && payload.code !== 200 && payload.code !== 0) {
      throw new Error(payload.message || 'embed token broker failed')
    }
    const token = payload.data?.token || payload.token
    if (!token) throw new Error('ReachAI embed token missing')
    return { token, expiresIn: payload.data?.expiresIn || payload.expiresIn }
  }
}).catch((error) => {
  // Token/network failures remain visible inside the mounted SDK; this catches local config/mount failures.
  console.error('[ReachAI] chat initialization failed', error instanceof Error ? error.message : 'unknown error')
})

// apiBase can be the ReachAI platform origin. For a gateway prefix, set embedPathPrefix.
// The SDK normalizes apiBase so '/api/reachai/embed' is treated as the embed API root.
// SDK 接入项目不在浏览器保存 appSecret，也不使用 pageRegistry 自动上报密钥。`
  })

  const highlightedFrontendSnippet = computed(() => highlightJsCode(frontendSnippet.value))

  const aiOnboardingPrompt = computed(() => {
    const manifest = deps.aiOnboardingManifest.value
    const project = deps.project.value
    const projectId = manifest?.project.id || project?.id || ''
    const code = manifest?.project.projectCode || project?.projectCode || deps.projectCode.value
    const name = manifest?.project.name || project?.name || code
    const appKey = manifest?.project.registryAppKey || project?.registryAppKey || 'your-app-key'
    const secretEnv = manifest?.security.appSecretEnv || 'REACHAI_REGISTRY_APP_SECRET'
    const skillPackageUrl = manifest?.endpoints.skillPackageUrl || '/api/ai-assist/skills/reachai-onboarding/latest.zip'
    const embedAgentId = manifest?.agentProvisioning?.defaultKeySlug || manifest?.embed?.defaultAgentKeySlug || manifest?.embed?.defaultAgentId || ''
    const embedAgentLine = embedAgentId
      ? `默认嵌入 Agent：${embedAgentId}`
      : '默认嵌入 Agent：平台尚未给该项目配置可嵌入 Agent；请先在 ReachAI 项目下创建/启用 Agent，再继续业务前端接入。'
    const allowedAgents = manifest?.embed?.allowedAgents || []
    const allowedAgentLine = allowedAgents.length
      ? `可嵌入 Agent 清单：${allowedAgents.map((item) => item.keySlug || item.id).join(', ')}`
      : '可嵌入 Agent 清单：空'
    const aiCodingKey = deps.aiCodingAccessKey.value.trim()
    const platformUrl = manifest?.sdk.config.registryUrl || window.location.origin
    const externalProjectRoot = `${platformUrl}/api/ai-coding/projects/${projectId}`
    const externalManifestUrl = `${externalProjectRoot}/onboarding-manifest`
    const agentProvisioning = manifest?.agentProvisioning
    const provisionAgentUrl = `${externalProjectRoot}/agents/provision`
    const agentSupervisor = manifest?.agentSupervisor
    const workflowAiCoding = agentSupervisor?.workflowAiCoding
    const globalAgentKeySlug = agentProvisioning?.defaultKeySlug || agentSupervisor?.globalAgentKeySlug || embedAgentId || `${code}-page-copilot`
    const agentProvisioningBlock = [
      `- Agent provisioning model: ${agentProvisioning?.model || 'agent-provisioning.v2'}`,
      `- Provisioning API: ${provisionAgentUrl}`,
      `- Default Agent keySlug: ${globalAgentKeySlug}`,
      `- Idempotent: ${agentProvisioning?.idempotent === false ? 'false' : 'true'}`,
      `- Creates Supervisor config: ${agentProvisioning?.createsSupervisorConfig === false ? 'false' : 'true'}`,
      `- Activates Supervisor config: ${agentProvisioning?.activatesSupervisorConfig === false ? 'false' : 'true'}`,
      `- Model selection: ${agentProvisioning?.modelSelection || 'REQUESTED_OR_FIRST_ACTIVE_LLM'}`,
      '- Cursor must POST the provisioning API before frontend embed work, verify response.supervisorConfig.status is ACTIVE, and use response.agent.keySlug as the agentId (bare JSON, not data.agent.keySlug).',
      '- Do not ask the business user to manually create, choose, or configure the page copilot Agent during SDK onboarding.',
    ].join('\n')
    const agentSupervisorBlock = [
      `- Agent Supervisor model: ${agentSupervisor?.model || 'agent-supervisor.workflow-tools.v1'}`,
      `- Page copilot agent keySlug: ${globalAgentKeySlug}`,
      `- Runtime type: ${agentSupervisor?.runtimeType || 'AGENTSCOPE'}`,
      `- Workflow tool catalog: ${agentSupervisor?.workflowToolCatalog || 'WORKFLOW_AS_TOOL_ALLOW_LIST'}`,
      `- Agents API: ${agentSupervisor?.endpoints?.agentsUrl || `${platformUrl}/api/agents`}`,
      `- Config versions API: ${agentSupervisor?.endpoints?.configVersionsUrlTemplate || `${platformUrl}/api/agents/{agentId}/config-versions`}`,
      `- Workflow Tool attach API: ${agentSupervisor?.endpoints?.workflowToolAttachUrlTemplate || `${platformUrl}/api/workflows/{workflowId}/page-assistant/attach-tool`}`,
    ].join('\n')
    const workflowAiCodingPublishUrl = workflowAiCoding?.publishUrlTemplate || `${platformUrl}/api/workflows/{workflowId}/ai-coding/publish`
    const workflowAiCodingBlock = [
      `- Workflow AI Coding skill package: ${workflowAiCoding?.skillPackageUrl || `${platformUrl}/api/ai-assist/skills/workflow-ai-coding/latest.zip`}`,
      `- Context URL template: ${workflowAiCoding?.contextUrlTemplate || `${platformUrl}/api/workflows/{workflowId}/ai-coding/context`}`,
      `- Patch URL template: ${workflowAiCoding?.patchUrlTemplate || `${platformUrl}/api/workflows/{workflowId}/ai-coding/patch`}`,
      `- Validate URL template: ${workflowAiCoding?.validateUrlTemplate || `${platformUrl}/api/workflows/{workflowId}/ai-coding/validate`}`,
      `- Versions URL template: ${workflowAiCoding?.versionsUrlTemplate || `${platformUrl}/api/workflows/{workflowId}/ai-coding/versions`}`,
      `- Publish URL template: ${workflowAiCodingPublishUrl}`,
      '- Workflow AI Coding 允许发布：完成默认工作流绘制、保存草稿并确认 release validation 通过后，必须调用 publish URL 做首次发布，创建 ACTIVE workflow version。',
      '- 首次发布建议版本号使用 v1.0.0；若版本已存在，请读取 /versions 后使用下一个语义化版本号。',
      '- 发布请求必须发送 X-ReachAI-AiCoding-Key header，body 至少包含 {"version":"v1.0.0","note":"initial AI Coding publish","publishedBy":"<toolName>"}。',
    ].join('\n')
    const globalPageRoutingBlock = [
      `- Use one project page copilot Agent for the embedded AI button. Expected keySlug after provisioning: ${globalAgentKeySlug}.`,
      '- The actual frontend agentId must come from the provisioning response: response.agent.keySlug (bare JSON, not data.agent.keySlug).',
      '- Each business page must pass the same pageKey/pageInstanceId/route/origin to token broker, createEafChat({ page }), and page actions.',
      '- The browser SDK creates /api/embed/chat/sessions with pageKey, route, pageInstanceId and bridgeActions.',
      '- ReachAI AgentScope Supervisor selects zero, one, or multiple published Workflows from the Agent Workflow-as-Tool allow-list.',
      '- Page-action Workflows may be selected only when the user explicitly asks to open, navigate, query, or operate a page.',
      '- Do not create one floating AI button per workflow. Page assistants are published Workflow tools exposed by the project page copilot Agent.'
    ].join('\n')
    const responseShapeBlock = [
      '平台响应形态（不要默认所有接口都读 data. 或都读顶层）：',
      '- Embed 对外 API（token exchange、sessions、messages、page-actions）：ApiResult 包装，业务字段读 data.token / data.sessionId / data.answer',
      '- POST .../agents/provision：裸 JSON，读 agent.keySlug 和 supervisorConfig.status（不是 data.agent.keySlug）',
      '- access-sessions / sdk-access-check / onboarding-manifest：裸 JSON，读顶层 sessionId、overallStatus、project/embed 等',
      '- Chat 回复禁止把顶层 message:"success" 当助手文本；助手自然语言读 data.answer',
      '- Chat 返回 data.metadata.pageActionQueue 时必须逐个执行页面动作并回传 /api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result，不能只显示 data.answer',
      '- Embed SSE 以 message.completed 结束，没有 done 事件',
    ].join('\n')
    const apiBaseContractBlock = [
      'Chat SDK apiBase contract (@reachai/embed-chat normalizes to an embed API root):',
      `- Direct ReachAI: apiBase = ReachAI platform origin, for example ${platformUrl}; SDK calls <origin>/api/embed/**`,
      '- Gateway prefix: set apiBase to the gateway origin and embedPathPrefix to /api/reachai/embed, or set apiBase to /api/reachai/embed for a relative proxy.',
      '- token broker path (for example /api/reachai/embed-token) and Chat embed API root are separate addresses.',
    ].join('\n')
    const pageActionResultBlock = [
      'Page Action 回传边界：',
      '- data.metadata.pageActionQueue 是优先级最高的页面动作队列；data.uiRequest.extension.pageActionRequest 是兼容单动作指令',
      '- 自定义聊天服务必须通过当前页面 bridge/SDK 执行 actionKey，并按 requestId POST /api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result',
      '- bridge 内部可有 FAILED/CANCELLED/TIMEOUT 等状态；回传 Embed API 时当前 DTO 的 error 是字符串，status 推荐 SUCCESS 或平台可接受的字符串',
      '- 页面助手 bridge 结构化 error 需在 API 边界映射为字符串，不要直接把对象写入 Embed PageActionResultRequest.error',
    ].join('\n')
    const npmArtifact = deps.aiOnboardingManifest.value?.sdkArtifacts?.find(
      (item) => item?.type === 'npm' || item?.packageName === '@reachai/embed-chat',
    )
    const dependencyResolutionBlock = [
      '- ReachAI 平台地址、Skill 包地址和 manifest 地址只用于读取接入资料、回传进度和自检；Maven 仍走公司仓库或本地 install。',
      '- 禁止把 ReachAI 平台 baseUrl 配成 Maven repository，禁止请求 /repository/**、/maven/**、/repository/maven/**、/api/embed/sdk 或 /npm/** 这类猜测路径。',
      '- Java 依赖必须来自业务仓库已有的公司 Maven 仓库、已发布的 ReachAI Maven 仓库，或先在 ReachAI 仓库执行 mvn -pl reachai-spring-boot2-starter -am install -DskipTests 后使用本地 Maven 仓库。',
      '- 如果 reachai-capability-sdk 或 reachai-spring-boot2-starter 无法解析，请停止并报告需要安装/发布 Maven 产物，不要虚构下载 URL。',
      npmArtifact
        ? [
            `- 前端 SDK tarball 契约（来自 manifest.sdkArtifacts）：packageName=${npmArtifact.packageName || '@reachai/embed-chat'}；version=${npmArtifact.version || ''}；downloadUrl=${npmArtifact.downloadUrl || ''}；integritySha256=${npmArtifact.integritySha256 || ''}；fallbackPolicy=${npmArtifact.fallbackPolicy || 'skill-zip-tarball'}。`,
            `- installWorkingDirectory=${npmArtifact.installWorkingDirectory || 'business-frontend-package-root'}（必须在业务前端 package.json 所在目录执行安装）。`,
            `- artifactPathWithinSkill=${npmArtifact.artifactPathWithinSkill || 'reachai-onboarding/artifacts/reachai-embed-chat-1.0.0-SNAPSHOT.tgz'}。`,
            `- installCommand / installCommandTemplate=${npmArtifact.installCommandTemplate || npmArtifact.installCommand || 'npm install "{skillExtractDir}/reachai-onboarding/artifacts/reachai-embed-chat-1.0.0-SNAPSHOT.tgz"'}；将 {skillExtractDir} 替换为 Skill zip 解压根目录的绝对路径（Skill 可在业务仓库外）。`,
            '- 不要把固定 ./artifacts 相对路径当唯一安装方式，也不要把 ReachAI 前端仓库的 npm run build:sdk 当作业务侧安装兜底。',
          ].join('\n')
        : '- 前端 SDK 包必须使用 onboarding-manifest.sdkArtifacts 中 @reachai/embed-chat 的 tarball 契约（packageName/version/downloadUrl/integritySha256/installCommand/fallbackPolicy）；不要猜测 /api/embed/sdk 或 /npm/**，也不要把 ReachAI 前端 npm run build:sdk 当作业务侧安装兜底。',
    ].join('\n')
    const annotationBoundaryBlock = [
      'ReachAI 注解边界：',
      '- @ReachCapability 用于业务方法或 Controller 方法。',
      '- @ReachParam 用于方法参数或请求 DTO 字段。',
      '- @ReachOutput 只用于返回 DTO 字段；不要写在方法上。',
      '- 如果返回值是 WebApiResult<Page<T>> 这类包装类型，优先在真实返回 DTO 字段上补 @ReachOutput，方法上仍只保留 @ReachCapability。',
    ].join('\n')
    const readinessBlock = [
      '平台自检分层解释：',
      '- CODE_READY：manifest、依赖/配置、registry 凭证、gatewayBaseUrl、embedTokenPath 等代码和配置前置条件。',
      '- RUNTIME_READY：业务服务已带 REACHAI_REGISTRY_APP_SECRET 启动，SDK 实例在线并持续心跳。',
      '- E2E_READY：嵌入式 Chat/token broker 链路真实打通；若已在 API 管理手动同步 SDK 接口，也可选择项目接口完成一次真实调用。',
      '- CODE_READY 通过但 RUNTIME_READY/E2E_READY 为 WARN，通常表示服务未启动、心跳未上报、未完成可选真实调用或尚未在 API 管理手动同步接口，不等于代码接入失败。',
    ].join('\n')
    const gatewayChecklistBlock = [
      '网关接入必查 6 项：',
      '1. /api/reachai/embed-token 使用业务登录 token，只在服务端签名调用 ReachAI POST /api/embed/token/exchange。',
      '2. /api/reachai/embed/** 代理 ReachAI /api/embed/**，必须原样透传 Authorization: Bearer <embedToken>。',
      '3. Spring Security WebFlux / OAuth2 Resource Server 需要独立高优先级 SecurityWebFilterChain；只写 permitAll 不充分。',
      '4. IgnoreUrlsRemoveJwtFilter / RemoveJwtFilter / RemoveRequestHeader=Authorization / mutate().header("Authorization", "") 不能作用于 /api/reachai/embed/**。',
      '5. Spring Cloud Gateway 代理时如网关和 ReachAI 都写 CORS 头，配置 DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST。',
      '6. ReachAI 会从服务端 POST 业务系统 /reachai/registry/capabilities/sync；网关必须路由 /reachai/registry/**，业务登录/JWT 与 CSRF 对该路径放行，同时保留 Starter 的 X-ReachAI-* 签名校验。',
    ].join('\n')
    const localTopologyBlock = [
      '本地联调拓扑：',
      `- 前端 :9200 -> 网关 :8080（${deps.embedTokenPath.value || '/api/reachai/embed-token'} + /api/reachai/embed/**）-> ReachAI :18603（/api/embed/**）。`,
      `- Chat apiBase 默认是 ReachAI 平台 origin（例如 ${platformUrl}）；gatewayBaseUrl 默认是业务网关入口（例如 ${deps.gatewayBaseUrl.value || 'http://localhost:8080'}），两者可能不同，不能互相替代。`,
      '- reachai.project.base-url 是 ReachAI 服务端回调业务系统的地址，不是浏览器地址；如果 ReachAI 与业务系统不在同一主机或网络命名空间，不能填写 localhost。',
      '- dev proxy / Nginx / Spring Cloud Gateway 三选一即可，但必须明确浏览器最终访问的 token broker 与 chat/embed 地址。',
    ].join('\n')
    const apiManagementScanBlock = [
      'API 管理手动同步边界：',
      '- SDK 接入阶段只完成依赖、registry/project 配置、实例心跳、网关、token broker、前端 Embed 和 Workflow 首次发布；不要把扫描接口同步作为 SDK 接入完成条件。',
      '- SDK 接口扫描与同步统一移动到 ReachAI 控制台的 API 管理：进入项目的 API 管理 / 添加接口，选择 SDK 同步，由平台调用在线业务系统 starter 手动触发现场扫描。',
      '- 手动同步调用方向是 ReachAI 服务端 -> 业务系统 POST /reachai/registry/capabilities/sync -> 业务系统扫描后回传 ReachAI；这不是浏览器请求，也不涉及 CORS。',
      '- 在交付 API 管理 handoff 前，必须检查该 POST 路径能穿过业务网关、Spring Security、Sa-Token、Shiro、自研登录拦截器和 CSRF。只绕过业务登录/JWT/CSRF，不得移除 Starter 对 X-ReachAI-App-Key、Timestamp、Nonce、Signature 的签名校验。',
      '- 如果 reachai.project.base-url 指向网关，必须为 /reachai/registry/** 配置到 Starter 所在业务服务的路由，并透传 X-ReachAI-App-Key / X-ReachAI-Timestamp / X-ReachAI-Nonce / X-ReachAI-Signature。',
      '- reachai.project.base-url 必须从 ReachAI 服务所在网络实际可达；localhost / 127.0.0.1 / ::1 只适用于 ReachAI 与业务系统同机或共享网络命名空间的联调环境。',
      '- reachai.capability.scan-packages / exclude-packages 只是在为后续 API 管理手动同步准备扫描边界；不要添加启动同步开关，也不能在 AI Coding 接入阶段主动调用同步接口。',
      '- 如果本次没有明确要求准备接口元数据，不要为了 SDK 接入去补 @ReachCapability 清单；如确需准备，也只选择低风险查询方法并说明等待 API 管理手动同步。',
    ].join('\n')
    const aiCodingKeyLine =
      deps.aiCodingAccessEnabled.value && aiCodingKey
        ? `AI Coding 请求头：X-ReachAI-AiCoding-Key: ${aiCodingKey}`
        : 'AI Coding 接入秘钥：已关闭，外部 AI 工具无法免登录读取 manifest'
    const aiCodingAuthLine =
      deps.aiCodingAccessEnabled.value && aiCodingKey
        ? '平台 /api/ai-coding/** 请求必须发送 X-ReachAI-AiCoding-Key header，不要把 aiCodingKey 拼进 URL'
        : '平台 AI Coding 接入已关闭；如需外部工具免登录读取 manifest，请先在平台开启 AI Coding 接入秘钥'
    const toolName =
      deps.aiPromptTool.value === 'cursor'
        ? 'Cursor'
        : deps.aiPromptTool.value === 'claude'
          ? 'Claude Code'
          : 'Codex'
    const session = deps.accessSession.value
    const sessionId = session?.sessionId || ''
    const reportUrlPattern = sessionId
      ? `${externalProjectRoot}/access-sessions/${sessionId}/steps/{stepKey}/report`
      : `${externalProjectRoot}/access-sessions/{sessionId}/steps/{stepKey}/report`
    const latestSessionUrl = `${externalProjectRoot}/access-sessions/latest`
    const sessionCheckUrl = sessionId
      ? `${externalProjectRoot}/access-sessions/${sessionId}/checks/run`
      : `${externalProjectRoot}/access-sessions/{sessionId}/checks/run`
    const installHint =
      deps.aiPromptTool.value === 'cursor'
        ? '如果当前工具不支持直接安装 Skill，请下载 zip 后读取其中的 SKILL.md，并把 references/、templates/、scripts/ 作为本次任务的工作资料。'
        : deps.aiPromptTool.value === 'claude'
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
- ${aiCodingAuthLine}
- App Secret 环境变量：${secretEnv}
- AI 接入会话 ID：${sessionId || '请先用 latest session 接口获取'}
- AI 接入会话查询：${latestSessionUrl}
- 步骤进度回传 URL：${reportUrlPattern}
- 平台会话化自检 URL：${sessionCheckUrl}
- Embed Token Broker：${manifest?.embed?.tokenPath || deps.embedTokenPath.value || '/api/reachai/embed-token'}
- ${embedAgentLine}
- ${allowedAgentLine}

    Agent Supervisor target model:
    ${agentSupervisorBlock}

Workflow AI Coding publish contract:
${workflowAiCodingBlock}

Agent provisioning contract:
${agentProvisioningBlock}

Global AI page routing contract:
${globalPageRoutingBlock}

Platform response shapes:
${responseShapeBlock}

Chat SDK apiBase contract:
${apiBaseContractBlock}

Page Action result boundary:
${pageActionResultBlock}

SDK artifact resolution contract:
${dependencyResolutionBlock}

${annotationBoundaryBlock}

${readinessBlock}

${gatewayChecklistBlock}

${localTopologyBlock}

${apiManagementScanBlock}

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
4. 识别业务代码主包名，作为后续 API 管理手动 SDK 同步的扫描边界；不要把业务系统依赖的框架包、平台包、第三方包接口纳入 ReachAI。若启动类根包过宽，请优先选择实际业务包。
5. 在业务系统配置中增加 reachai.registry、reachai.project、reachai.capability 配置，并用 ${secretEnv} 引用密钥；不要添加能力启动同步配置。若准备后续 API 管理手动同步，再配置 reachai.capability.scan-packages 与 reachai.capability.exclude-packages。
6. 本次 SDK 接入不要主动同步接口或要求项目接口目录已有数据。只有当用户明确要求准备接口元数据时，才根据现有 Controller / Service 选择 1-2 个低风险查询能力补充 @ReachCapability / @ReachParam；@ReachOutput 只用于返回 DTO 字段，不要写在方法上。
7. 检查业务系统是否有统一网关模块、Spring Cloud Gateway 配置、Nginx 配置或前端 dev proxy。若 reachai.project.base-url 指向网关，除能力调用路由外还必须把 /reachai/registry/** 转发到 Starter 所在业务服务，并透传 X-ReachAI-App-Key、X-ReachAI-Timestamp、X-ReachAI-Nonce、X-ReachAI-Signature；若没有网关，必须在计划里说明实际回调地址，不要把 secret 下沉到浏览器。
8. 在业务网关或服务端 token broker 中实现前端获取 embed token 的接口，默认路径可用 ${deps.embedTokenPath.value || '/api/reachai/embed-token'}。该接口必须从业务登录态解析当前用户，映射 principal.externalUserId，使用项目 appKey/appSecret 服务端签名调用 ReachAI 的 POST /api/embed/token/exchange，并按短期 token 策略缓存；appSecret 仍只能来自 ${secretEnv} 或密钥管理器。ReachAI token exchange 返回统一 ApiResult：{code:200,message:"success",data:{token,expiresIn,sessionHint}}，broker 必须读取 data.token / data.expiresIn，可兼容历史顶层 token / expiresIn，但不能只读取顶层 token，也不能把 helper 的一条路径误写成 token.data.token。
9. 在业务前端接入 ReachAI Chat Embed：增加配置、组件或页面入口；你必须在接入阶段从本机或服务端 POST Agent provisioning API（${provisionAgentUrl}）。该接口会创建/复用项目页面副驾驶 Agent，并发布 ACTIVE AgentScope Supervisor 配置；不得创建默认占位 Workflow。确认返回的裸 JSON 中 supervisorConfig.status=ACTIVE 后，将 agent.keySlug 写入业务前端配置作为 agentId（不是 data.agent.keySlug，也不是让用户手工填写 Agent）。运行时浏览器不得调用 provisioning API，不得保存 aiCodingKey。使用 @reachai/embed-chat；createEafChat 的 apiBase 可以是 ReachAI 平台 origin（例如 ${platformUrl}），经业务网关时可配置 embedPathPrefix=/api/reachai/embed，或把 apiBase 直接设为相对代理根 /api/reachai/embed。让前端通过业务网关 token broker 获取 embed token，再用 token 调用 ReachAI /api/embed/chat/sessions 与消息接口。前端不得保存 appSecret，不得使用 pageRegistry.appSecret 自动上报密钥。
10. 明确区分两类 Authorization：请求 ${deps.embedTokenPath.value || '/api/reachai/embed-token'} 时使用业务系统登录 token；请求 /api/reachai/embed/**、/api/embed/chat/sessions 或消息接口时只能使用 ReachAI 返回的短期 embed token。不要把业务登录 token 当作 embed token 传给 ReachAI Chat API。
11. 修改业务网关白名单 / 安全链：${deps.embedTokenPath.value || '/api/reachai/embed-token'} 继续使用业务登录 token；但 /api/reachai/embed/** 是 ReachAI embed token 代理流量，必须绕过业务 OAuth/JWT 认证并原样透传 Authorization: Bearer <embedToken> 给 ReachAI。
12. 如果业务网关使用 Spring Security WebFlux / OAuth2 Resource Server，不能只写 .pathMatchers("/api/reachai/embed/**").permitAll()；Resource Server 仍可能先解析 Authorization: Bearer <embedToken> 并按业务 JWT 失败返回 401。必须为 /api/reachai/embed/** 添加更高优先级的独立 SecurityWebFilterChain / securityMatcher，并且该链不要启用业务 oauth2ResourceServer()。
13. 专门检查现有白名单/匿名路径过滤器是否会清空 JWT 请求头，例如 IgnoreUrlsRemoveJwtFilter、RemoveJwtFilter、RemoveRequestHeader=Authorization，或 mutate().header("Authorization", "") 这类代码。/api/reachai/embed/** 对业务登录认证是匿名，但对 ReachAI 来说必须保留 Authorization: Bearer <embedToken>，禁止在该路径清空、改写或消费 Authorization。
    同时检查 POST /reachai/registry/capabilities/sync：Spring Security、Sa-Token、Shiro、自研登录拦截器和 CSRF 必须允许 ReachAI 的服务端签名请求到达 Starter Controller；不得把它改成无鉴权接口，Starter 仍负责校验 X-ReachAI-* 注册签名。
14. 如果业务系统用 Spring Cloud Gateway 代理 /api/reachai/embed/** 到 ReachAI /api/embed/**，检查是否会同时由网关和 ReachAI 返回 CORS 头；若会重复，请在该路由增加类似 DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST 的响应头去重配置，避免浏览器把真实 401/500 遮蔽成 status 0 Unknown Error。
15. 业务前端缓存 embed token 时必须按 expiresIn 提前失效；如果创建 session 或发送消息返回 embed token is expired，应清空缓存、重新调用 token broker 获取新 embed token 并重试一次。
16. 保证网关转发时透传 X-ReachAI-Invocation-Token、X-ReachAI-Trace-Id、X-ReachAI-Run-Id，以及业务身份所需的 Authorization / 用户上下文头；业务接口不能只凭普通 X-ReachAI-* 上下文头放行。
17. 分别运行业务后端、网关和业务前端的最小可行编译/构建/测试。
18. 完成默认 Workflow 绘制和草稿保存后，必须读取 /api/workflows/{workflowId}/ai-coding/versions 确认 releaseValidation.valid=true；随后调用 ${workflowAiCodingPublishUrl} 做首次发布，创建 ACTIVE workflow version。不要停在“只保存草稿，等待人工发布”。如果发布校验失败，修复 GraphSpec 后再发布。
19. 如果业务系统、网关和 ReachAI 服务可访问，调用“平台会话化自检 URL”做接入自检；请求必须发送 X-ReachAI-AiCoding-Key header，URL 不要拼 aiCodingKey；body 中带 gatewayBaseUrl=${deps.gatewayBaseUrl.value || 'http://localhost:8080'} 与 embedTokenPath=${deps.embedTokenPath.value || '/api/reachai/embed-token'}。如果只能拿到 manifest.endpoints.sdkAccessCheckUrl 且该接口返回 platform login required，请回到会话化自检 URL；否则说明缺少的本地前置条件。
20. 最后给出修改文件清单、验证结果、首次发布的 workflowId/version/versionId、仍需人工配置的密钥或环境变量。

进度回传要求：
- 每完成或卡住一个关键步骤，请 POST 到“步骤进度回传 URL”，把 {stepKey} 替换为下列 key 之一：project-manifest、backend-sdk、reachai-config、api-management-handoff、gateway-route、embed-token-broker、gateway-whitelist、frontend-embed、connectivity-check、handoff-summary。
- 请求必须发送 X-ReachAI-AiCoding-Key header；不要把 aiCodingKey 拼进 URL。
- 请求体格式：{"status":"PASS|WARN|FAIL|RUNNING","message":"一句话说明","files":["相对路径"],"evidence":{"command":"执行过的命令","exitCode":0},"reportedBy":"${toolName}"}。
- 如果你不能访问平台接口，请在最终总结里说明无法回传；如果可以访问，不要只在聊天里报告进度。
- 做最终自检时优先调用“平台会话化自检 URL”，它会把检查结果同步写入当前会话。

业务网关要求：
- 网关需要暴露前端可调用的 embed token broker，例如 GET ${manifest?.embed?.tokenPath || deps.embedTokenPath.value || '/api/reachai/embed-token'}?projectCode=${code}&agentId=<provisionedAgentKeySlug>&pageInstanceId=...&route=...&origin=...。
- token broker 服务端再调用 ReachAI POST /api/embed/token/exchange，请求体至少包含 projectCode、agentId、pageInstanceId、route、origin、principal.externalUserId。
- ReachAI token exchange 响应是统一 ApiResult，成功样例为 {"code":200,"message":"success","data":{"token":"jwt","expiresIn":600,"sessionHint":{}}}。token broker 必须从 data.token / data.expiresIn 取值，并用该样例做 mock 单测或本地断言；允许兼容顶层 token / expiresIn，但禁止只读顶层 token。
- 网关需要把 /api/reachai/embed/** 配成匿名代理或独立安全链，不能用业务 OAuth/JWT 校验 ReachAI embed token；如果有全局认证过滤器，也要跳过该路径。
- 如果是 Spring Security WebFlux / OAuth2 Resource Server，permitAll 不是充分条件；必须给 /api/reachai/embed/** 单独配置高优先级 SecurityWebFilterChain / securityMatcher，且不要在这条链上启用业务 oauth2ResourceServer()，否则 embed token 会被当作业务 JWT 提前 401。
- 必须检查白名单/匿名路径过滤器是否会删除 Authorization，例如 IgnoreUrlsRemoveJwtFilter、RemoveJwtFilter、RemoveRequestHeader=Authorization 或 mutate().header("Authorization", "")。这些清头逻辑不能作用于 /api/reachai/embed/**，否则 ReachAI 会收到空 Authorization 并返回 Authorization Bearer embed token is required。
- Spring Cloud Gateway 代理 /api/reachai/embed/** 时，若网关和 ReachAI 都会写 CORS 响应头，请在该路由配置 DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST，避免重复 CORS 头导致浏览器显示 status 0 Unknown Error。
- 如果项目已有 Spring Cloud Gateway 路由，请补充到对应 application.yml / bootstrap.yml / 配置中心文件；如果是 Nginx 或前端代理，请补充到实际使用的网关配置。

业务前端要求：
- 在真实业务页面接入对话入口，不要只写 README 示例。
- 浏览器运行时代码不得 fetch /api/ai-coding/projects/**（含 onboarding-manifest、agents/provision、access-sessions）；不得在 environment.ts、.env、window.__env 或前端配置中保存 aiCodingKey、provisionAgentUrl、appSecret。
- 运行时前端只保存 agentId=<provisioned agent.keySlug>；provisioning 只能在接入阶段由 AI 工具、本机 shell 或服务端执行。
- tokenProvider 只能请求业务网关 token broker，不能在浏览器拼 appSecret、registry 签名或项目级密钥。
- tokenProvider 请求业务网关 token broker 时使用业务登录 token；创建 ReachAI session 和发送消息时使用 token broker 返回的短期 embed token，两者不能混用。
- 前端缓存 embed token 必须按 expiresIn 提前失效；遇到 embed token is expired 时清缓存、重新获取并重试一次。
- pageKey、pageInstanceId、route、origin 要由前端运行时生成，并在 token broker、session create、Page Action 三处保持一致，便于 ReachAI 做会话隔离、Workflow 路由和页面动作回传。

API 管理手动同步准备要求：
- 明确记录调用方向：ReachAI 服务端 POST 业务系统 /reachai/registry/capabilities/sync，业务系统再把扫描结果同步回 ReachAI。
- 确认 reachai.project.base-url + context-path 是 ReachAI 服务端可达地址；localhost 仅限同机或共享网络命名空间。
- 如果经过网关，补充 /reachai/registry/** 到 Starter 所在业务服务的路由并透传 X-ReachAI-App-Key、X-ReachAI-Timestamp、X-ReachAI-Nonce、X-ReachAI-Signature。
- 对 POST /reachai/registry/capabilities/sync 绕过业务登录/JWT 与 CSRF，但保留 Starter 签名校验；不要把该路径做成无鉴权公网接口。
- 如果本次需要为后续 API 管理手动同步准备扫描边界，必须先从启动类、业务 Controller / Service 包、Maven 模块名中推断业务代码主包，例如 com.company.order 或 com.xxx.biz。
- reachai.capability.scan-packages 只填写业务代码包；不要填写 org.springframework、springfox、org.springdoc、com.baomidou、框架基座包、通用平台包或 SDK 包。
- reachai.capability.exclude-packages 至少排除 org.springframework、springfox、org.springdoc、com.enterprise.ai.reach；如果项目有 hussar、framework、common-web、platform 等框架包，也要排除。
- 如果无法可靠判断业务包，先在计划里列出候选包并等待我确认，不要默认扫描整个根包。
- 配置完成后不要在接入阶段调用同步接口；由用户在 ReachAI API 管理中手动触发 SDK 同步。

请先输出你识别到的项目结构和接入计划，等我确认后再改代码。`
  })

  return {
    starterDependencySnippet,
    starterApplicationSnippet,
    highlightedStarterDependencySnippet,
    highlightedStarterApplicationSnippet,
    gatewaySnippet,
    highlightedGatewaySnippet,
    frontendSnippet,
    highlightedFrontendSnippet,
    aiOnboardingPrompt,
  }
}

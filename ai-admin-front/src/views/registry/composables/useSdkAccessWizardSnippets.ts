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
    context-path: ${deps.project.value?.contextPath || ''}
    environment: ${deps.project.value?.environment || 'dev'}
  capability:
    scan-beans: true
    sync-on-startup: true`)

  const highlightedStarterDependencySnippet = computed(() => highlightXmlCode(starterDependencySnippet.value))
  const highlightedStarterApplicationSnippet = computed(() => highlightYamlCode(starterApplicationSnippet.value))

  const gatewaySnippet = computed(() => `spring:
  cloud:
    gateway:
      routes:
        - id: ${deps.project.value?.projectCode || deps.projectCode.value}-reachai
          uri: ${deps.project.value?.baseUrl || 'http://localhost:18089'}
          predicates:
            - Path=/reachai/capabilities/**
          filters:
            - PreserveHostHeader

# 必须透传：
# X-ReachAI-Invocation-Token
# X-ReachAI-Trace-Id / X-ReachAI-Run-Id
# 业务用户身份头或当前登录态`)

  const frontendSnippet = computed(() => {
    const manifest = deps.aiOnboardingManifest.value
    const project = deps.project.value
    const code = manifest?.project.projectCode || project?.projectCode || deps.projectCode.value
    const platformUrl = manifest?.sdk?.config?.registryUrl || window.location.origin
    const expectedKeySlug = manifest?.agentProvisioning?.defaultKeySlug
      || manifest?.agentWorkflow?.globalAgentKeySlug
      || manifest?.embed?.defaultAgentKeySlug
      || `${code}-page-copilot`

    return `import { createEafChat } from '@reachai/frontend-sdk'

const pageInstanceId = sessionStorage.getItem('reachaiPageInstanceId') || crypto.randomUUID()
sessionStorage.setItem('reachaiPageInstanceId', pageInstanceId)
const pageKey = '<current-page-key>'

// Provisioning runs once during onboarding from AI tool / local shell / server-side integration.
// Browser runtime must NOT call /api/ai-coding/projects/** onboarding/provisioning/session APIs or store project signing secrets in front-end config.
// Use only the provisioned bare JSON agent.keySlug below as agentId.
const provisionedAgentKeySlug = '${expectedKeySlug}'

createEafChat({
  mount: '#reachai-chat',
  apiBase: '${platformUrl}',
  agentId: provisionedAgentKeySlug,
  page: {
    pageKey,
    name: '<current-page-name>',
    routePattern: window.location.pathname,
  },
  tokenProvider: async () => {
    const query = new URLSearchParams({
      projectCode: '${code}',
      agentId: provisionedAgentKeySlug,
      pageKey,
      pageInstanceId,
      route: window.location.pathname,
      origin: window.location.origin
    })
    const payload = await fetch('${deps.embedTokenPath.value || '/api/reachai/embed-token'}?' + query).then((res) => res.json())
    if (payload.code && payload.code !== 200 && payload.code !== 0) {
      throw new Error(payload.message || 'embed token broker failed')
    }
    const token = payload.data?.token || payload.token
    if (!token) throw new Error('ReachAI embed token missing')
    return token
  }
})

// apiBase 必须是 ReachAI 平台 origin（SDK 会请求 \${apiBase}/api/embed/**）。
// 不要把 apiBase 写成 '/api/reachai/embed'。
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
    const agentWorkflow = manifest?.agentWorkflow
    const workflowAiCoding = agentWorkflow?.workflowAiCoding
    const globalAgentKeySlug = agentProvisioning?.defaultKeySlug || agentWorkflow?.globalAgentKeySlug || embedAgentId || `${code}-page-copilot`
    const agentProvisioningBlock = [
      `- Agent provisioning model: ${agentProvisioning?.model || 'agent-provisioning.v1'}`,
      `- Provisioning API: ${provisionAgentUrl}`,
      `- Default Agent kind: ${agentProvisioning?.defaultAgentKind || 'PAGE_COPILOT'}`,
      `- Default Agent keySlug: ${globalAgentKeySlug}`,
      `- Idempotent: ${agentProvisioning?.idempotent === false ? 'false' : 'true'}`,
      `- Creates default Workflow: ${agentProvisioning?.createsDefaultWorkflow === false ? 'false' : 'true'}`,
      `- Creates default binding: ${agentProvisioning?.createsDefaultBinding === false ? 'false' : 'true'}`,
      '- Cursor must POST the provisioning API before frontend embed work and use response.agent.keySlug as the agentId (bare JSON, not data.agent.keySlug).',
      '- Do not ask the business user to manually create, choose, or configure the page copilot Agent during SDK onboarding.',
    ].join('\n')
    const agentWorkflowBlock = [
      `- Agent/Workflow model: ${agentWorkflow?.model || 'agent-workflow.decoupled.v1'}`,
      `- Page copilot agent keySlug: ${globalAgentKeySlug}`,
      `- Page copilot agent kind: ${agentWorkflow?.globalAgentKind || agentProvisioning?.defaultAgentKind || 'PAGE_COPILOT'}`,
      `- Workflow storage target: ${agentWorkflow?.workflowStorage || 'ai_workflow'}`,
      `- SDK graph workflow type: ${agentWorkflow?.sdkGraphWorkflowType || 'SDK_GRAPH'}`,
      `- Binding strategy: ${agentWorkflow?.bindingStrategy || 'Bind page/action/intent workflows to the page copilot Agent.'}`,
      `- Agents API: ${agentWorkflow?.endpoints?.agentsUrl || `${platformUrl}/api/agents`}`,
      `- Workflows API: ${agentWorkflow?.endpoints?.workflowsUrl || `${platformUrl}/api/workflows`}`,
      `- Bindings API: ${agentWorkflow?.endpoints?.globalAgentBindingsUrl || `${platformUrl}/api/agents/${globalAgentKeySlug}/workflow-bindings`}`,
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
      '- ReachAI resolves the runnable Workflow from ai_agent_workflow_binding by current pageKey/action/intent.',
      '- Do not create one floating AI button per workflow. Page assistants are page workflows bound to the page copilot Agent entry.'
    ].join('\n')
    const responseShapeBlock = [
      '平台响应形态（不要默认所有接口都读 data. 或都读顶层）：',
      '- Embed 对外 API（token exchange、sessions、messages、page-actions）：ApiResult 包装，业务字段读 data.token / data.sessionId / data.answer',
      '- POST .../agents/provision：裸 JSON，读 agent.keySlug（不是 data.agent.keySlug）',
      '- access-sessions / sdk-access-check / onboarding-manifest：裸 JSON，读顶层 sessionId、overallStatus、project/embed 等',
      '- Chat 回复禁止把顶层 message:"success" 当助手文本；助手自然语言读 data.answer',
      '- Chat 返回 data.metadata.pageActionQueue 时必须逐个执行页面动作并回传 /api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result，不能只显示 data.answer',
      '- Embed SSE 以 message.completed 结束，没有 done 事件',
    ].join('\n')
    const apiBaseContractBlock = [
      'Chat SDK apiBase 契约（eafChat 请求 ${apiBase}/api/embed/chat/sessions）：',
      `- 直连 ReachAI：apiBase = ReachAI 平台 origin，例如 ${platformUrl}`,
      '- 经业务网关：浏览器必须能访问 /api/embed/** 并转发到 ReachAI /api/embed/**',
      '- 错误：apiBase: "/api/reachai/embed" 会变成 /api/reachai/embed/api/embed/...',
      '- token broker 路径（如 /api/reachai/embed-token）与 Chat apiBase 是两套地址，不要混用',
    ].join('\n')
    const pageActionResultBlock = [
      'Page Action 回传边界：',
      '- data.metadata.pageActionQueue 是优先级最高的页面动作队列；data.uiRequest.extension.pageActionRequest 是兼容单动作指令',
      '- 自定义聊天服务必须通过当前页面 bridge/SDK 执行 actionKey，并按 requestId POST /api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result',
      '- bridge 内部可有 FAILED/CANCELLED/TIMEOUT 等状态；回传 Embed API 时当前 DTO 的 error 是字符串，status 推荐 SUCCESS 或平台可接受的字符串',
      '- 页面助手 bridge 结构化 error 需在 API 边界映射为字符串，不要直接把对象写入 Embed PageActionResultRequest.error',
    ].join('\n')
    const dependencyResolutionBlock = [
      '- ReachAI 平台地址、Skill 包地址和 manifest 地址只用于读取接入资料、回传进度和自检；它们不是 Maven 仓库、npm registry 或 SDK 文件服务器。',
      '- 禁止把 ReachAI 平台 baseUrl 配成 Maven repository，禁止请求 /repository/**、/maven/**、/repository/maven/**、/api/embed/sdk 或 /npm/** 这类猜测路径。',
      '- Java 依赖必须来自业务仓库已有的公司 Maven 仓库、已发布的 ReachAI Maven 仓库，或先在 ReachAI 仓库执行 mvn -pl reachai-spring-boot2-starter -am install -DskipTests 后使用本地 Maven 仓库。',
      '- 如果 reachai-capability-sdk 或 reachai-spring-boot2-starter 无法解析，请停止并报告需要安装/发布 Maven 产物，不要虚构下载 URL。',
      '- 前端 SDK 同理：若业务仓库没有可用 npm 包或本地构建产物，不要从 ReachAI 平台猜测 /api/embed/sdk 或 /npm/**；应按 manifest 的 embed HTTP 合同实现最小调用，或报告需要提供正式前端 SDK 包。',
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
      '- RUNTIME_READY：业务服务已带 REACHAI_REGISTRY_APP_SECRET 启动，SDK 实例在线，API 资产已同步。',
      '- E2E_READY：选定 API 资产完成一次真实调用，或嵌入式 Chat/token broker 链路真实打通。',
      '- CODE_READY 通过但 RUNTIME_READY/E2E_READY 为 WARN，通常表示服务未启动、心跳未上报、API 未同步或未选择真实调用参数，不等于代码接入失败。',
    ].join('\n')
    const gatewayChecklistBlock = [
      '网关接入必查 5 项：',
      '1. /api/reachai/embed-token 使用业务登录 token，只在服务端签名调用 ReachAI POST /api/embed/token/exchange。',
      '2. /api/reachai/embed/** 代理 ReachAI /api/embed/**，必须原样透传 Authorization: Bearer <embedToken>。',
      '3. Spring Security WebFlux / OAuth2 Resource Server 需要独立高优先级 SecurityWebFilterChain；只写 permitAll 不充分。',
      '4. IgnoreUrlsRemoveJwtFilter / RemoveJwtFilter / RemoveRequestHeader=Authorization / mutate().header("Authorization", "") 不能作用于 /api/reachai/embed/**。',
      '5. Spring Cloud Gateway 代理时如网关和 ReachAI 都写 CORS 头，配置 DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST。',
    ].join('\n')
    const localTopologyBlock = [
      '本地联调拓扑：',
      `- 前端 :9200 -> 网关 :8080（${deps.embedTokenPath.value || '/api/reachai/embed-token'} + /api/reachai/embed/**）-> ReachAI :18603（/api/embed/**）。`,
      `- Chat apiBase 默认是 ReachAI 平台 origin（例如 ${platformUrl}）；gatewayBaseUrl 默认是业务网关入口（例如 ${deps.gatewayBaseUrl.value || 'http://localhost:8080'}），两者可能不同，不能互相替代。`,
      '- dev proxy / Nginx / Spring Cloud Gateway 三选一即可，但必须明确浏览器最终访问的 token broker 与 chat/embed 地址。',
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

Agent/Workflow target model:
${agentWorkflowBlock}

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
6. 根据现有 Controller / Service 代码，优先选择 1-2 个低风险查询能力，补充 @ReachCapability / @ReachParam；@ReachOutput 只用于返回 DTO 字段，不要写在方法上。
7. 检查业务系统是否有统一网关模块、Spring Cloud Gateway 配置、Nginx 配置或前端 dev proxy。若有网关，必须补上 ReachAI 相关路由；若没有网关，必须在计划里说明缺口，不要把 secret 下沉到浏览器。
8. 在业务网关或服务端 token broker 中实现前端获取 embed token 的接口，默认路径可用 ${deps.embedTokenPath.value || '/api/reachai/embed-token'}。该接口必须从业务登录态解析当前用户，映射 principal.externalUserId，使用项目 appKey/appSecret 服务端签名调用 ReachAI 的 POST /api/embed/token/exchange，并按短期 token 策略缓存；appSecret 仍只能来自 ${secretEnv} 或密钥管理器。ReachAI token exchange 返回统一 ApiResult：{code:200,message:"success",data:{token,expiresIn,sessionHint}}，broker 必须读取 data.token / data.expiresIn，可兼容历史顶层 token / expiresIn，但不能只读取顶层 token，也不能把 helper 的一条路径误写成 token.data.token。
9. 在业务前端接入 ReachAI Chat Embed：增加配置、组件或页面入口；你必须在接入阶段从本机或服务端 POST Agent provisioning API（${provisionAgentUrl}），并将返回的裸 JSON 字段 agent.keySlug 写入业务前端配置作为 agentId（不是 data.agent.keySlug，也不是让用户手工填写 Agent）。运行时浏览器不得调用 provisioning API，不得保存 aiCodingKey。createEafChat 的 apiBase 使用 ReachAI 平台 origin（例如 ${platformUrl}），SDK 会请求 ${platformUrl}/api/embed/**；不要把 apiBase 写成 /api/reachai/embed。让前端通过业务网关 token broker 获取 embed token，再用 token 调用 ReachAI /api/embed/chat/sessions 与消息接口。前端不得保存 appSecret，不得使用 pageRegistry.appSecret 自动上报密钥。
10. 明确区分两类 Authorization：请求 ${deps.embedTokenPath.value || '/api/reachai/embed-token'} 时使用业务系统登录 token；请求 /api/reachai/embed/**、/api/embed/chat/sessions 或消息接口时只能使用 ReachAI 返回的短期 embed token。不要把业务登录 token 当作 embed token 传给 ReachAI Chat API。
11. 修改业务网关白名单 / 安全链：${deps.embedTokenPath.value || '/api/reachai/embed-token'} 继续使用业务登录 token；但 /api/reachai/embed/** 是 ReachAI embed token 代理流量，必须绕过业务 OAuth/JWT 认证并原样透传 Authorization: Bearer <embedToken> 给 ReachAI。
12. 如果业务网关使用 Spring Security WebFlux / OAuth2 Resource Server，不能只写 .pathMatchers("/api/reachai/embed/**").permitAll()；Resource Server 仍可能先解析 Authorization: Bearer <embedToken> 并按业务 JWT 失败返回 401。必须为 /api/reachai/embed/** 添加更高优先级的独立 SecurityWebFilterChain / securityMatcher，并且该链不要启用业务 oauth2ResourceServer()。
13. 专门检查现有白名单/匿名路径过滤器是否会清空 JWT 请求头，例如 IgnoreUrlsRemoveJwtFilter、RemoveJwtFilter、RemoveRequestHeader=Authorization，或 mutate().header("Authorization", "") 这类代码。/api/reachai/embed/** 对业务登录认证是匿名，但对 ReachAI 来说必须保留 Authorization: Bearer <embedToken>，禁止在该路径清空、改写或消费 Authorization。
14. 如果业务系统用 Spring Cloud Gateway 代理 /api/reachai/embed/** 到 ReachAI /api/embed/**，检查是否会同时由网关和 ReachAI 返回 CORS 头；若会重复，请在该路由增加类似 DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST 的响应头去重配置，避免浏览器把真实 401/500 遮蔽成 status 0 Unknown Error。
15. 业务前端缓存 embed token 时必须按 expiresIn 提前失效；如果创建 session 或发送消息返回 embed token is expired，应清空缓存、重新调用 token broker 获取新 embed token 并重试一次。
16. 保证网关转发时透传 X-ReachAI-Invocation-Token、X-ReachAI-Trace-Id、X-ReachAI-Run-Id，以及业务身份所需的 Authorization / 用户上下文头；业务接口不能只凭普通 X-ReachAI-* 上下文头放行。
17. 分别运行业务后端、网关和业务前端的最小可行编译/构建/测试。
18. 完成默认 Workflow 绘制和草稿保存后，必须读取 /api/workflows/{workflowId}/ai-coding/versions 确认 releaseValidation.valid=true；随后调用 ${workflowAiCodingPublishUrl} 做首次发布，创建 ACTIVE workflow version。不要停在“只保存草稿，等待人工发布”。如果发布校验失败，修复 GraphSpec 后再发布。
19. 如果业务系统、网关和 ReachAI 服务可访问，调用“平台会话化自检 URL”做接入自检；请求必须发送 X-ReachAI-AiCoding-Key header，URL 不要拼 aiCodingKey；body 中带 gatewayBaseUrl=${deps.gatewayBaseUrl.value || 'http://localhost:8080'} 与 embedTokenPath=${deps.embedTokenPath.value || '/api/reachai/embed-token'}。如果只能拿到 manifest.endpoints.sdkAccessCheckUrl 且该接口返回 platform login required，请回到会话化自检 URL；否则说明缺少的本地前置条件。
20. 最后给出修改文件清单、验证结果、首次发布的 workflowId/version/versionId、仍需人工配置的密钥或环境变量。

进度回传要求：
- 每完成或卡住一个关键步骤，请 POST 到“步骤进度回传 URL”，把 {stepKey} 替换为下列 key 之一：project-manifest、backend-sdk、reachai-config、capability-scan、gateway-route、embed-token-broker、gateway-whitelist、frontend-embed、connectivity-check、handoff-summary。
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

扫描边界要求：
- 必须先从启动类、业务 Controller / Service 包、Maven 模块名中推断业务代码主包，例如 com.company.order 或 com.xxx.biz。
- reachai.capability.scan-packages 只填写业务代码包；不要填写 org.springframework、springfox、org.springdoc、com.baomidou、框架基座包、通用平台包或 SDK 包。
- reachai.capability.exclude-packages 至少排除 org.springframework、springfox、org.springdoc、com.enterprise.ai.reach；如果项目有 hussar、framework、common-web、platform 等框架包，也要排除。
- 如果无法可靠判断业务包，先在计划里列出候选包并等待我确认，不要默认扫描整个根包。

请先输出你识别到的项目结构和接入计划，等我确认后再改代码。`
  })

  return {
    starterDependencySnippet,
    starterApplicationSnippet,
    highlightedStarterDependencySnippet,
    highlightedStarterApplicationSnippet,
    gatewaySnippet,
    frontendSnippet,
    highlightedFrontendSnippet,
    aiOnboardingPrompt,
  }
}

import { computed, type Ref } from 'vue'
import type {
  AiOnboardingManifest,
  ScanProject,
} from '@/types/scanProject'
import {
  highlightJsCode,
  highlightYamlCode,
} from '@/views/registry/composables/sdkAccessWizardCodeHighlight'

export interface UseSdkAccessWizardSnippetsDeps {
  projectCode: Ref<string>
  project: Ref<ScanProject | null>
  aiOnboardingManifest: Ref<AiOnboardingManifest | null>
  gatewayBaseUrl: Ref<string>
  embedTokenPath: Ref<string>
}

export function useSdkAccessWizardSnippets(
  deps: UseSdkAccessWizardSnippetsDeps,
) {
  const registryPublicUrl = computed(() =>
    deps.aiOnboardingManifest.value?.sdk?.config?.registryUrl
    || (typeof window === 'undefined' ? '' : window.location.origin)
    || 'http://localhost:18603')

  const starterApplicationSnippet = computed(() => `reachai:
  registry:
    enabled: true
    url: \${REACHAI_REGISTRY_URL:${registryPublicUrl.value}}
    app-key: ${JSON.stringify(deps.project.value?.registryAppKey || 'your-app-key')}
    app-secret: \${REACHAI_REGISTRY_APP_SECRET}
  project:
    code: ${JSON.stringify(deps.project.value?.projectCode || deps.projectCode.value)}
    name: ${JSON.stringify(deps.project.value?.name || 'your-service-name')}
    base-url: ${JSON.stringify(deps.project.value?.baseUrl || 'https://business.example.com')}
    # 必须填写 ReachAI 服务端实际可访问的业务系统地址；localhost 仅适用于同机、同网络命名空间联调。
    context-path: ${JSON.stringify(deps.project.value?.contextPath || '')}
    environment: ${JSON.stringify(deps.project.value?.environment || 'dev')}
  capability:
    scan-beans: true
    scan-mode: ANNOTATED_ONLY
    # ANNOTATED_ONLY 只同步 @ReachCapability 业务方法；要把 Controller 接口同步为 HTTP API，
    # 改为 ALL_CONTROLLERS 并用 scan-packages 限定包名。启动只注册项目与实例，同步在项目内手动触发。`)

  const highlightedStarterApplicationSnippet = computed(() =>
    highlightYamlCode(starterApplicationSnippet.value),
  )

  const gatewaySnippet = computed(() => {
    const code =
      deps.project.value?.projectCode
      || deps.projectCode.value
      || 'your-project'
    const platformUrl =
      deps.aiOnboardingManifest.value?.sdk?.config?.registryUrl
      || 'http://localhost:18603'

    return `spring:
  cloud:
    gateway:
      routes:
        # ReachAI -> business Starter callbacks and capability invocations.
        - id: ${code}-reachai-business
          uri: \${REACHAI_BUSINESS_SERVICE_URL:http://localhost:18089}
          predicates:
            - Path=/reachai/capabilities/**,/reachai/registry/**
          filters:
            - PreserveHostHeader

# Browser -> business Token Broker. Keep the normal business login/JWT chain.
        - id: ${code}-reachai-token-broker
          uri: \${REACHAI_BUSINESS_SERVICE_URL:http://localhost:18089}
          predicates:
            - Path=/reachai/embed/token
            - Method=POST

# Browser -> ReachAI Embed API. Preserve the ReachAI Embed Token Authorization header.
        - id: ${code}-reachai-embed
          uri: \${REACHAI_PLATFORM_URL:${platformUrl}}
          predicates:
            - Path=/api/reachai/embed/**
          filters:
            - RewritePath=/api/reachai/embed/?(?<segment>.*), /api/embed/$\\{segment}
            - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST

# /reachai/registry/** and /reachai/capabilities/** must bypass ordinary business
# login/JWT filters and CSRF so traffic reaches the Starter. They are not
# unauthenticated: the Starter validates the registry signature or invocation token.
#
# /api/reachai/embed/** needs an independent security chain that does not run the
# business OAuth2 resource server. permitAll() alone is not sufficient.
# It is anonymous only to business login validation; do not remove Authorization.
# /reachai/embed/token remains on the normal business login and CSRF chain.
#
# Starter callback/invocation traffic must preserve:
# X-ReachAI-Invocation-Token
# X-ReachAI-Trace-Id / X-ReachAI-Run-Id
# X-ReachAI-App-Key / X-ReachAI-Timestamp / X-ReachAI-Nonce / X-ReachAI-Signature
# 业务用户身份头或当前登录态`
  })
  const highlightedGatewaySnippet = computed(() =>
    highlightYamlCode(gatewaySnippet.value),
  )

  const frontendSnippet = computed(() => {
    const manifest = deps.aiOnboardingManifest.value
    const currentProject = deps.project.value
    const code =
      manifest?.project.projectCode
      || currentProject?.projectCode
      || deps.projectCode.value
    const runtimeOrigin =
      typeof window === 'undefined' ? '' : window.location.origin
    const configuredGatewayBase = deps.gatewayBaseUrl.value.trim()
      .replace(/\/+$/, '')
    const chatApiBase =
      configuredGatewayBase
      || manifest?.sdk?.config?.registryUrl
      || runtimeOrigin
      || 'http://localhost:18603'
    const embedPathPrefix = configuredGatewayBase
      ? '/api/reachai/embed'
      : '/api/embed'
    const configuredTokenBrokerPath =
      deps.embedTokenPath.value.trim() || '/reachai/embed/token'
    const tokenBrokerUrl =
      /^https?:\/\//i.test(configuredTokenBrokerPath)
        ? configuredTokenBrokerPath
        : configuredGatewayBase
          ? `${configuredGatewayBase}/${configuredTokenBrokerPath.replace(/^\/+/, '')}`
          : configuredTokenBrokerPath
    const expectedKeySlug =
      manifest?.agentProvisioning?.defaultKeySlug
      || manifest?.agentSupervisor?.globalAgentKeySlug
      || manifest?.embed?.defaultAgentKeySlug
      || `${code}-page-copilot`

    return `import { createEafChat, createEafPageBridge } from '@reachai/embed-chat'
import '@reachai/embed-chat/style.css'

const pageKey = '<current-page-key>'
const route = window.location.pathname || '/'
const pageBridge = createEafPageBridge({ route })
// 将此值核对为实际已发布且允许嵌入的 Agent 标识。
const agentId = ${JSON.stringify(expectedKeySlug)}

const chat = await createEafChat({
  mount: '#reachai-chat',
  apiBase: ${JSON.stringify(chatApiBase)},
  embedPathPrefix: '${embedPathPrefix}',
  agentId,
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
    if (!tokenContext?.pageInstanceId) {
      throw new Error('ReachAI page identity is unavailable')
    }
    // 按宿主要求使用已有登录请求客户端，并附上需要的 CSRF 请求头。
    const response = await fetch(${JSON.stringify(tokenBrokerUrl)}, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      signal: tokenContext.signal,
      body: JSON.stringify({
        agentId,
        pageKey: tokenContext.pageKey,
        pageInstanceId: tokenContext.pageInstanceId,
        route: tokenContext.route,
        origin: tokenContext.origin,
      }),
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
  },
})

// 在宿主组件卸载或业务退出登录时调用 chat.destroy()。`
  })

  const highlightedFrontendSnippet = computed(() =>
    highlightJsCode(frontendSnippet.value),
  )

  return {
    starterApplicationSnippet,
    highlightedStarterApplicationSnippet,
    gatewaySnippet,
    highlightedGatewaySnippet,
    frontendSnippet,
    highlightedFrontendSnippet,
  }
}

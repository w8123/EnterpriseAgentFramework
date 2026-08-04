import { computed, type Ref } from 'vue'
import type {
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
  gatewayBaseUrl: Ref<string>
  embedTokenPath: Ref<string>
}

export function useSdkAccessWizardSnippets(
  deps: UseSdkAccessWizardSnippetsDeps,
) {
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
  project:
    code: ${deps.project.value?.projectCode || deps.projectCode.value}
    name: ${deps.project.value?.name || 'your-service-name'}
    base-url: ${deps.project.value?.baseUrl || 'http://localhost:8080'}
    # 必须填写 ReachAI 服务端实际可访问的业务系统地址；localhost 仅适用于同机、同网络命名空间联调。
    context-path: ${deps.project.value?.contextPath || ''}
    environment: ${deps.project.value?.environment || 'dev'}
  capability:
    scan-beans: true
    scan-mode: ANNOTATED_ONLY
    # SDK 接入阶段只注册项目与实例；接口扫描由 ReachAI API 管理手动触发。`)

  const highlightedStarterDependencySnippet = computed(() =>
    highlightXmlCode(starterDependencySnippet.value),
  )
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
            - Path=/api/reachai/embed-token

# Browser -> ReachAI Embed API. Preserve the ReachAI Embed Token Authorization header.
        - id: ${code}-reachai-embed
          uri: \${REACHAI_PLATFORM_URL:${platformUrl}}
          predicates:
            - Path=/api/reachai/embed/**
          filters:
            - RewritePath=/api/reachai/embed/(?<segment>.*), /api/embed/\${segment}
            - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST

# /reachai/registry/** and /reachai/capabilities/** must bypass ordinary business
# login/JWT filters and CSRF so traffic reaches the Starter. They are not
# unauthenticated: the Starter validates the registry signature or invocation token.
#
# /api/reachai/embed/** needs an independent security chain that does not run the
# business OAuth2 resource server. permitAll() alone is not sufficient.
# It is anonymous only to business login validation; do not remove Authorization.
# /api/reachai/embed-token intentionally remains on the normal business login chain.
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
      deps.embedTokenPath.value.trim() || '/api/reachai/embed-token'
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
const provisionedAgentKeySlug = '${expectedKeySlug}'

void createEafChat({
  mount: '#reachai-chat',
  apiBase: '${chatApiBase}',
  embedPathPrefix: '${embedPathPrefix}',
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
    if (!tokenContext?.pageInstanceId) {
      throw new Error('ReachAI page identity is unavailable')
    }
    const query = new URLSearchParams({
      projectCode: '${code}',
      agentId: provisionedAgentKeySlug,
      pageKey: tokenContext.pageKey || pageKey,
      pageInstanceId: tokenContext.pageInstanceId,
      route: tokenContext.route,
      origin: tokenContext.origin,
    })
    // If the business app uses bearer-token interceptors, replace fetch with its
    // existing authenticated HTTP client. The broker must receive business identity.
    const response = await fetch('${tokenBrokerUrl}?' + query, {
      credentials: 'include',
      signal: tokenContext?.signal,
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
}).catch((error) => {
  console.error(
    '[ReachAI] chat initialization failed',
    error instanceof Error ? error.message : 'unknown error',
  )
})

// Browser runtime only requests a short-lived embed token from the business token broker.
// Never store registry secrets, AI Coding task tokens or project-level keys in browser config.`
  })

  const highlightedFrontendSnippet = computed(() =>
    highlightJsCode(frontendSnippet.value),
  )

  return {
    starterDependencySnippet,
    starterApplicationSnippet,
    highlightedStarterDependencySnippet,
    highlightedStarterApplicationSnippet,
    gatewaySnippet,
    highlightedGatewaySnippet,
    frontendSnippet,
    highlightedFrontendSnippet,
  }
}

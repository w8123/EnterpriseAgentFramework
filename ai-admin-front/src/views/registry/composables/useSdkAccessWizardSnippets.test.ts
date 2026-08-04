import { ref } from 'vue'
import { describe, expect, it } from 'vitest'
import type {
  AiOnboardingManifest,
  ScanProject,
} from '@/types/scanProject'
import { useSdkAccessWizardSnippets } from './useSdkAccessWizardSnippets'

describe('useSdkAccessWizardSnippets', () => {
  it('keeps the SDK-owned page identity unchanged through the token broker', () => {
    const snippets = useSdkAccessWizardSnippets({
      projectCode: ref('demo-project'),
      project: ref({
        id: 27,
        projectCode: 'demo-project',
        name: 'Demo Project',
      } as ScanProject),
      aiOnboardingManifest: ref<AiOnboardingManifest | null>(null),
      gatewayBaseUrl: ref('http://localhost:8080'),
      embedTokenPath: ref('/api/reachai/embed-token'),
    })

    const snippet = snippets.frontendSnippet.value
    expect(snippets.gatewaySnippet.value)
      .toContain('Path=/api/reachai/embed/**')
    expect(snippets.gatewaySnippet.value)
      .toContain('Path=/api/reachai/embed-token')
    expect(snippets.gatewaySnippet.value)
      .toContain('/api/embed/${segment}')
    expect(snippets.gatewaySnippet.value)
      .toContain('must bypass ordinary business')
    expect(snippets.gatewaySnippet.value)
      .toContain('embed-token intentionally remains on the normal business login chain')
    expect(snippet).toContain("apiBase: 'http://localhost:8080'")
    expect(snippet).toContain("embedPathPrefix: '/api/reachai/embed'")
    expect(snippet)
      .toContain("fetch('http://localhost:8080/api/reachai/embed-token?'")
    expect(snippet).toContain("credentials: 'include'")
    expect(snippet).toContain('const pageBridge = createEafPageBridge({ route })')
    expect(snippet).toContain('pageKey: tokenContext.pageKey || pageKey')
    expect(snippet).toContain('pageInstanceId: tokenContext.pageInstanceId')
    expect(snippet).toContain('route: tokenContext.route')
    expect(snippet).toContain('origin: tokenContext.origin')
    expect(snippet).not.toContain('crypto.randomUUID')
    expect(snippet).not.toContain('sessionStorage')
    expect(snippet).not.toContain('pageInstanceId: pageBridge.pageInstanceId')
  })

  it('does not duplicate the starter heartbeat default in business config', () => {
    const snippets = useSdkAccessWizardSnippets({
      projectCode: ref('demo-project'),
      project: ref<ScanProject | null>(null),
      aiOnboardingManifest: ref<AiOnboardingManifest | null>(null),
      gatewayBaseUrl: ref('http://localhost:8080'),
      embedTokenPath: ref('/api/reachai/embed-token'),
    })

    expect(snippets.starterApplicationSnippet.value)
      .not.toContain('heartbeat-interval-ms')
    expect(snippets.starterApplicationSnippet.value)
      .toContain('scan-mode: ANNOTATED_ONLY')
  })
})

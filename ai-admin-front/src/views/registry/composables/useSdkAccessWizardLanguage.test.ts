import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AiOnboardingManifest, ScanProject } from '@/types/scanProject'
import type { SdkAccessLanguage } from './useSdkAccessWizardUiState'
import { useSdkAccessWizardLanguage } from './useSdkAccessWizardLanguage'
import { useSdkAccessWizardCsharpSnippets } from './useSdkAccessWizardCsharpSnippets'
import { useSdkAccessWizardPythonSnippets } from './useSdkAccessWizardPythonSnippets'

const mocks = vi.hoisted(() => ({ getManifest: vi.fn() }))
vi.mock('@/api/scanProject', () => ({ getAiOnboardingManifest: mocks.getManifest }))

describe('C# SDK access', () => {
  beforeEach(() => vi.resetAllMocks())

  it('loads C# metadata on selection and requires a verified matching artifact', async () => {
    const language = ref<SdkAccessLanguage>('java')
    const project = ref({ id: 27 } as ScanProject)
    const wizard = useSdkAccessWizardLanguage(language, project)
    expect(mocks.getManifest).not.toHaveBeenCalled()
    mocks.getManifest.mockResolvedValueOnce({ data: { sdkArtifacts: [
      { language: 'browser', packageName: '@reachai/embed-chat' },
      { language: 'csharp', packageName: 'ReachAI.AspNetCore', downloadUrl: '/package', integritySha256: 'a'.repeat(64), installCommandTemplate: 'dotnet add package' },
    ] } })

    language.value = 'csharp'
    await vi.waitFor(() => expect(wizard.csharpArtifactReady.value).toBe(true))
    expect(mocks.getManifest).toHaveBeenCalledWith(27, 'csharp')
    expect(wizard.csharpArtifact.value?.packageName).toBe('ReachAI.AspNetCore')

    mocks.getManifest.mockResolvedValueOnce({ data: { sdkArtifacts: [
      { language: 'csharp', packageName: 'ReachAI.AspNetCore', downloadUrl: '/package', integritySha256: null, installCommandTemplate: 'dotnet add package' },
    ] } })
    await wizard.loadCsharpManifest()
    expect(wizard.csharpArtifactReady.value).toBe(false)
  })

  it('ignores stale responses after language or project changes and exposes retryable errors', async () => {
    let resolveOld: ((value: unknown) => void) | undefined
    mocks.getManifest.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
    const language = ref<SdkAccessLanguage>('csharp')
    const project = ref({ id: 27 } as ScanProject)
    const wizard = useSdkAccessWizardLanguage(language, project)
    language.value = 'java'
    resolveOld?.({ data: { sdkArtifacts: [{ language: 'csharp', packageName: 'ReachAI.AspNetCore' }] } })
    await Promise.resolve()
    expect(wizard.csharpManifest.value).toBeNull()
    expect(wizard.csharpLoading.value).toBe(false)

    mocks.getManifest.mockRejectedValueOnce(new Error('network'))
    language.value = 'csharp'
    await vi.waitFor(() => expect(wizard.csharpError.value).toContain('无法读取'))
    mocks.getManifest.mockResolvedValueOnce({ data: { sdkArtifacts: [] } })
    await wizard.loadCsharpManifest()
    expect(wizard.csharpError.value).toBe('')
  })

  it('uses JSON POST for the C# broker and produces valid project configuration', () => {
    const project = ref({ projectCode: 'orders', name: '订单"系统', baseUrl: 'https://orders.example' } as ScanProject)
    const manifest = ref({ sdk: { config: { registryUrl: 'https://reachai.example' } } } as AiOnboardingManifest)
    const javaSnippet = ref("const response = await fetch('/reachai/embed/token?' + query, {\n  credentials: 'include'\n})")
    const snippets = useSdkAccessWizardCsharpSnippets(project, manifest, javaSnippet)
    expect(JSON.parse(snippets.application.value).ReachAI.ProjectName).toBe('订单"系统')
    expect(JSON.parse(snippets.application.value).ReachAI.CredentialFilePath).toContain('持久凭据文件路径')
    expect(snippets.frontend.value).toContain("fetch('/reachai/embed/token', {")
    expect(snippets.frontend.value).toContain("method: 'POST'")
    expect(snippets.frontend.value).toContain('JSON.stringify(Object.fromEntries(query.entries()))')
    expect(snippets.startup).toContain('IReachAiSecurityContextBridge')
  })

  it('loads only the verified Python artifact and produces a host-owned identity skeleton', async () => {
    mocks.getManifest.mockResolvedValueOnce({ data: { sdkArtifacts: [
      { language: 'python', packageName: 'reachai-fastapi', downloadUrl: '/python.whl', integritySha256: 'b'.repeat(64), installCommandTemplate: 'python -m pip install' },
    ] } })
    const language = ref<SdkAccessLanguage>('python')
    const project = ref({ id: 27, projectCode: 'orders', name: '订单"系统' } as ScanProject)
    const wizard = useSdkAccessWizardLanguage(language, project)
    await vi.waitFor(() => expect(wizard.pythonArtifactReady.value).toBe(true))
    expect(mocks.getManifest).toHaveBeenCalledWith(27, 'python')
    const snippet = useSdkAccessWizardPythonSnippets(project, wizard.pythonManifest).startup.value
    expect(snippet).toContain('reachai_fastapi')
    expect(snippet).toContain('REACHAI_CREDENTIAL_KEY')
    expect(snippet).toContain('REACHAI_BUSINESS_ORIGIN')
    expect(snippet).toContain('resolve_business_principal')
    expect(snippet).toContain('订单\\"系统')
  })

  it('loads Node artifacts only when Node is selected and rejects an unverified archive', async () => {
    const language = ref<SdkAccessLanguage>('node')
    const project = ref({ id: 27 } as ScanProject)
    mocks.getManifest.mockResolvedValueOnce({ data: { sdkArtifacts: [
      { language: 'node', packageName: '@reachai/node-sdk', downloadUrl: '/node.tgz', integritySha256: 'c'.repeat(64), installCommandTemplate: 'npm install' },
    ] } })
    const wizard = useSdkAccessWizardLanguage(language, project)
    await vi.waitFor(() => expect(wizard.nodeArtifactReady.value).toBe(true))
    expect(mocks.getManifest).toHaveBeenCalledWith(27, 'node')
    mocks.getManifest.mockResolvedValueOnce({ data: { sdkArtifacts: [
      { language: 'node', packageName: '@reachai/node-sdk', downloadUrl: '/node.tgz', integritySha256: null, installCommandTemplate: 'npm install' },
    ] } })
    await wizard.loadNodeManifest()
    expect(wizard.nodeArtifactReady.value).toBe(false)
    language.value = 'java'
    expect(wizard.nodeManifest.value).toBeNull()
  })

  it('loads only verified Go source metadata and clears it on language switch', async () => {
    const language = ref<SdkAccessLanguage>('go')
    const project = ref({ id: 27 } as ScanProject)
    mocks.getManifest.mockResolvedValueOnce({ data: { sdkArtifacts: [
      { language: 'go', packageName: 'github.com/w8123/EnterpriseAgentFramework/reachai-go', downloadUrl: '/go.zip', integritySha256: 'd'.repeat(64), installCommandTemplate: 'go mod edit' },
    ] } })
    const wizard = useSdkAccessWizardLanguage(language, project)
    await vi.waitFor(() => expect(wizard.goArtifactReady.value).toBe(true))
    expect(mocks.getManifest).toHaveBeenCalledWith(27, 'go')
    mocks.getManifest.mockResolvedValueOnce({ data: { sdkArtifacts: [
      { language: 'go', packageName: 'github.com/w8123/EnterpriseAgentFramework/reachai-go', downloadUrl: '/go.zip', integritySha256: null, installCommandTemplate: 'go mod edit' },
    ] } })
    await wizard.loadGoManifest()
    expect(wizard.goArtifactReady.value).toBe(false)
    language.value = 'java'
    expect(wizard.goManifest.value).toBeNull()
  })
})

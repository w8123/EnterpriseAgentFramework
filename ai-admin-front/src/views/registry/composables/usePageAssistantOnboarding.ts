import { computed, type ComputedRef, type Ref } from 'vue'
import type {
  PageActionRegistryView,
  PageRegistryView,
} from '@/api/embedOps'
import type {
  AiAccessSession,
  PageAssistantOnboardingManifest,
  PageAssistantSessionSummary,
  ScanProject,
} from '@/types/scanProject'
import { buildPageAssistantOnboardingPrompt } from '../pageAssistantOnboardingPrompt'
import { pageAssistantToolUrl } from '../pageAssistantWizardUtils'

type AiPromptTool = 'Cursor' | 'Codex' | 'Claude Code'

interface UsePageAssistantOnboardingDeps {
  aiPromptTool: Ref<AiPromptTool>
  project: Ref<ScanProject | null>
  projectCode: ComputedRef<string>
  selectedPage: ComputedRef<PageRegistryView | null>
  selectedPageKey: Ref<string>
  selectedActions: Ref<PageActionRegistryView[]>
  filteredActions: ComputedRef<PageActionRegistryView[]>
  pageAssistantManifest: Ref<PageAssistantOnboardingManifest | null>
  pageAssistantSession: Ref<AiAccessSession | null>
  pageAssistantSessions: Ref<PageAssistantSessionSummary[]>
  selectedPageAssistantAccess: Ref<PageAssistantSessionSummary | null>
}

export function usePageAssistantOnboarding(deps: UsePageAssistantOnboardingDeps) {
  const pageAssistantAccessGroups = computed(() => {
    const groups = [
      { key: 'WAITING_TARGET', title: '待确认', desc: '等待 Cursor 绑定目标页面', items: [] as PageAssistantSessionSummary[] },
      { key: 'IN_PROGRESS', title: '接入中', desc: 'AI 正在回传步骤进度', items: [] as PageAssistantSessionSummary[] },
      { key: 'COMPLETED', title: '已完成', desc: '可直接带入创建助手', items: [] as PageAssistantSessionSummary[] },
      { key: 'BLOCKED', title: '失败/阻塞', desc: '需要人工处理异常', items: [] as PageAssistantSessionSummary[] },
    ]
    const byKey = new Map(groups.map((group) => [group.key, group]))
    for (const session of deps.pageAssistantSessions.value) {
      const state = session.completionState || 'IN_PROGRESS'
      const group = byKey.get(state) || byKey.get('IN_PROGRESS')
      group?.items.push(session)
    }
    return groups
  })

  const pageAssistantAccessCount = computed(() => deps.pageAssistantSessions.value.length)
  const pageAssistantPromptActions = computed(() =>
    deps.selectedActions.value.length ? deps.selectedActions.value : deps.filteredActions.value)
  const pageAssistantActionKeys = computed(() => {
    const keys = pageAssistantPromptActions.value
      .map((action) => action.actionKey)
      .filter((key): key is string => Boolean(key))
    return Array.from(new Set(keys))
  })
  const pageAssistantSessionSteps = computed(() => deps.pageAssistantSession.value?.steps || [])
  const pageAssistantAccessDetailVisible = computed({
    get: () => Boolean(deps.selectedPageAssistantAccess.value),
    set: (visible: boolean) => {
      if (!visible) deps.selectedPageAssistantAccess.value = null
    },
  })
  const pageAssistantProgressText = computed(() => {
    const session = deps.pageAssistantSession.value
    if (!session) return '未开始'
    if (!session.totalSteps) return session.status || 'OPEN'
    return `${session.completedSteps}/${session.totalSteps} · ${session.status}`
  })
  const aiCodingAccessState = computed(() => {
    const access = deps.pageAssistantManifest.value?.aiCodingAccess
    if (!access?.enabled) return '未启用'
    return access.accessKey ? '已启用' : '已启用，未生成秘钥'
  })
  const pageAssistantProjectIdForTool = computed(() =>
    deps.project.value?.id || deps.pageAssistantManifest.value?.project.id || '')
  const pageAssistantToolRoot = computed(() => (
    pageAssistantProjectIdForTool.value
      ? `${window.location.origin}/api/ai-coding/projects/${pageAssistantProjectIdForTool.value}/page-assistant`
      : ''
  ))
  const pageAssistantSessionIdForTool = computed(() =>
    deps.pageAssistantSession.value?.sessionId || deps.pageAssistantManifest.value?.session.sessionId || '')
  const pageAssistantManifestUrlForTool = computed(() => (
    pageAssistantToolRoot.value
      ? `${pageAssistantToolRoot.value}/onboarding-manifest`
      : pageAssistantToolUrl(deps.pageAssistantManifest.value?.endpoints.manifestUrl)
  ))
  const pageAssistantLatestSessionUrlForTool = computed(() => (
    pageAssistantToolRoot.value
      ? `${pageAssistantToolRoot.value}/sessions/latest`
      : pageAssistantToolUrl(deps.pageAssistantManifest.value?.endpoints.latestSessionUrl)
  ))
  const pageAssistantStepReportUrlForTool = computed(() => (
    pageAssistantToolRoot.value && pageAssistantSessionIdForTool.value
      ? `${pageAssistantToolRoot.value}/sessions/${pageAssistantSessionIdForTool.value}/steps/{stepKey}/report`
      : pageAssistantToolUrl(deps.pageAssistantManifest.value?.endpoints.stepReportUrl)
  ))
  const pageAssistantTargetBindUrlForTool = computed(() => (
    pageAssistantToolRoot.value && pageAssistantSessionIdForTool.value
      ? `${pageAssistantToolRoot.value}/sessions/${pageAssistantSessionIdForTool.value}/target`
      : pageAssistantToolUrl(deps.pageAssistantManifest.value?.endpoints.targetBindUrl)
  ))
  const pageAssistantCatalogSyncUrlForTool = computed(() => (
    pageAssistantToolRoot.value && pageAssistantSessionIdForTool.value
      ? `${pageAssistantToolRoot.value}/sessions/${pageAssistantSessionIdForTool.value}/catalog/sync`
      : pageAssistantToolUrl(deps.pageAssistantManifest.value?.endpoints.catalogSyncUrl)
  ))
  const pageAssistantChecksRunUrlForTool = computed(() => (
    pageAssistantToolRoot.value && pageAssistantSessionIdForTool.value
      ? `${pageAssistantToolRoot.value}/sessions/${pageAssistantSessionIdForTool.value}/checks/run`
      : pageAssistantToolUrl(deps.pageAssistantManifest.value?.endpoints.checksRunUrl)
  ))
  const pageAssistantRegisterPageUrlForTool = computed(() => (
    pageAssistantToolRoot.value
      ? `${pageAssistantToolRoot.value}/pages/register`
      : pageAssistantToolUrl(deps.pageAssistantManifest.value?.endpoints.registerPageUrl)
  ))
  const pageAssistantAiCodingKeyArgument = computed(() => (
    deps.pageAssistantManifest.value?.aiCodingAccess.enabled ? ' -AiCodingKey $env:REACHAI_AI_CODING_KEY' : ''
  ))
  const pageAssistantScaffoldCommand = computed(() => {
    const manifestUrl = pageAssistantManifestUrlForTool.value || '<页面助手接入清单 URL>'
    return `.\\scripts\\reachai-page-assistant.ps1 scaffold -ManifestUrl "${manifestUrl}"${pageAssistantAiCodingKeyArgument.value} -Framework angular -OutputDir ".\\src\\app\\shared\\reachai"`
  })
  const pageAssistantVerifyCommand = computed(() => {
    const manifestUrl = pageAssistantManifestUrlForTool.value || '<页面助手接入清单 URL>'
    const routePattern = deps.selectedPage.value?.routePattern || '<目标路由>'
    const pageKey = deps.selectedPage.value?.pageKey || deps.selectedPageKey.value || '<pageKey>'
    return `.\\scripts\\reachai-page-assistant.ps1 verify -ManifestUrl "${manifestUrl}"${pageAssistantAiCodingKeyArgument.value} -FrontendUrl "<业务前端地址>" -Route "${routePattern}" -PageKey "${pageKey}"`
  })
  const pageAssistantOnboardingPrompt = computed(() => buildPageAssistantOnboardingPrompt({
    toolName: deps.aiPromptTool.value,
    platformUrl: window.location.origin,
    project: {
      id: deps.project.value?.id,
      projectCode: deps.project.value?.projectCode || deps.projectCode.value,
      name: deps.project.value?.name || deps.projectCode.value,
      appKey: deps.pageAssistantManifest.value?.project.registryAppKey || deps.project.value?.registryAppKey,
    },
    page: deps.selectedPage.value
      ? {
        pageKey: deps.selectedPage.value.pageKey,
        name: deps.selectedPage.value.name,
        routePattern: deps.selectedPage.value.routePattern,
      }
      : {
        pageKey: deps.selectedPageKey.value,
        name: deps.selectedPageKey.value,
        routePattern: '',
      },
    actions: pageAssistantPromptActions.value,
    progress: {
      aiCodingAccessKey: deps.pageAssistantManifest.value?.aiCodingAccess.accessKey,
      authMode: deps.pageAssistantManifest.value?.auth?.mode,
      authHeaderName: deps.pageAssistantManifest.value?.auth?.headerName,
      authKeyEnv: deps.pageAssistantManifest.value?.auth?.keyEnv,
      externalToolPath: pageAssistantToolRoot.value
        ? `${pageAssistantToolRoot.value}/**`
        : deps.pageAssistantManifest.value?.auth?.externalToolPath,
      platformSessionPath: deps.pageAssistantManifest.value?.auth?.platformSessionPath,
      appSecretEnv: deps.pageAssistantManifest.value?.security.appSecretEnv,
      sessionId: deps.pageAssistantSession.value?.sessionId || deps.pageAssistantManifest.value?.session.sessionId,
      manifestUrl: pageAssistantManifestUrlForTool.value,
      latestSessionUrl: pageAssistantLatestSessionUrlForTool.value,
      stepReportUrl: pageAssistantStepReportUrlForTool.value,
      targetBindUrl: pageAssistantTargetBindUrlForTool.value,
      catalogSyncUrl: pageAssistantCatalogSyncUrlForTool.value,
      checksRunUrl: pageAssistantChecksRunUrlForTool.value,
      registerPageUrl: pageAssistantRegisterPageUrlForTool.value,
      skillPackageUrl: deps.pageAssistantManifest.value?.endpoints.skillPackageUrl
        || deps.pageAssistantManifest.value?.scaffold?.skillPackageUrl,
      scriptDownloadUrl: deps.pageAssistantManifest.value?.endpoints.scriptDownloadUrl
        || deps.pageAssistantManifest.value?.scaffold?.scriptDownloadUrl,
      helperScriptPath: deps.pageAssistantManifest.value?.scaffold?.helperScriptPath || 'scripts/reachai-page-assistant.ps1',
      scaffoldCommand: pageAssistantScaffoldCommand.value,
      verifyCommand: pageAssistantVerifyCommand.value,
      bridgeApiGlobal: deps.pageAssistantManifest.value?.pageActionContract?.bridgeApi?.global
        || `window.${deps.pageAssistantManifest.value?.pageActionContract?.bridgeGlobal || '__REACHAI_PAGE_BRIDGE__'}`,
    },
  }))

  return {
    pageAssistantAccessGroups,
    pageAssistantAccessCount,
    pageAssistantActionKeys,
    pageAssistantSessionSteps,
    pageAssistantAccessDetailVisible,
    pageAssistantProgressText,
    aiCodingAccessState,
    pageAssistantScaffoldCommand,
    pageAssistantVerifyCommand,
    pageAssistantOnboardingPrompt,
  }
}

import { ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  PageActionRegistryView,
  PageRegistryView,
} from '@/api/embedOps'
import {
  getPageAssistantAccessSessions,
  getLatestPageAssistantAccessSession,
  getPageAssistantOnboardingManifest,
  runPageAssistantAccessSessionChecks,
} from '@/api/scanProject'
import type {
  AiAccessSession,
  PageAssistantOnboardingManifest,
  PageAssistantSessionRequest,
  PageAssistantSessionSummary,
  ScanProject,
} from '@/types/scanProject'

type AiPromptTool = 'Cursor' | 'Codex' | 'Claude Code'

interface UsePageAssistantSessionsDeps {
  aiPromptTool: Ref<AiPromptTool>
  project: Ref<ScanProject | null>
  selectedPage: ComputedRef<PageRegistryView | null>
  selectedPageKey: Ref<string>
  pageActions: Ref<PageActionRegistryView[]>
  pageAssistantActionKeys: ComputedRef<string[]>
  pageAssistantManifest: Ref<PageAssistantOnboardingManifest | null>
  pageAssistantSession: Ref<AiAccessSession | null>
  pageAssistantSessions: Ref<PageAssistantSessionSummary[]>
  selectedPageAssistantAccess: Ref<PageAssistantSessionSummary | null>
}

export function usePageAssistantSessions(deps: UsePageAssistantSessionsDeps) {
  const pageAssistantManifestLoading = ref(false)
  const pageAssistantCheckRunning = ref(false)
  const pageAssistantSessionsLoading = ref(false)

  function pageAssistantSessionRequest(): PageAssistantSessionRequest {
    return {
      toolName: deps.aiPromptTool.value,
      pageKey: deps.selectedPage.value?.pageKey || deps.selectedPageKey.value || undefined,
      routePattern: deps.selectedPage.value?.routePattern || undefined,
      actionKeys: deps.pageAssistantActionKeys.value,
    }
  }

  function pageActionKeysForSession(session: PageAssistantSessionSummary) {
    return deps.pageActions.value
      .filter((action) => action.pageKey === session.targetPageKey)
      .map((action) => action.actionKey)
      .filter((key): key is string => Boolean(key))
  }

  async function loadPageAssistantSessions(options: { silent?: boolean } = {}) {
    if (!deps.project.value?.id) {
      if (!options.silent) ElMessage.warning('项目详情尚未加载完成，请稍后再试')
      return
    }
    pageAssistantSessionsLoading.value = true
    try {
      const { data } = await getPageAssistantAccessSessions(deps.project.value.id)
      deps.pageAssistantSessions.value = data || []
      if (deps.selectedPageAssistantAccess.value) {
        deps.selectedPageAssistantAccess.value = deps.pageAssistantSessions.value.find((item) =>
          item.sessionId === deps.selectedPageAssistantAccess.value?.sessionId) || deps.selectedPageAssistantAccess.value
      }
    } catch (error) {
      if (!options.silent) {
        ElMessage.warning((error as Error).message || '加载页面接入进度失败')
      }
    } finally {
      pageAssistantSessionsLoading.value = false
    }
  }

  async function loadPageAssistantManifest(options: { silent?: boolean } = {}) {
    if (!deps.project.value?.id) {
      if (!options.silent) ElMessage.warning('项目详情尚未加载完成，请稍后再试')
      return
    }
    pageAssistantManifestLoading.value = true
    try {
      const { data } = await getPageAssistantOnboardingManifest(deps.project.value.id, pageAssistantSessionRequest())
      deps.pageAssistantManifest.value = data
      deps.pageAssistantSession.value = data.session
      await loadPageAssistantSessions({ silent: true })
    } catch (error) {
      if (!options.silent) {
        ElMessage.warning((error as Error).message || '获取页面助手 AI 接入会话失败')
      }
    } finally {
      pageAssistantManifestLoading.value = false
    }
  }

  async function refreshPageAssistantSession() {
    if (!deps.project.value?.id) {
      ElMessage.warning('项目详情尚未加载完成，请稍后再试')
      return
    }
    pageAssistantManifestLoading.value = true
    try {
      const { data } = await getLatestPageAssistantAccessSession(
        deps.project.value.id,
        deps.selectedPage.value?.pageKey || deps.selectedPageKey.value,
      )
      deps.pageAssistantSession.value = data
      await loadPageAssistantSessions({ silent: true })
    } catch {
      await loadPageAssistantManifest({ silent: true })
    } finally {
      pageAssistantManifestLoading.value = false
    }
  }

  async function runPageAssistantSelfCheck() {
    const session = deps.pageAssistantSession.value
    if (!deps.project.value?.id || !session?.sessionId) {
      ElMessage.warning('请先创建页面助手 AI 接入会话')
      return
    }
    pageAssistantCheckRunning.value = true
    try {
      const { data } = await runPageAssistantAccessSessionChecks(deps.project.value.id, session.sessionId, {
        pageKey: deps.selectedPage.value?.pageKey || deps.selectedPageKey.value || undefined,
        routePattern: deps.selectedPage.value?.routePattern || undefined,
        actionKeys: deps.pageAssistantActionKeys.value,
      })
      deps.pageAssistantSession.value = data.session
      await loadPageAssistantSessions({ silent: true })
      ElMessage.success(`页面助手自检完成：${data.checkResult.overallStatus}`)
    } catch (error) {
      ElMessage.error((error as Error).message || '页面助手自检失败')
    } finally {
      pageAssistantCheckRunning.value = false
    }
  }

  async function runPageAssistantCardCheck(session: PageAssistantSessionSummary) {
    if (!deps.project.value?.id) {
      ElMessage.warning('项目详情尚未加载完成，请稍后再试')
      return
    }
    pageAssistantCheckRunning.value = true
    try {
      const { data } = await runPageAssistantAccessSessionChecks(deps.project.value.id, session.sessionId, {
        pageKey: session.targetPageKey || undefined,
        routePattern: session.targetRoute || undefined,
        actionKeys: pageActionKeysForSession(session),
      })
      deps.pageAssistantSession.value = data.session
      await loadPageAssistantSessions({ silent: true })
      ElMessage.success(`页面助手自检完成：${data.checkResult.overallStatus}`)
    } catch (error) {
      ElMessage.error((error as Error).message || '页面助手自检失败')
    } finally {
      pageAssistantCheckRunning.value = false
    }
  }

  return {
    pageAssistantManifestLoading,
    pageAssistantCheckRunning,
    pageAssistantSessionsLoading,
    loadPageAssistantSessions,
    loadPageAssistantManifest,
    refreshPageAssistantSession,
    runPageAssistantSelfCheck,
    runPageAssistantCardCheck,
  }
}

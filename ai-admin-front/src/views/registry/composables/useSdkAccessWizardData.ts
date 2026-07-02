import { computed, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
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
} from '@/api/scanProject'
import type { ApiAssetItem } from '@/types/apiAsset'
import type { ProjectInstance } from '@/types/registry'
import type {
  AiAccessSession,
  AiOnboardingManifest,
  ScanProject,
  SdkAccessCheckResponse,
} from '@/types/scanProject'

export interface UseSdkAccessWizardDataDeps {
  selectedApiAssetId: Ref<number | null>
  argsText: Ref<string>
  gatewayBaseUrl: Ref<string>
  embedTokenPath: Ref<string>
  aiPromptTool: Ref<'cursor' | 'claude' | 'codex'>
  projectCode?: Ref<string>
}

export function useSdkAccessWizardData(deps: UseSdkAccessWizardDataDeps) {
  const route = useRoute()
  const projectCode = deps.projectCode ?? computed(() => String(route.params.projectCode || ''))
  const project = ref<ScanProject | null>(null)
  const instances = ref<ProjectInstance[]>([])
  const apiAssets = ref<ApiAssetItem[]>([])
  const loading = ref(false)
  const checking = ref(false)
  const aiPromptLoading = ref(false)
  const aiPromptDialogVisible = ref(false)
  const aiOnboardingManifest = ref<AiOnboardingManifest | null>(null)
  const accessSession = ref<AiAccessSession | null>(null)
  const aiCodingAccessEnabled = ref(false)
  const aiCodingAccessKey = ref('')
  const checkResult = ref<SdkAccessCheckResponse | null>(null)

  const aiCodingAccessDisplayKey = computed(() =>
    aiCodingAccessEnabled.value && aiCodingAccessKey.value.trim() ? aiCodingAccessKey.value.trim() : '',
  )

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
    const { data } = await startAiAccessSession(projectId, deps.aiPromptTool.value)
    accessSession.value = data
    return data
  }

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
      if (!deps.selectedApiAssetId.value) {
        deps.selectedApiAssetId.value = callableApiAssets.value[0]?.apiId || apiAssets.value[0]?.apiId || null
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

  async function runCheck() {
    const p = project.value
    if (!p?.id) return
    let args: Record<string, unknown>
    try {
      const parsed = JSON.parse(deps.argsText.value || '{}')
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('参数 JSON 必须是对象')
      args = parsed as Record<string, unknown>
    } catch (error) {
      ElMessage.warning(error instanceof Error ? error.message : '参数 JSON 格式不正确')
      return
    }
    checking.value = true
    try {
      const payload = {
        apiAssetId: deps.selectedApiAssetId.value,
        args,
        gatewayBaseUrl: deps.gatewayBaseUrl.value,
        embedTokenPath: deps.embedTokenPath.value,
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

  return {
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
  }
}

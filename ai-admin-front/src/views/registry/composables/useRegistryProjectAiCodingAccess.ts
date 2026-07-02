import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getAiOnboardingManifest,
  updateAiCodingAccess,
} from '@/api/scanProject'
import type { ScanProject } from '@/types/scanProject'

export interface UseRegistryProjectAiCodingAccessDeps {
  project: Ref<ScanProject | null>
  projectCode: Ref<string>
}

export function useRegistryProjectAiCodingAccess(deps: UseRegistryProjectAiCodingAccessDeps) {
  const aiCodingAccessSaving = ref(false)
  const aiCodingAccessEnabled = ref(false)
  const aiCodingAccessKey = ref('')
  const aiCodingDialogVisible = ref(false)

  const reachAiPlatformUrl = computed(
    () => import.meta.env.VITE_REACHAI_CONTROL_SERVICE_URL?.trim() || window.location.origin,
  )

  const gatewayManifestUrl = computed(() =>
    deps.project.value?.id ? `${reachAiPlatformUrl.value}/api/ai-coding/projects/${deps.project.value.id}/manifest` : '',
  )

  const contextCandidatesUrl = computed(() =>
    deps.project.value?.id
      ? `${reachAiPlatformUrl.value}/api/ai-coding/projects/${deps.project.value.id}/context-candidates`
      : '',
  )

  const contextCandidatesBatchUrl = computed(() =>
    contextCandidatesUrl.value ? `${contextCandidatesUrl.value}/batch` : '',
  )

  const contextCandidateStatusUrlTemplate = computed(() =>
    contextCandidatesUrl.value ? `${contextCandidatesUrl.value}?traceId={submissionId}&status=PENDING` : '',
  )

  const contextCandidateAuditUrlTemplate = computed(() =>
    deps.project.value?.id
      ? `${reachAiPlatformUrl.value}/context/governance?tab=audit&projectId=${deps.project.value.id}&traceId={submissionId}`
      : '',
  )

  const aiCodingKeyDisplay = computed(() => {
    if (!aiCodingAccessEnabled.value) return '未启用'
    const key = aiCodingAccessKey.value.trim()
    return key || '已启用（保存后显示）'
  })

  const aiCodingInfoRows = computed(() => {
    const project = deps.project.value
    return [
      {
        label: 'ReachAI 平台地址',
        displayValue: reachAiPlatformUrl.value,
        copyValue: reachAiPlatformUrl.value,
      },
      {
        label: '项目 ID',
        displayValue: project?.id ? String(project.id) : '-',
        copyValue: project?.id ? String(project.id) : '',
      },
      {
        label: '项目编码',
        displayValue: project?.projectCode || deps.projectCode.value || '-',
        copyValue: project?.projectCode || deps.projectCode.value || '',
      },
      {
        label: '项目名称',
        displayValue: project?.name || '-',
        copyValue: project?.name || '',
      },
      {
        label: 'App Key',
        displayValue: project?.registryAppKey || '未配置',
        copyValue: project?.registryAppKey || '',
      },
      {
        label: 'AI Coding 接入秘钥',
        displayValue: aiCodingKeyDisplay.value,
        copyValue: aiCodingAccessEnabled.value && aiCodingAccessKey.value.trim() ? aiCodingAccessKey.value.trim() : '',
      },
      {
        label: 'AI Coding Gateway Manifest',
        displayValue: gatewayManifestUrl.value || '-',
        copyValue: gatewayManifestUrl.value,
      },
      {
        label: '上下文候选提交 URL',
        displayValue: contextCandidatesUrl.value || '-',
        copyValue: contextCandidatesUrl.value,
      },
      {
        label: '上下文候选批量提交 URL',
        displayValue: contextCandidatesBatchUrl.value || '-',
        copyValue: contextCandidatesBatchUrl.value,
      },
      {
        label: '上下文候选状态查询 URL 模板',
        displayValue: contextCandidateStatusUrlTemplate.value || '-',
        copyValue: contextCandidateStatusUrlTemplate.value,
      },
      {
        label: '上下文候选审计 URL 模板',
        displayValue: contextCandidateAuditUrlTemplate.value || '-',
        copyValue: contextCandidateAuditUrlTemplate.value,
      },
      {
        label: 'AI Coding Header',
        displayValue: 'X-ReachAI-AiCoding-Key',
        copyValue: 'X-ReachAI-AiCoding-Key',
      },
    ]
  })

  const aiCodingBundleText = computed(() =>
    aiCodingInfoRows.value
      .filter((row) => row.copyValue)
      .map((row) => `${row.label}：${row.copyValue}`)
      .join('\n'),
  )

  async function loadAiCodingAccess(projectId: number) {
    try {
      const { data } = await getAiOnboardingManifest(projectId)
      aiCodingAccessEnabled.value = data.aiCodingAccess?.enabled ?? false
      aiCodingAccessKey.value = data.aiCodingAccess?.accessKey || ''
    } catch {
      aiCodingAccessEnabled.value = false
      aiCodingAccessKey.value = ''
    }
  }

  async function saveAiCodingAccess() {
    const project = deps.project.value
    if (!project?.id) return
    aiCodingAccessSaving.value = true
    try {
      const { data } = await updateAiCodingAccess(project.id, {
        enabled: aiCodingAccessEnabled.value,
        accessKey: aiCodingAccessKey.value.trim() || null,
      })
      aiCodingAccessEnabled.value = data.enabled
      aiCodingAccessKey.value = data.accessKey || ''
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

  function openAiCodingDialog() {
    aiCodingDialogVisible.value = true
  }

  async function copyText(text: string, label?: string) {
    const value = text.trim()
    if (!value) {
      ElMessage.warning(label ? `${label} 暂无可复制内容` : '暂无可复制内容')
      return
    }
    try {
      await navigator.clipboard.writeText(value)
      ElMessage.success(label ? `已复制${label}` : '已复制')
    } catch {
      ElMessage.error('复制失败，请手动选择文本')
    }
  }

  async function copyAiCodingBundle() {
    const text = aiCodingBundleText.value.trim()
    if (!text) {
      ElMessage.warning('请先启用并保存 AI Coding 接入秘钥')
      return
    }
    await copyText(text, '全部接入信息')
  }

  return {
    aiCodingAccessSaving,
    aiCodingAccessEnabled,
    aiCodingAccessKey,
    aiCodingDialogVisible,
    aiCodingInfoRows,
    loadAiCodingAccess,
    saveAiCodingAccess,
    clearAiCodingAccess,
    openAiCodingDialog,
    copyText,
    copyAiCodingBundle,
  }
}

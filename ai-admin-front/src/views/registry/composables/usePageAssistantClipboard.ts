import { ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { AiAccessSession, ScanProject } from '@/types/scanProject'

interface UsePageAssistantClipboardDeps {
  sdkTemplate: ComputedRef<string>
  pageAssistantOnboardingPrompt: ComputedRef<string>
  workflowAiCodingPrompt: ComputedRef<string>
  pageAssistantSession: Ref<AiAccessSession | null>
  project: Ref<ScanProject | null>
  loadPageAssistantManifest: (options?: { silent?: boolean }) => Promise<void>
}

export function usePageAssistantClipboard(deps: UsePageAssistantClipboardDeps) {
  const sdkTemplateCopied = ref(false)
  const aiPromptCopied = ref(false)
  const workflowAiCodingPromptCopied = ref(false)

  let sdkCopyTimer: ReturnType<typeof setTimeout> | undefined
  let aiPromptCopyTimer: ReturnType<typeof setTimeout> | undefined
  let workflowAiCodingPromptCopyTimer: ReturnType<typeof setTimeout> | undefined

  function markCopied(target: Ref<boolean>, timer: ReturnType<typeof setTimeout> | undefined, clearTimer: (timer: ReturnType<typeof setTimeout> | undefined) => void) {
    target.value = true
    if (timer) {
      clearTimeout(timer)
    }
    const nextTimer = setTimeout(() => {
      target.value = false
      clearTimer(undefined)
    }, 1600)
    clearTimer(nextTimer)
  }

  async function copySdkTemplate() {
    try {
      await navigator.clipboard.writeText(deps.sdkTemplate.value)
      markCopied(sdkTemplateCopied, sdkCopyTimer, (timer) => { sdkCopyTimer = timer })
      ElMessage.success('模板已复制')
    } catch {
      ElMessage.warning('复制失败，请手动选择模板')
    }
  }

  async function copyPageAssistantPrompt() {
    try {
      if (!deps.pageAssistantSession.value && deps.project.value?.id) {
        await deps.loadPageAssistantManifest({ silent: true })
      }
      await navigator.clipboard.writeText(deps.pageAssistantOnboardingPrompt.value)
      markCopied(aiPromptCopied, aiPromptCopyTimer, (timer) => { aiPromptCopyTimer = timer })
      ElMessage.success('页面助手接入提示词已复制')
    } catch {
      ElMessage.warning('复制失败，请手动选择提示词')
    }
  }

  async function copyWorkflowAiCodingPrompt() {
    try {
      await navigator.clipboard.writeText(deps.workflowAiCodingPrompt.value)
      markCopied(workflowAiCodingPromptCopied, workflowAiCodingPromptCopyTimer, (timer) => {
        workflowAiCodingPromptCopyTimer = timer
      })
      ElMessage.success('Workflow AI Coding 提示词已复制')
    } catch {
      ElMessage.warning('复制失败，请手动选择提示词')
    }
  }

  async function copyText(value: string, successMessage: string) {
    try {
      await navigator.clipboard.writeText(value)
      ElMessage.success(successMessage)
    } catch {
      ElMessage.warning('复制失败，请手动选择内容')
    }
  }

  return {
    sdkTemplateCopied,
    aiPromptCopied,
    workflowAiCodingPromptCopied,
    copySdkTemplate,
    copyPageAssistantPrompt,
    copyWorkflowAiCodingPrompt,
    copyText,
  }
}
